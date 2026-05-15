import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import {
  clearAuthSession,
  getAuthSession,
  setAuthSessionForTest,
} from './auth/oidc';

type ApiMode = 'success' | 'error';

const TEST_ACCESS_TOKEN = 'test-access-token';

const jsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

const getAuthorizationHeaderValue = (headers?: HeadersInit): string | null => {
  if (!headers) {
    return null;
  }

  if (headers instanceof Headers) {
    return headers.get('Authorization');
  }

  if (Array.isArray(headers)) {
    const matched = headers.find(([name]) => name.toLowerCase() === 'authorization');
    return matched ? matched[1] : null;
  }

  return headers.Authorization ?? headers['authorization'] ?? null;
};

const errorResponse = (code: string, message: string, status = 500) =>
  jsonResponse({
    success: false,
    data: null,
    error: {
      code,
      message,
      details: {},
    },
    trace: { correlationId: 'corr-error' },
  }, status);

type OrderDetailFixture = {
  orderId: string;
  memberId: string;
  status: string;
  sagaStatus: string;
  paymentMethod: string;
  couponCode: string | null;
  totalAmount: number;
  discountAmount: number;
  payableAmount: number;
  items: Array<{
    productCode: string;
    skuId: string;
    quantity: number;
    unitPrice: number;
    lineAmount: number;
  }>;
};

const productFixture = {
  productCode: 'LIMITED-001',
  name: 'Limited Hoodie',
  status: 'ON_SALE',
  listPrice: 12000,
};

const searchProductFixture = {
  productCode: 'LIMITED-002',
  name: 'Limited Cap',
  status: 'ON_SALE',
  listPrice: 8000,
};

const stockFixture = {
  skuId: 'SKU-001',
  productCode: 'LIMITED-001',
  availableQuantity: 10,
  reservedQuantity: 2,
  version: 3,
};

