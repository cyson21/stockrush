import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const jsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

describe('Customer order flow', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let quoteMode: 'success' | 'error' = 'success';

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockClear();
    quoteMode = 'success';
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);

      if (url === '/catalog/api/products?status=ON_SALE') {
        return jsonResponse({
          success: true,
          data: [
            {
              productCode: 'LIMITED-001',
              name: 'Limited Hoodie',
              status: 'ON_SALE',
              listPrice: 12000,
            },
          ],
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === '/inventory/api/stocks?productCode=LIMITED-001') {
        return jsonResponse({
          success: true,
          data: [
            {
              skuId: 'SKU-001',
              productCode: 'LIMITED-001',
              availableQuantity: 10,
              reservedQuantity: 2,
              version: 3,
            },
          ],
          trace: { correlationId: 'corr-stock' },
        });
      }

      if (url === '/orders/api/orders' && init?.method === 'POST') {
        const requestBody = JSON.parse(String(init.body)) as {
          couponCode?: string;
          paymentMethod?: string;
          items?: Array<{ quantity: number; unitPrice: number }>;
        };
        const paymentMethod = requestBody.paymentMethod ?? 'CARD';
        const orderId = paymentMethod === 'DELAY_CARD' ? 'ord_app_delay_001' : 'ord_app_001';
        const totalAmount = (requestBody.items ?? []).reduce((sum, item) => sum + item.quantity * item.unitPrice, 0);
        const discountAmount = requestBody.couponCode === 'WELCOME10' ? 5000 : 0;

        return jsonResponse(
          {
            success: true,
            data: {
              orderId,
              status: 'CREATED',
              sagaStatus: 'STARTED',
              paymentMethod,
              couponCode: requestBody.couponCode ?? null,
              totalAmount,
              discountAmount,
              payableAmount: totalAmount - discountAmount,
            },
            trace: { correlationId: 'corr-order-create' },
          },
          201,
        );
      }

      if (url === '/promotion/api/coupons/quote' && init?.method === 'POST') {
        if (quoteMode === 'error') {
          return jsonResponse(
            {
              success: false,
              data: null,
              error: {
                code: 'PROMOTION_COUPON_NOT_FOUND',
                message: '쿠폰 코드를 확인해주세요.',
                details: {},
              },
              trace: { correlationId: 'corr-promotion-quote' },
            },
            404,
          );
        }

        const requestBody = JSON.parse(String(init.body)) as { couponCode: string; orderAmount: number };
        const requestedAmount = requestBody.orderAmount ?? 0;
        const isWelcome10 = requestBody.couponCode === 'WELCOME10';
        const discountAmount = isWelcome10 ? 5000 : 0;

        return jsonResponse({
          success: true,
          data: {
            couponCode: requestBody.couponCode,
            applied: isWelcome10,
            discountAmount,
            payAmount: requestedAmount - discountAmount,
            reason: isWelcome10 ? 'APPLIED' : 'INVALID_COUPON',
          },
          trace: { correlationId: 'corr-promotion-quote' },
        });
      }

      if (url === '/orders/api/orders/ord_app_001') {
        return jsonResponse({
          success: true,
          data: {
            orderId: 'ord_app_001',
            memberId: 'member-portfolio',
            status: 'CANCELLED',
            sagaStatus: 'FAILED',
            paymentMethod: 'FAIL_CARD',
            couponCode: null,
            totalAmount: 24000,
            discountAmount: 0,
            payableAmount: 24000,
            items: [
              {
                productCode: 'LIMITED-001',
                skuId: 'SKU-001',
                quantity: 2,
                unitPrice: 12000,
                lineAmount: 24000,
              },
            ],
          },
          trace: { correlationId: 'corr-order-detail' },
        });
      }

      if (url === '/orders/api/orders/ord_app_delay_001') {
        return jsonResponse({
          success: true,
          data: {
            orderId: 'ord_app_delay_001',
            memberId: 'member-portfolio',
            status: 'CREATED',
            sagaStatus: 'PAYMENT_DELAYED',
            paymentMethod: 'DELAY_CARD',
            couponCode: null,
            totalAmount: 12000,
            discountAmount: 0,
            payableAmount: 12000,
            items: [
              {
                productCode: 'LIMITED-001',
                skuId: 'SKU-001',
                quantity: 1,
                unitPrice: 12000,
                lineAmount: 12000,
              },
            ],
          },
          trace: { correlationId: 'corr-order-delay-detail' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('selects a product, creates an order, and shows saga status', async () => {
    const user = userEvent.setup();

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();

    await user.clear(screen.getByLabelText('수량'));
    await user.type(screen.getByLabelText('수량'), '2');
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    expect(screen.getByRole('option', { name: 'DELAY_CARD' })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('결제수단'), 'FAIL_CARD');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    await screen.findByText('ord_app_001');
    expect(screen.getByText('CANCELLED')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();

    const orderRequest = fetchMock.mock.calls.find(([url, init]) => url === '/orders/api/orders' && init?.method === 'POST');
    expect(orderRequest).toBeDefined();

    const [, init] = orderRequest!;
    expect(init?.headers).toMatchObject({
      'Content-Type': 'application/json',
      'Idempotency-Key': expect.stringMatching(/^customer-app-/),
      'X-Correlation-Id': 'customer-app-order-create',
    });
    expect(JSON.parse(String(init?.body))).toEqual({
      memberId: 'member-portfolio',
      paymentMethod: 'FAIL_CARD',
      items: [
        {
          productCode: 'LIMITED-001',
          skuId: 'SKU-001',
          quantity: 2,
          unitPrice: 12000,
        },
      ],
    });

    const statusPanel = screen.getByRole('region', { name: '주문 상태' });
    expect(within(statusPanel).getByText('FAIL_CARD')).toBeInTheDocument();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/orders/api/orders/ord_app_001', expect.objectContaining({ method: 'GET' }));
    });
  });

  it('creates a delayed payment order and shows delayed saga status', async () => {
    const user = userEvent.setup();

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();

    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.selectOptions(screen.getByLabelText('결제수단'), 'DELAY_CARD');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    await screen.findByText('ord_app_delay_001');

    const statusPanel = screen.getByRole('region', { name: '주문 상태' });
    expect(within(statusPanel).getByText('CREATED')).toBeInTheDocument();
    expect(within(statusPanel).getByText('PAYMENT_DELAYED')).toBeInTheDocument();
    expect(within(statusPanel).getByText('DELAY_CARD')).toBeInTheDocument();

    const orderRequest = fetchMock.mock.calls.find(
      ([url, init]) =>
        url === '/orders/api/orders' &&
        init?.method === 'POST' &&
        JSON.parse(String(init.body)).paymentMethod === 'DELAY_CARD',
    );
    expect(orderRequest).toBeDefined();
    expect(JSON.parse(String(orderRequest?.[1]?.body))).toMatchObject({
      memberId: 'member-portfolio',
      paymentMethod: 'DELAY_CARD',
    });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/orders/api/orders/ord_app_delay_001',
        expect.objectContaining({ method: 'GET' }),
      );
    });
  });

  it('applies coupon quote and uses discount in checkout summary', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();

    await user.type(screen.getByLabelText('쿠폰 코드'), 'WELCOME10');
    await user.click(screen.getByRole('button', { name: '쿠폰 적용' }));

    await screen.findByText('쿠폰 적용: APPLIED');
    expect(screen.getByText('할인 금액')).toBeInTheDocument();
    expect(screen.getByText('₩5,000')).toBeInTheDocument();
    expect(screen.getByText('결제 예정 금액')).toBeInTheDocument();
    expect(screen.getByText('₩7,000')).toBeInTheDocument();

    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.selectOptions(screen.getByLabelText('결제수단'), 'FAIL_CARD');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    const orderRequest = fetchMock.mock.calls.find(
      ([url, init]) => url === '/orders/api/orders' && init?.method === 'POST',
    );
    expect(orderRequest).toBeDefined();
    expect(JSON.parse(String(orderRequest?.[1]?.body))).toMatchObject({
      memberId: 'member-portfolio',
      paymentMethod: 'FAIL_CARD',
      couponCode: 'WELCOME10',
      items: [
        {
          productCode: 'LIMITED-001',
          skuId: 'SKU-001',
          quantity: 1,
          unitPrice: 12000,
        },
      ],
    });
  });

  it('shows inline error when coupon quote fails and does not apply discount', async () => {
    const user = userEvent.setup();
    quoteMode = 'error';

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('쿠폰 코드'), 'BAD01');
    await user.click(screen.getByRole('button', { name: '쿠폰 적용' }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');

    expect(await screen.findByRole('alert')).toHaveTextContent('쿠폰 적용 실패: PROMOTION_COUPON_NOT_FOUND: 쿠폰 코드를 확인해주세요.');
    expect(screen.getByText('할인 금액')).toBeInTheDocument();
    expect(screen.getByText('₩0')).toBeInTheDocument();
    expect(screen.getAllByText('₩12,000').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/orders/api/orders' && init?.method === 'POST')).toBe(
      false,
    );
  });

  it('blocks checkout when coupon quote returns not applied', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('쿠폰 코드'), 'BAD01');
    await user.click(screen.getByRole('button', { name: '쿠폰 적용' }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');

    await screen.findByText('쿠폰 적용: INVALID_COUPON');
    expect(screen.getByText('할인 금액')).toBeInTheDocument();
    expect(screen.getByText('₩0')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/orders/api/orders' && init?.method === 'POST')).toBe(
      false,
    );
  });
});
