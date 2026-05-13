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

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
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
          paymentMethod?: string;
          items?: Array<{ quantity: number; unitPrice: number }>;
        };
        const paymentMethod = requestBody.paymentMethod ?? 'CARD';
        const orderId = paymentMethod === 'DELAY_CARD' ? 'ord_app_delay_001' : 'ord_app_001';
        const totalAmount = (requestBody.items ?? []).reduce((sum, item) => sum + item.quantity * item.unitPrice, 0);

        return jsonResponse(
          {
            success: true,
            data: {
              orderId,
              status: 'CREATED',
              sagaStatus: 'STARTED',
              paymentMethod,
              totalAmount,
            },
            trace: { correlationId: 'corr-order-create' },
          },
          201,
        );
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
            totalAmount: 24000,
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
            totalAmount: 12000,
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
});