const orderFixture: Record<string, OrderDetailFixture[]> = {
  ord_app_001: [
    {
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
  ],
  ord_app_delay_001: [
    {
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
  ],
  ord_app_card_001: [
    {
      orderId: 'ord_app_card_001',
      memberId: 'member-portfolio',
      status: 'CREATED',
      sagaStatus: 'STARTED',
      paymentMethod: 'CARD',
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
    {
      orderId: 'ord_app_card_001',
      memberId: 'member-portfolio',
      status: 'CONFIRMED',
      sagaStatus: 'CONFIRMED',
      paymentMethod: 'CARD',
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
    {
      orderId: 'ord_app_card_001',
      memberId: 'member-portfolio',
      status: 'COMPLETED',
      sagaStatus: 'COMPLETED',
      paymentMethod: 'CARD',
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
  ],
  ord_poll_fail_001: [
    {
      orderId: 'ord_poll_fail_001',
      memberId: 'member-portfolio',
      status: 'CREATED',
      sagaStatus: 'STARTED',
      paymentMethod: 'CARD',
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
  ],
  };

describe('Customer order flow', () => {
  const flushMicrotasks = () => new Promise((resolve) => setTimeout(resolve, 0));

  const getPollCallCount = (orderId: string, calls: ReadonlyArray<Parameters<typeof fetch>>) =>
    calls.filter((call) => String(call[0]) === `/api/orders/${orderId}`).length;

  const triggerPollUntilProgress = async (orderId: string, intervalCallbacks: Array<() => Promise<unknown> | void>, beforeCount: number) => {
    for (const callback of intervalCallbacks) {
      await act(async () => {
        await callback();
      });
      await flushMicrotasks();
      if (getPollCallCount(orderId, fetchMock.mock.calls) > beforeCount) {
        return;
      }
    }

    throw new Error('Polling interval callback did not trigger an order-detail request.');
  };

  const fetchMock = vi.fn<typeof fetch>();
  let quoteMode: 'success' | 'error' = 'success';
  let productsMode: ApiMode = 'success';
  let stocksMode: ApiMode = 'success';
  let createMode: ApiMode = 'success';
  let orderDetailQueues: Record<string, OrderDetailFixture[]> = {};
  let orderDetailLastState: Record<string, OrderDetailFixture> = {};
  let pollFailureOrderIds = new Set<string>();

  beforeEach(() => {
    vi.useRealTimers();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockClear();
    clearAuthSession();
    setAuthSessionForTest(TEST_ACCESS_TOKEN, 3600);
    quoteMode = 'success';
    productsMode = 'success';
    stocksMode = 'success';
    createMode = 'success';
    pollFailureOrderIds = new Set();
    orderDetailQueues = {
      ord_app_001: [...orderFixture.ord_app_001],
      ord_app_delay_001: [...orderFixture.ord_app_delay_001],
      ord_app_card_001: [...orderFixture.ord_app_card_001],
      ord_poll_fail_001: [...orderFixture.ord_poll_fail_001],
    };
    orderDetailLastState = Object.fromEntries(
      Object.entries(orderDetailQueues).map(([orderId, states]) => [orderId, states[states.length - 1]]),
    );

    const takeNextOrderState = (orderId: string) => {
      const remaining = orderDetailQueues[orderId];
      if (remaining && remaining.length > 0) {
        const nextState = remaining.shift() as OrderDetailFixture;
        orderDetailLastState[orderId] = nextState;
        return nextState;
      }

      return orderDetailLastState[orderId];
    };

    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      const method = init?.method ?? 'GET';

      if (url.startsWith('/catalog/api/products?status=ON_SALE')) {
        const parsedUrl = new URL(url, 'http://localhost');
        const query = parsedUrl.searchParams.get('q')?.trim().toLowerCase();
        const matchingProducts = (() => {
          if (!query) {
            return [productFixture, searchProductFixture];
          }

          if (query.includes('hoodie') || query.includes('limited-001') || query.includes('limited hoodie')) {
            return [productFixture];
          }

          if (query.includes('cap') || query.includes('limited-002')) {
            return [searchProductFixture];
          }

          return [];
        })();

        if (productsMode === 'error') {
          return errorResponse('PRODUCT_LIST_FAILED', '상품 목록 조회 실패');
        }

        return jsonResponse({
          success: true,
          data: matchingProducts,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === '/inventory/api/stocks?productCode=LIMITED-001') {
        if (stocksMode === 'error') {
          return errorResponse('STOCK_LIST_FAILED', '재고 조회 실패');
        }

        return jsonResponse({
          success: true,
          data: [stockFixture],
          trace: { correlationId: 'corr-stock' },
        });
      }

      if (url === '/api/orders' && method === 'POST') {
        const requestBody = JSON.parse(String(init?.body)) as {
          couponCode?: string;
          paymentMethod?: string;
          items?: Array<{ quantity: number; unitPrice: number }>;
        };

        if (createMode === 'error') {
          return errorResponse('ORDER_CREATE_FAILED', '주문 생성에 실패했습니다.', 500);
        }

        const paymentMethod = requestBody.paymentMethod ?? 'CARD';
        const orderId = paymentMethod === 'DELAY_CARD' ? 'ord_app_delay_001' : paymentMethod === 'CARD' ? 'ord_app_card_001' : 'ord_app_001';
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

      if (url === '/promotion/api/coupons/quote' && method === 'POST') {
        if (quoteMode === 'error') {
          return errorResponse('PROMOTION_COUPON_NOT_FOUND', '쿠폰 코드를 확인해주세요.', 404);
        }

        const requestBody = JSON.parse(String(init?.body)) as { couponCode: string; orderAmount: number };
        const isWelcome10 = requestBody.couponCode === 'WELCOME10';
        const discountAmount = isWelcome10 ? 5000 : 0;

        return jsonResponse({
          success: true,
          data: {
            couponCode: requestBody.couponCode,
            applied: isWelcome10,
            discountAmount,
            payAmount: requestBody.orderAmount - discountAmount,
            reason: isWelcome10 ? 'APPLIED' : 'INVALID_COUPON',
          },
          trace: { correlationId: 'corr-promotion-quote' },
        });
      }

      if (url.startsWith('/api/orders/')) {
        const orderId = url.substring('/api/orders/'.length);
        if (pollFailureOrderIds.has(orderId)) {
          return errorResponse('ORDER_STATUS_FAILED', '주문 상태 조회에 실패했습니다.', 503);
        }

        const next = takeNextOrderState(orderId);
        if (!next) {
          return errorResponse('ORDER_NOT_FOUND', '주문 정보를 찾을 수 없습니다.', 404);
        }
        return jsonResponse({
          success: true,
          data: next,
          trace: { correlationId: `corr-order-${orderId}` },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });
  });

  afterEach(() => {
    vi.useRealTimers();
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

    const orderRequest = fetchMock.mock.calls.find(
      ([url, init]) => url === '/api/orders' && init?.method === 'POST',
    );
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
      expect(fetchMock).toHaveBeenCalledWith('/api/orders/ord_app_001', expect.objectContaining({ method: 'GET' }));
    });
  });

  it('follows CARD polling to CONFIRMED then COMPLETED and stops at terminal', async () => {
    const getStatusValueByLabel = (label: string) => {
      const statusPanel = screen.getByRole('region', { name: '주문 상태' });
      const labelNode = within(statusPanel).getByText(label);
      return labelNode.closest('div')?.querySelector('strong')?.textContent;
    };

    const user = userEvent.setup();
    const intervalCallbacks: Array<() => Promise<unknown> | void> = [];
    const intervalSpy = vi.spyOn(globalThis, 'setInterval').mockImplementation(((handler) => {
      const callback = typeof handler === 'function' ? handler : () => undefined;
      intervalCallbacks.push(() => {
        return Promise.resolve(callback());
      });
      return 1 as unknown as number;
    }) as typeof globalThis.setInterval);

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));
    await waitFor(() => expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(1));
    expect(intervalCallbacks.length).toBeGreaterThan(0);

    await screen.findByText('ord_app_card_001');
    await waitFor(() => expect(getStatusValueByLabel('주문 상태')).toBe('CREATED'));
    await waitFor(() => expect(getStatusValueByLabel('Saga 상태')).toBe('STARTED'));

    const pollCallsBeforeFirstRefresh = getPollCallCount('ord_app_card_001', fetchMock.mock.calls);
    await triggerPollUntilProgress('ord_app_card_001', intervalCallbacks, pollCallsBeforeFirstRefresh);
    await waitFor(() => expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(2));
    await waitFor(() => expect(getStatusValueByLabel('주문 상태')).toBe('CONFIRMED'));
    await waitFor(() => expect(getStatusValueByLabel('Saga 상태')).toBe('CONFIRMED'));

    const pollCallsBeforeSecondRefresh = getPollCallCount('ord_app_card_001', fetchMock.mock.calls);
    await triggerPollUntilProgress('ord_app_card_001', intervalCallbacks, pollCallsBeforeSecondRefresh);
    await waitFor(() => expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(3));
    await waitFor(() => expect(getStatusValueByLabel('주문 상태')).toBe('COMPLETED'));
    await waitFor(() => expect(getStatusValueByLabel('Saga 상태')).toBe('COMPLETED'));

    const pollCallsAtTerminal = getPollCallCount('ord_app_card_001', fetchMock.mock.calls);
    await flushMicrotasks();
    expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(pollCallsAtTerminal);

    intervalSpy.mockRestore();
    const pollCalls = getPollCallCount('ord_app_card_001', fetchMock.mock.calls);
    expect(pollCalls).toBe(3);
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
        url === '/api/orders' &&
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
        '/api/orders/ord_app_delay_001',
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
      ([url, init]) => url === '/api/orders' && init?.method === 'POST',
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

  it('shows inline error when coupon quote fails and does not allow order creation', async () => {
    const user = userEvent.setup();
    quoteMode = 'error';

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('쿠폰 코드'), 'BAD01');
    await user.click(screen.getByRole('button', { name: '쿠폰 적용' }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '쿠폰 적용 실패: PROMOTION_COUPON_NOT_FOUND: 쿠폰 코드를 확인해주세요.',
    );
    expect(screen.getByText('할인 금액')).toBeInTheDocument();
    expect(screen.getByText('₩0')).toBeInTheDocument();
    expect(screen.getAllByText('₩12,000').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/orders' && init?.method === 'POST')).toBe(false);
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
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/orders' && init?.method === 'POST')).toBe(false);
  });

  it('initial product list load failure shows alert and blocks checkout', async () => {
    const user = userEvent.setup();
    productsMode = 'error';

    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('PRODUCT_LIST_FAILED: 상품 목록 조회 실패');
    expect(screen.queryByRole('button', { name: '주문 생성' })).not.toBeInTheDocument();
  });

  it('clears product list error after a successful search retry', async () => {
    const user = userEvent.setup();
    productsMode = 'error';

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent('PRODUCT_LIST_FAILED: 상품 목록 조회 실패');

    productsMode = 'success';
    await user.type(screen.getByLabelText('상품 검색'), 'cap');

    await screen.findByRole('button', { name: /Limited Cap/ });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('searches catalog products by query while preserving existing list flow', async () => {
    const user = userEvent.setup();

    render(<App />);

    await screen.findByRole('button', { name: /Limited Hoodie/ });
    const queryInput = screen.getByLabelText('상품 검색');

    await user.clear(queryInput);
    await user.type(queryInput, ' cap ');

    await screen.findByRole('button', { name: /Limited Cap/ });
    expect(screen.queryByRole('button', { name: /Limited Hoodie/ })).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      '/catalog/api/products?status=ON_SALE&q=cap',
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('returns full catalog when query is only whitespace', async () => {
    const user = userEvent.setup();

    render(<App />);

    await screen.findByRole('button', { name: /Limited Hoodie/ });
    const queryInput = screen.getByLabelText('상품 검색');

    await user.type(queryInput, 'cap');
    expect(await screen.findByRole('button', { name: /Limited Cap/ })).toBeInTheDocument();

    await user.clear(queryInput);
    await user.type(queryInput, '   ');

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Limited Hoodie/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Limited Cap/ })).toBeInTheDocument();
    });

    const catalogCalls = fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/catalog/api/products'));
    const latestCatalogCall = catalogCalls[catalogCalls.length - 1];
    expect(latestCatalogCall?.[0]).toBe('/catalog/api/products?status=ON_SALE');
  });

  it('stock load failure after product selection shows alert and disables order creation', async () => {
    const user = userEvent.setup();
    stocksMode = 'error';

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('STOCK_LIST_FAILED: 재고 조회 실패');
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
  });

  it('order creation API failure shows alert and clears visible order status', async () => {
    const user = userEvent.setup();

    render(<App />);
    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.selectOptions(screen.getByLabelText('결제수단'), 'FAIL_CARD');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    await screen.findByText('ord_app_001');
    expect(screen.getByText('FAILED')).toBeInTheDocument();

    createMode = 'error';
    await user.selectOptions(screen.getByLabelText('결제수단'), 'CARD');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('ORDER_CREATE_FAILED: 주문 생성에 실패했습니다.');
    expect(screen.queryByText('ord_app_001')).not.toBeInTheDocument();
  });

  it('status polling repeated failures stop after configured threshold with stop message', async () => {
    const user = userEvent.setup();
    pollFailureOrderIds.add('ord_app_card_001');
    const intervalCallbacks: Array<() => Promise<unknown> | void> = [];
    const intervalSpy = vi.spyOn(globalThis, 'setInterval').mockImplementation(((handler) => {
      const callback = typeof handler === 'function' ? handler : () => undefined;
      intervalCallbacks.push(() => {
        return Promise.resolve(callback());
      });
      return 1 as unknown as number;
    }) as typeof globalThis.setInterval);
    const clearIntervalSpy = vi
      .spyOn(globalThis, 'clearInterval')
      .mockImplementation(() => undefined as unknown as void);

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    await screen.findByText('ord_app_card_001');
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/orders/ord_app_card_001', expect.objectContaining({ method: 'GET' }));
    });

    await triggerPollUntilProgress('ord_app_card_001', intervalCallbacks, 1);
    await waitFor(() => expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(2));
    await triggerPollUntilProgress('ord_app_card_001', intervalCallbacks, 2);
    await waitFor(() => expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(3));
    await waitFor(() => expect(clearIntervalSpy).toHaveBeenCalled());
    const pollCallsAtStop = getPollCallCount('ord_app_card_001', fetchMock.mock.calls);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /상태 조회가 반복 실패해 자동 조회를 중단했습니다\./,
    );
    expect(pollCallsAtStop).toBe(3);
    await flushMicrotasks();
    expect(getPollCallCount('ord_app_card_001', fetchMock.mock.calls)).toBe(pollCallsAtStop);
    intervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();

    expect(
      getPollCallCount('ord_app_card_001', fetchMock.mock.calls),
    ).toBe(pollCallsAtStop);
  });

  it('clears applied coupon when quantity changes and keeps order button enabled when valid', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('쿠폰 코드'), 'WELCOME10');
    await user.click(screen.getByRole('button', { name: '쿠폰 적용' }));
    await screen.findByText('쿠폰 적용: APPLIED');
    expect(screen.getByText('₩5,000')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();

    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.clear(screen.getByLabelText('수량'));
    await user.type(screen.getByLabelText('수량'), '2');

    expect(screen.queryByText('쿠폰 적용: APPLIED')).not.toBeInTheDocument();
    expect(screen.getByText('₩0')).toBeInTheDocument();
    const payableRow = screen.getByText('결제 예정 금액').parentElement;
    expect(payableRow).toHaveTextContent('₩24,000');
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeEnabled();
  });

  it('shows 인증 상태 and blocks checkout without an access token', async () => {
    const user = userEvent.setup();
    clearAuthSession();

    render(<App />);

    expect(screen.getByText('인증 상태: 미인증')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();
    await user.type(screen.getByLabelText('수량'), '1');
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');

    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
    expect(getAuthSession()).toBeNull();
  });

  it('attaches access token to protected order APIs', async () => {
    const user = userEvent.setup();

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    expect(await screen.findByText('SKU-001')).toBeInTheDocument();
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');

    await user.click(screen.getByRole('button', { name: '주문 생성' }));

    await screen.findByText('ord_app_card_001');
    const createCall = fetchMock.mock.calls.find(
      ([url, init]) => url === '/api/orders' && init?.method === 'POST',
    );
    expect(createCall).toBeDefined();
    expect(getAuthorizationHeaderValue(createCall?.[1]?.headers)).toBe(`Bearer ${TEST_ACCESS_TOKEN}`);

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(([url, init]) =>
          url === '/api/orders/ord_app_card_001' &&
          init?.method === 'GET' &&
          getAuthorizationHeaderValue(init?.headers) === `Bearer ${TEST_ACCESS_TOKEN}`,
        ),
      ).toBe(true);
    });

    const catalogCall = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/catalog/api/products'));
    expect(getAuthorizationHeaderValue(catalogCall?.[1]?.headers)).toBeNull();
    const stockCall = fetchMock.mock.calls.find(([url]) => String(url) === '/inventory/api/stocks?productCode=LIMITED-001');
    expect(getAuthorizationHeaderValue(stockCall?.[1]?.headers)).toBeNull();
  });

  it('clears authentication state after logout', async () => {
    const user = userEvent.setup();

    render(<App />);

    expect(screen.getByText('인증 상태: 인증됨')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument();
    expect(getAuthSession()).not.toBeNull();

    await user.click(await screen.findByRole('button', { name: /Limited Hoodie/ }));
    await user.type(screen.getByLabelText('회원 ID'), 'member-portfolio');
    await user.click(screen.getByRole('button', { name: '로그아웃' }));

    expect(screen.getByText('인증 상태: 미인증')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
    expect(getAuthSession()).toBeNull();
    expect(screen.getByRole('button', { name: '주문 생성' })).toBeDisabled();
  });
});
