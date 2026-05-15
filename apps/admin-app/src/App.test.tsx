import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { AUTH_ACCESS_TOKEN_STORAGE_KEY } from './auth';

const toJsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

const buildResponse = (ok: boolean, data: unknown, status = 200) => ({
  success: ok,
  data,
  error: null,
  trace: { correlationId: 'corr-admin-test' },
});

const buildErrorResponse = (code: string, message: string, status = 500) =>
  Promise.resolve(
    new Response(
      JSON.stringify({
        success: false,
        data: null,
        error: { code, message, details: {} },
        trace: { correlationId: 'corr-admin-test' },
      }),
      { status, headers: { 'Content-Type': 'application/json' } },
    ),
  );

function headerValue(headers: HeadersInit | undefined, name: string): string {
  if (!headers) {
    return '';
  }

  const normalized = headers instanceof Headers ? headers : new Headers(headers);
  return normalized.get(name) ?? '';
}

function isAdminApiCall(input: RequestInfo | URL): boolean {
  const request = new URL(String(input), 'http://localhost:5173');
  const pathname = request.pathname;
  return pathname.startsWith('/orders') || pathname.startsWith('/api') || pathname.startsWith('/inventory') || pathname.startsWith('/catalog');
}

const TEST_ACCESS_TOKEN = 'test-admin-token';

function createMockStorage(): Storage {
  const items = new Map<string, string>();

  return {
    clear() {
      items.clear();
    },
    getItem(key: string) {
      return items.get(key) ?? null;
    },
    key(index: number) {
      return [...items.keys()][index] ?? null;
    },
    removeItem(key: string) {
      items.delete(key);
    },
    setItem(key: string, value: string) {
      items.set(key, value);
    },
    get length() {
      return items.size;
    },
    get [Symbol.toStringTag]() {
      return 'Storage';
    },
  } as Storage;
}

function ensureMockStorage() {
  const storage = createMockStorage();

  Object.defineProperty(window, 'localStorage', {
    value: storage,
    configurable: true,
    writable: true,
  });

  Object.defineProperty(globalThis, 'localStorage', {
    value: storage,
    configurable: true,
    writable: true,
  });
}

function setAuthenticatedState() {
  localStorage.setItem(AUTH_ACCESS_TOKEN_STORAGE_KEY, TEST_ACCESS_TOKEN);
}

function clearAuthenticatedState() {
  localStorage.removeItem(AUTH_ACCESS_TOKEN_STORAGE_KEY);
}

describe('admin app operations', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let defaultRequestHandler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

  beforeEach(() => {
    ensureMockStorage();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockClear();
    setAuthenticatedState();

    const catalogProducts = [
      {
        productCode: 'LAPTOP-001',
        name: 'lightbook',
        status: 'ON_SALE',
        listPrice: 1200000,
      },
    ];

    const stocks = [
      {
        skuId: 'SKU-001',
        productCode: 'LAPTOP-001',
        availableQuantity: 12,
        reservedQuantity: 2,
        version: 1,
      },
    ];

    const stockBySku = new Map(stocks.map((stock) => [stock.skuId, { ...stock }]));

    defaultRequestHandler = (input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';
      const requestBody = init?.body ? JSON.parse(String(init.body)) : null;

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_001',
                memberId: 'member-a',
                status: 'CREATED',
                sagaStatus: 'STARTED',
                paymentMethod: 'CARD',
                totalAmount: 12000,
                itemCount: 1,
                createdAt: '2026-05-13T00:00:00Z',
                updatedAt: '2026-05-13T00:00:10Z',
              },
              {
                orderId: 'ord_admin_002',
                memberId: 'member-b',
                status: 'CANCELLED',
                sagaStatus: 'FAILED',
                paymentMethod: 'FAIL_CARD',
                totalAmount: 8800,
                itemCount: 2,
                createdAt: '2026-05-13T00:02:00Z',
                updatedAt: '2026-05-13T00:03:00Z',
              },
            ],
          }),
          200,
        );
      }

      if (
        request.pathname.startsWith('/api/admin/orders/') &&
        request.pathname.endsWith('/saga') &&
        method === 'GET'
      ) {
        const pathParts = request.pathname.split('/');
        const selectedOrderId = pathParts[pathParts.length - 2] ?? '';

        return toJsonResponse(
          buildResponse(true, {
            orderId: selectedOrderId,
            orderStatus: 'CANCELLED',
            sagaStatus: 'FAILED',
            failedAt: '2026-05-13T00:03:10Z',
            businessReason: 'PAYMENT_DECLINED',
            technicalErrorMessage: 'kafka unavailable',
            lastEventType: 'OrderCancelled',
            outboxAttempts: 4,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        return toJsonResponse(buildResponse(true, { limit: 50, offset: 0, items: [] }), 200);
      }

      if (request.pathname === '/api/read-model/admin/orders' && method === 'GET') {
        const orders = [
          {
            orderId: 'ord_read_admin_001',
            memberId: 'member-a',
            status: 'CONFIRMED',
            sagaStatus: 'COMPLETED',
            couponCode: 'WELCOME10',
            totalAmount: 12000,
            discountAmount: 1000,
            payableAmount: 11000,
            itemCount: 1,
            cancellationReason: null,
            createdAt: '2026-05-13T00:00:00Z',
            updatedAt: '2026-05-13T00:02:00Z',
          },
          {
            orderId: 'ord_read_admin_002',
            memberId: 'member-b',
            status: 'CANCELLED',
            sagaStatus: 'FAILED',
            couponCode: null,
            totalAmount: 8800,
            discountAmount: 0,
            payableAmount: 8800,
            itemCount: 2,
            cancellationReason: 'PAYMENT_DECLINED',
            createdAt: '2026-05-13T00:03:00Z',
            updatedAt: '2026-05-13T00:04:00Z',
          },
          {
            orderId: 'ord_read_admin_003',
            memberId: 'member-c',
            status: 'CREATED',
            sagaStatus: 'PAYMENT_DELAYED',
            couponCode: null,
            totalAmount: 5000,
            discountAmount: 0,
            payableAmount: 5000,
            itemCount: 1,
            cancellationReason: null,
            createdAt: '2026-05-13T00:05:00Z',
            updatedAt: '2026-05-13T00:06:00Z',
          },
        ].filter((order) => {
          const orderId = request.searchParams.get('orderId');
          const memberId = request.searchParams.get('memberId');
          const status = request.searchParams.get('status');
          const sagaStatus = request.searchParams.get('sagaStatus');
          const couponCode = request.searchParams.get('couponCode');
          return (
            (!orderId || order.orderId === orderId) &&
            (!memberId || order.memberId === memberId) &&
            (!status || order.status === status) &&
            (!sagaStatus || order.sagaStatus === sagaStatus) &&
            (!couponCode || order.couponCode === couponCode)
          );
        });

        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 50,
            items: orders,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/coupon-usages' && method === 'GET') {
        const usages = [
          {
            orderId: 'ord_coupon_usage_001',
            memberId: 'member-a',
            couponCode: 'WELCOME10',
            status: 'CONSUMED',
            orderAmount: 80000,
            discountAmount: 5000,
            payableAmount: 75000,
            reservedAt: '2026-05-13T04:30:00Z',
            consumedAt: '2026-05-13T04:31:00Z',
            releasedAt: null,
            releaseReason: null,
            updatedAt: '2026-05-13T04:31:00Z',
          },
          {
            orderId: 'ord_coupon_usage_002',
            memberId: 'member-b',
            couponCode: 'WELCOME10',
            status: 'RELEASED',
            orderAmount: 40000,
            discountAmount: 4000,
            payableAmount: 36000,
            reservedAt: '2026-05-13T04:32:00Z',
            consumedAt: null,
            releasedAt: '2026-05-13T04:33:00Z',
            releaseReason: 'PAYMENT_DECLINED',
            updatedAt: '2026-05-13T04:33:00Z',
          },
        ].filter((usage) => {
          const couponCode = request.searchParams.get('couponCode');
          const status = request.searchParams.get('status');
          return (!couponCode || usage.couponCode === couponCode) && (!status || usage.status === status);
        });

        return toJsonResponse(buildResponse(true, { page: 0, size: 50, items: usages }), 200);
      }

      if (request.pathname === '/api/admin/fulfillment-requests' && method === 'GET') {
        const requests = [
          {
            requestId: '018f8d0b-8d32-7c42-9f1b-78328e0f801',
            orderId: 'ord_fulfillment_usage_001',
            status: 'PREPARING',
            requestedAt: '2026-05-13T08:10:00Z',
            sourceEventId: '018f8d0b-8d32-7c42-9f1b-78328e0f7a1',
            correlationId: 'corr-fulfillment-admin-001',
            idempotencyKey: 'idem-fulfillment-admin-001',
            createdAt: '2026-05-13T08:10:00Z',
            updatedAt: '2026-05-13T08:10:00Z',
          },
          {
            requestId: '018f8d0b-8d32-7c42-9f1b-78328e0f802',
            orderId: 'ord_fulfillment_usage_002',
            status: 'PREPARING',
            requestedAt: '2026-05-13T08:12:00Z',
            sourceEventId: '018f8d0b-8d32-7c42-9f1b-78328e0f7a2',
            correlationId: 'corr-fulfillment-admin-002',
            idempotencyKey: 'idem-fulfillment-admin-002',
            createdAt: '2026-05-13T08:12:00Z',
            updatedAt: '2026-05-13T08:12:00Z',
          },
        ].filter((item) => {
          const orderId = request.searchParams.get('orderId');
          const status = request.searchParams.get('status');
          return (!orderId || item.orderId === orderId) && (!status || item.status === status);
        });

        return toJsonResponse(buildResponse(true, { page: 0, size: 50, items: requests }), 200);
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-inv-001',
                aggregateType: 'inventory',
                aggregateId: 'ord_admin_001',
                eventType: 'InventoryReserved',
                status: 'FAILED',
                retryCount: 2,
                maxRetryCount: 3,
                nextRetryAt: null,
                errorMessage: 'temporary network issue',
                createdAt: '2026-05-13T00:04:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events/retry' && method === 'POST') {
        expect(headerValue(init?.headers, 'X-Operator-Id')).toBe('');
        return toJsonResponse(buildResponse(true, { claimed: 2, published: 1, failed: 0 }), 200);
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events/failed/requeue' && method === 'POST') {
        expect(headerValue(init?.headers, 'X-Operator-Id')).toBe('');
        return toJsonResponse(buildResponse(true, { updated: 1 }), 200);
      }

      if (request.pathname === '/api/admin/products' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, catalogProducts.filter((product) => request.searchParams.get('status') === product.status)),
          200,
        );
      }

      if (request.pathname === '/api/admin/products' && method === 'POST') {
        const key = headerValue(init?.headers, 'Idempotency-Key');
        expect(key).not.toBe('');
        const payload = requestBody;
        expect(payload.productCode).toBeDefined();
        catalogProducts.push({
          productCode: payload.productCode,
          name: payload.name,
          status: payload.salesStatus,
          listPrice: payload.listPrice,
        });
        return toJsonResponse(
          buildResponse(true, {
            productCode: payload.productCode,
            name: payload.name,
            status: payload.salesStatus,
            listPrice: payload.listPrice,
          }),
          201,
        );
      }

      if (request.pathname === '/api/admin/products/LAPTOP-001' && method === 'PUT') {
        const key = headerValue(init?.headers, 'Idempotency-Key');
        expect(key).not.toBe('');
        const payload = requestBody;
        const index = catalogProducts.findIndex((item) => item.productCode === 'LAPTOP-001');
        if (index >= 0) {
          catalogProducts[index] = {
            ...catalogProducts[index],
            name: payload.name,
            status: payload.salesStatus,
            listPrice: payload.listPrice,
          };
        }

        return toJsonResponse(
          buildResponse(true, {
            productCode: 'LAPTOP-001',
            name: payload.name,
            status: payload.salesStatus,
            listPrice: payload.listPrice,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/stocks' && method === 'GET') {
        const productCode = request.searchParams.get('productCode');
        return toJsonResponse(
          buildResponse(
            true,
            stocks.filter((stock) => !productCode || stock.productCode === productCode),
          ),
          200,
        );
      }

      if (request.pathname.startsWith('/api/admin/stocks/') && method === 'PUT') {
        const pathSkuId = request.pathname.replace('/api/admin/stocks/', '');
        const payload = requestBody as { productCode: string; availableQuantity: number };
        const previous = stockBySku.get(pathSkuId);
        const updated = {
          skuId: pathSkuId,
          productCode: payload.productCode,
          availableQuantity: payload.availableQuantity,
          reservedQuantity: previous?.reservedQuantity ?? 0,
          version: (previous?.version ?? 0) + 1,
        };

        stockBySku.set(pathSkuId, updated);
        const index = stocks.findIndex((stock) => stock.skuId === pathSkuId);
        if (index >= 0) {
          stocks[index] = updated;
        } else {
          stocks.push(updated);
        }

        return toJsonResponse(buildResponse(true, updated), 200);
      }

      throw new Error(`Unexpected request: ${String(input)}`);
    };

    fetchMock.mockImplementation((input, init) => defaultRequestHandler(input, init));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    clearAuthenticatedState();
  });

  it('loads orders and fetches selected order saga', async () => {
    const user = userEvent.setup();

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /ord_admin_002/ }));

    await waitFor(() => {
      const sagaRequests = fetchMock.mock.calls.filter(([url]) =>
        String(url).includes('/api/admin/orders/ord_admin_002/saga'),
      );
      expect(sagaRequests.length).toBeGreaterThanOrEqual(1);
    });

    expect(await screen.findByText('PAYMENT_DECLINED')).toBeInTheDocument();
    expect(screen.getByText('OrderCancelled')).toBeInTheDocument();
  });

  it('loads dashboard metrics from read model summaries', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Dashboard' }));

    expect(await screen.findByText('주문 대시보드')).toBeInTheDocument();
    expect(screen.getByText('조회 주문')).toBeInTheDocument();
    expect(screen.getByText('3건')).toBeInTheDocument();
    expect(screen.getByText('확정 주문')).toBeInTheDocument();
    expect(screen.getByText('취소 주문')).toBeInTheDocument();
    expect(screen.getByText('지연 결제')).toBeInTheDocument();
    expect(screen.getByText('쿠폰 사용')).toBeInTheDocument();
    expect(screen.getByText('₩24,800')).toBeInTheDocument();
    expect(screen.getByText('₩1,000')).toBeInTheDocument();
    expect(screen.getByText('ord_read_admin_003')).toBeInTheDocument();
    expect(screen.getByText('PAYMENT_DECLINED')).toBeInTheDocument();
  });

  it('filters dashboard read model summaries through gateway', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Dashboard' }));
    expect(await screen.findByText('주문 대시보드')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Read Model 주문 ID'), 'ord_read_admin_001');
    await user.type(screen.getByLabelText('Read Model 회원 ID'), 'member-a');
    await user.selectOptions(screen.getByLabelText('Read Model 주문 상태'), 'CONFIRMED');
    await user.selectOptions(screen.getByLabelText('Read Model Saga 상태'), 'COMPLETED');
    await user.type(screen.getByLabelText('Read Model 쿠폰 코드'), 'WELCOME10');
    await user.click(screen.getByRole('button', { name: '대시보드 조회' }));

    await waitFor(() => {
      const filteredCalls = fetchMock.mock.calls.filter(([url]) => {
        const request = new URL(String(url), 'http://localhost:5173');
        return (
          request.pathname === '/api/read-model/admin/orders' &&
          request.searchParams.get('orderId') === 'ord_read_admin_001' &&
          request.searchParams.get('memberId') === 'member-a' &&
          request.searchParams.get('status') === 'CONFIRMED' &&
          request.searchParams.get('sagaStatus') === 'COMPLETED' &&
          request.searchParams.get('couponCode') === 'WELCOME10'
        );
      });
      expect(filteredCalls.length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.getByText('ord_read_admin_001')).toBeInTheDocument();
    expect(screen.queryByText('ord_read_admin_002')).not.toBeInTheDocument();
    expect(screen.queryByText('ord_read_admin_003')).not.toBeInTheDocument();
    expect(screen.getAllByText('1건').length).toBeGreaterThanOrEqual(1);
  });

  it('shows dashboard error when read model request fails', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/read-model/admin/orders' && method === 'GET') {
        return buildErrorResponse('READ_MODEL_FAIL', '대시보드 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Dashboard' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('READ_MODEL_FAIL: 대시보드 조회 실패');
    expect(screen.getByText('대시보드 데이터를 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('loads coupon usage history and applies filters through gateway', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Coupons' }));

    expect(await screen.findByText('쿠폰 사용 이력')).toBeInTheDocument();
    expect(screen.getByText('ord_coupon_usage_001')).toBeInTheDocument();
    expect(screen.getByText('ord_coupon_usage_002')).toBeInTheDocument();
    expect(screen.getByText('₩5,000')).toBeInTheDocument();
    expect(screen.getByText('PAYMENT_DECLINED')).toBeInTheDocument();

    await user.type(screen.getByLabelText('쿠폰 코드'), 'WELCOME10');
    await user.selectOptions(screen.getByLabelText('사용 상태'), 'CONSUMED');
    await user.click(screen.getByRole('button', { name: '쿠폰 이력 조회' }));

    await waitFor(() => {
      const filteredCalls = fetchMock.mock.calls.filter(([url]) => {
        const request = new URL(String(url), 'http://localhost:5173');
        return (
          request.pathname === '/api/admin/coupon-usages' &&
          request.searchParams.get('couponCode') === 'WELCOME10' &&
          request.searchParams.get('status') === 'CONSUMED'
        );
      });
      expect(filteredCalls.length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.getByText('ord_coupon_usage_001')).toBeInTheDocument();
    expect(screen.queryByText('ord_coupon_usage_002')).not.toBeInTheDocument();
  });

  it('shows coupon usage error when history request fails', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/coupon-usages' && method === 'GET') {
        return buildErrorResponse('COUPON_USAGE_FAIL', '쿠폰 사용 이력 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Coupons' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('COUPON_USAGE_FAIL: 쿠폰 사용 이력 조회 실패');
    expect(screen.getByText('쿠폰 사용 이력을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('loads fulfillment request history and applies filters through gateway', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Fulfillment' }));

    expect(await screen.findByText('출고 요청 이력')).toBeInTheDocument();
    expect(screen.getByText('ord_fulfillment_usage_001')).toBeInTheDocument();
    expect(screen.getByText('ord_fulfillment_usage_002')).toBeInTheDocument();
    expect(screen.getAllByText('PREPARING').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('corr-fulfillment-admin-001')).toBeInTheDocument();

    await user.type(screen.getByLabelText('주문 ID'), 'ord_fulfillment_usage_002');
    await user.selectOptions(screen.getByLabelText('출고 상태'), 'PREPARING');
    await user.click(screen.getByRole('button', { name: '출고 이력 조회' }));

    await waitFor(() => {
      const filteredCalls = fetchMock.mock.calls.filter(([url]) => {
        const request = new URL(String(url), 'http://localhost:5173');
        return (
          request.pathname === '/api/admin/fulfillment-requests' &&
          request.searchParams.get('orderId') === 'ord_fulfillment_usage_002' &&
          request.searchParams.get('status') === 'PREPARING'
        );
      });
      expect(filteredCalls.length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.getByText('ord_fulfillment_usage_002')).toBeInTheDocument();
    expect(screen.queryByText('ord_fulfillment_usage_001')).not.toBeInTheDocument();
  });

  it('shows fulfillment request error when history request fails', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/fulfillment-requests' && method === 'GET') {
        return buildErrorResponse('FULFILLMENT_REQUEST_FAIL', '출고 요청 이력 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Fulfillment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('FULFILLMENT_REQUEST_FAIL: 출고 요청 이력 조회 실패');
    expect(screen.getByText('출고 요청 이력을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('keeps fulfillment request list from the latest request when responses return out of order', async () => {
    const user = userEvent.setup();
    const initialRequestResolvers: Array<(response: Response) => void> = [];

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/fulfillment-requests' && method === 'GET') {
        if (!request.searchParams.get('orderId')) {
          return new Promise<Response>((resolve) => {
            initialRequestResolvers.push(resolve);
          });
        }

        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 50,
            items: [
              {
                requestId: '018f8d0b-8d32-7c42-9f1b-78328e0f901',
                orderId: 'ord_fulfillment_latest',
                status: 'PREPARING',
                requestedAt: '2026-05-13T08:20:00Z',
                sourceEventId: '018f8d0b-8d32-7c42-9f1b-78328e0f9a1',
                correlationId: 'corr-fulfillment-latest',
                idempotencyKey: 'idem-fulfillment-latest',
                createdAt: '2026-05-13T08:20:00Z',
                updatedAt: '2026-05-13T08:20:00Z',
              },
            ],
          }),
          200,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Fulfillment' }));
    await screen.findByText('출고 요청 이력');

    await user.type(screen.getByLabelText('주문 ID'), 'ord_fulfillment_latest');
    await user.click(screen.getByRole('button', { name: '출고 이력 조회' }));

    expect(await screen.findByText('ord_fulfillment_latest')).toBeInTheDocument();
    expect(initialRequestResolvers).toHaveLength(1);
    initialRequestResolvers[0](
      new Response(
        JSON.stringify(
          buildResponse(true, {
            page: 0,
            size: 50,
            items: [
              {
                requestId: '018f8d0b-8d32-7c42-9f1b-78328e0f902',
                orderId: 'ord_fulfillment_stale',
                status: 'PREPARING',
                requestedAt: '2026-05-13T08:22:00Z',
                sourceEventId: '018f8d0b-8d32-7c42-9f1b-78328e0f9a2',
                correlationId: 'corr-fulfillment-stale',
                idempotencyKey: 'idem-fulfillment-stale',
                createdAt: '2026-05-13T08:22:00Z',
                updatedAt: '2026-05-13T08:22:00Z',
              },
            ],
          }),
        ),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    await waitFor(() => {
      expect(screen.getByText('ord_fulfillment_latest')).toBeInTheDocument();
      expect(screen.queryByText('ord_fulfillment_stale')).not.toBeInTheDocument();
    });
  });

  it('keeps coupon usage list from the latest request when responses return out of order', async () => {
    const user = userEvent.setup();
    const initialRequestResolvers: Array<(response: Response) => void> = [];

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/coupon-usages' && method === 'GET') {
        if (!request.searchParams.get('status')) {
          return new Promise<Response>((resolve) => {
            initialRequestResolvers.push(resolve);
          });
        }

        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 50,
            items: [
              {
                orderId: 'ord_coupon_latest',
                memberId: 'member-latest',
                couponCode: 'WELCOME10',
                status: 'CONSUMED',
                orderAmount: 80000,
                discountAmount: 5000,
                payableAmount: 75000,
                reservedAt: '2026-05-13T04:30:00Z',
                consumedAt: '2026-05-13T04:31:00Z',
                releasedAt: null,
                releaseReason: null,
                updatedAt: '2026-05-13T04:31:00Z',
              },
            ],
          }),
          200,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Coupons' }));
    await screen.findByText('쿠폰 사용 이력');

    await user.selectOptions(screen.getByLabelText('사용 상태'), 'CONSUMED');
    await user.click(screen.getByRole('button', { name: '쿠폰 이력 조회' }));

    expect(await screen.findByText('ord_coupon_latest')).toBeInTheDocument();
    expect(initialRequestResolvers).toHaveLength(1);
    initialRequestResolvers[0](
      new Response(
        JSON.stringify(
          buildResponse(true, {
            page: 0,
            size: 50,
            items: [
              {
                orderId: 'ord_coupon_late_stale',
                memberId: 'member-late',
                couponCode: 'WELCOME10',
                status: 'RELEASED',
                orderAmount: 40000,
                discountAmount: 4000,
                payableAmount: 36000,
                reservedAt: '2026-05-13T04:32:00Z',
                consumedAt: null,
                releasedAt: '2026-05-13T04:33:00Z',
                releaseReason: 'PAYMENT_DECLINED',
                updatedAt: '2026-05-13T04:33:00Z',
              },
            ],
          }),
        ),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    await waitFor(() => {
      expect(screen.getByText('ord_coupon_latest')).toBeInTheDocument();
      expect(screen.queryByText('ord_coupon_late_stale')).not.toBeInTheDocument();
    });
  });

  it('requests cancel for a payment delayed order', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_delay_001',
                memberId: 'member-delay',
                status: 'CREATED',
                sagaStatus: 'PAYMENT_DELAYED',
                paymentMethod: 'DELAY_CARD',
                totalAmount: 24000,
                itemCount: 1,
                createdAt: '2026-05-13T00:10:00Z',
                updatedAt: '2026-05-13T00:11:00Z',
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_001/saga' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_001',
            orderStatus: 'CREATED',
            sagaStatus: 'PAYMENT_DELAYED',
            failedAt: null,
            businessReason: 'PAYMENT_DELAYED',
            technicalErrorMessage: null,
            lastEventType: 'PaymentAuthorizationDelayed',
            outboxAttempts: 0,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_001/cancel' && method === 'POST') {
        expect(headerValue(init?.headers, 'Idempotency-Key')).toMatch(/^admin-order-cancel-/);
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_001',
            status: 'CREATED',
            sagaStatus: 'PAYMENT_CANCEL_REQUESTED',
          }),
          202,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_delay_001')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '결제 취소 요청' }));

    await waitFor(() => {
      expect(screen.getByText('ord_admin_delay_001 결제 취소 요청이 접수되었습니다.')).toBeInTheDocument();
    });

    expect(screen.getAllByText('PAYMENT_CANCEL_REQUESTED').length).toBeGreaterThanOrEqual(1);
    const cancelCalls = fetchMock.mock.calls.filter(
      ([url, init]) =>
        String(url).includes('/api/admin/orders/ord_admin_delay_001/cancel') &&
        (init?.method ?? 'GET') === 'POST',
    );
    expect(cancelCalls.length).toBe(1);
  });

  it('reuses cancel idempotency key when delayed order cancel is retried', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const idempotencyKeys: string[] = [];
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_delay_retry_001',
                memberId: 'member-delay',
                status: 'CREATED',
                sagaStatus: 'PAYMENT_DELAYED',
                paymentMethod: 'DELAY_CARD',
                totalAmount: 24000,
                itemCount: 1,
                createdAt: '2026-05-13T00:10:00Z',
                updatedAt: '2026-05-13T00:11:00Z',
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_retry_001/saga' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_retry_001',
            orderStatus: 'CREATED',
            sagaStatus: 'PAYMENT_DELAYED',
            failedAt: null,
            businessReason: 'PAYMENT_DELAYED',
            technicalErrorMessage: null,
            lastEventType: 'PaymentAuthorizationDelayed',
            outboxAttempts: 0,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_retry_001/cancel' && method === 'POST') {
        attempt += 1;
        idempotencyKeys.push(headerValue(init?.headers, 'Idempotency-Key'));
        if (attempt === 1) {
          return buildErrorResponse('NETWORK', '잠시 후 다시 시도하세요');
        }

        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_retry_001',
            status: 'CREATED',
            sagaStatus: 'PAYMENT_CANCEL_REQUESTED',
          }),
          202,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_delay_retry_001')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '결제 취소 요청' }));
    await waitFor(() => {
      expect(screen.getByText(/잠시 후 다시 시도하세요/)).toBeInTheDocument();
    });

    await user.click(await screen.findByRole('button', { name: '결제 취소 요청' }));
    await waitFor(() => {
      expect(screen.getByText('ord_admin_delay_retry_001 결제 취소 요청이 접수되었습니다.')).toBeInTheDocument();
    });

    expect(idempotencyKeys).toHaveLength(2);
    expect(idempotencyKeys[0]).toHaveLength(idempotencyKeys[1].length);
    expect(idempotencyKeys[0]).toBe(idempotencyKeys[1]);
  });

  it('lists outbox events for inventory and shows retry result', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    await user.selectOptions(screen.getByLabelText('서비스'), 'inventory');

    expect(await screen.findByText('evt-inv-001')).toBeInTheDocument();
    expect(await screen.findByText('temporary network issue')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '선택한 서비스 재시도' }));

    await waitFor(() => {
      expect(screen.getByText(/2건 claim, 1건 publish, 0건 fail/)).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: '실패 이벤트 재처리 준비' }));

    await waitFor(() => {
      expect(screen.getByText(/1건 requeue/)).toBeInTheDocument();
    });
  });

  it('registers and updates catalog products with idempotency key', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('LAPTOP-001');

    await user.click(screen.getByRole('button', { name: '새로 등록' }));
    await user.clear(screen.getByLabelText('상품코드(등록/수정)'));
    await user.type(screen.getByLabelText('상품코드(등록/수정)'), 'NEW-001');
    await user.clear(screen.getByLabelText('상품명'));
    await user.type(screen.getByLabelText('상품명'), 'wireless bag');
    await user.selectOptions(screen.getByLabelText('판매 상태'), 'STOPPED');
    await user.clear(screen.getByLabelText('가격'));
    await user.type(screen.getByLabelText('가격'), '35000');
    await user.click(screen.getByRole('button', { name: '상품 등록' }));

    await waitFor(() => {
      expect(screen.getByText('NEW-001 상품 등록이 완료되었습니다.')).toBeInTheDocument();
    });

    const createCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url).includes('/api/admin/products') && (init?.method ?? 'GET') === 'POST',
    );
    expect(createCalls.length).toBe(1);
    const [, createInit] = createCalls[0];
    expect(headerValue(createInit?.headers, 'Idempotency-Key')).not.toHaveLength(0);
    const createPayload = JSON.parse(String(createInit?.body ?? '{}'));
    expect(createPayload).toEqual({
      productCode: 'NEW-001',
      name: 'wireless bag',
      salesStatus: 'STOPPED',
      listPrice: 35000,
    });
    expect(createPayload).not.toHaveProperty('price');

    await user.click(screen.getByRole('button', { name: 'LAPTOP-001' }));
    await user.clear(screen.getByLabelText('상품명'));
    await user.type(screen.getByLabelText('상품명'), 'lightbook updated');
    await user.selectOptions(screen.getByLabelText('판매 상태'), 'ON_SALE');
    await user.clear(screen.getByLabelText('가격'));
    await user.type(screen.getByLabelText('가격'), '1500000');
    await user.click(screen.getByRole('button', { name: '상품 수정' }));

    await waitFor(() => {
      expect(screen.getByText('LAPTOP-001 상품 수정이 완료되었습니다.')).toBeInTheDocument();
    });

    const updateCalls = fetchMock.mock.calls.filter(
      ([url, init]) =>
        String(url).includes('/api/admin/products/LAPTOP-001') && (init?.method ?? 'GET') === 'PUT',
    );
    expect(updateCalls.length).toBe(1);
    const [, updateInit] = updateCalls[0];
    expect(headerValue(updateInit?.headers, 'Idempotency-Key')).not.toHaveLength(0);
    const updatePayload = JSON.parse(String(updateInit?.body ?? '{}'));
    expect(updatePayload).toEqual({
      name: 'lightbook updated',
      salesStatus: 'ON_SALE',
      listPrice: 1500000,
    });
    expect(updatePayload).not.toHaveProperty('productCode');
    expect(updatePayload).not.toHaveProperty('price');
  });

  it('reuses the same idempotency key when product creation is retried with identical payload', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const idempotencyKeys: string[] = [];
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/products' && method === 'POST') {
        attempt += 1;
        const key = headerValue(init?.headers, 'Idempotency-Key');
        idempotencyKeys.push(key);

        if (attempt === 1) {
          return buildErrorResponse('NETWORK', '잠시 후 다시 시도하세요');
        }
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('LAPTOP-001');

    await user.click(screen.getByRole('button', { name: '새로 등록' }));
    await user.clear(screen.getByLabelText('상품코드(등록/수정)'));
    await user.type(screen.getByLabelText('상품코드(등록/수정)'), 'NEW-001');
    await user.clear(screen.getByLabelText('상품명'));
    await user.type(screen.getByLabelText('상품명'), 'wireless bag');
    await user.selectOptions(screen.getByLabelText('판매 상태'), 'STOPPED');
    await user.clear(screen.getByLabelText('가격'));
    await user.type(screen.getByLabelText('가격'), '35000');

    await user.click(screen.getByRole('button', { name: '상품 등록' }));
    await waitFor(() => {
      expect(screen.getByText(/잠시 후 다시 시도하세요/)).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: '상품 등록' }));
    await waitFor(() => {
      expect(screen.getByText('NEW-001 상품 등록이 완료되었습니다.')).toBeInTheDocument();
    });

    expect(idempotencyKeys.length).toBe(2);
    expect(idempotencyKeys[0]).toHaveLength(idempotencyKeys[1].length);
    expect(idempotencyKeys[0]).toBe(idempotencyKeys[1]);
  });

  it('loads stock list for selected product and updates quantity', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('재고 조회 / 설정');

    await user.click(screen.getByRole('button', { name: '재고 조회' }));

    expect(await screen.findByText('SKU-001')).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: '선택' })[0]);

    await user.clear(screen.getByLabelText('가능 수량'));
    await user.type(screen.getByLabelText('가능 수량'), '20');
    await user.click(screen.getByRole('button', { name: '재고 설정' }));

    await waitFor(() => {
      expect(screen.getByText('SKU-001 재고가 20개로 저장되었습니다.')).toBeInTheDocument();
    });

    const stockCalls = fetchMock.mock.calls.filter(
      ([url, init]) =>
        String(url).includes('/api/admin/stocks/SKU-001') && (init?.method ?? 'GET') === 'PUT',
    );
    expect(stockCalls.length).toBe(1);
    const [, stockInit] = stockCalls[0];
    expect(headerValue(stockInit?.headers, 'Content-Type')).toBe('application/json');
    expect(stockInit?.body).toBe(JSON.stringify({ productCode: 'LAPTOP-001', availableQuantity: 20 }));
  });

  it('saves stock for direct SKU input (upsert-style flow)', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('재고 조회 / 설정');

    const skuInput = screen.getByLabelText('SKU');
    expect(skuInput).not.toHaveAttribute('readonly');

    await user.clear(skuInput);
    await user.type(skuInput, 'SKU-NEW');
    await user.clear(screen.getByLabelText('재고 대상 상품코드'));
    await user.type(screen.getByLabelText('재고 대상 상품코드'), 'NEW-PROD-001');
    await user.clear(screen.getByLabelText('가능 수량'));
    await user.type(screen.getByLabelText('가능 수량'), '7');
    await user.click(screen.getByRole('button', { name: '재고 설정' }));

    await waitFor(() => {
      expect(screen.getByText('SKU-NEW 재고가 7개로 저장되었습니다.')).toBeInTheDocument();
    });

    const stockCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url).includes('/api/admin/stocks/SKU-NEW') && (init?.method ?? 'GET') === 'PUT',
    );
    expect(stockCalls.length).toBe(1);
    const [, stockInit] = stockCalls[0];
    expect(headerValue(stockInit?.headers, 'Content-Type')).toBe('application/json');
    expect(stockInit?.body).toBe(JSON.stringify({ productCode: 'NEW-PROD-001', availableQuantity: 7 }));
  });

  it('shows an alert when order list request fails', async () => {
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return buildErrorResponse('ORDER_LIST_FAIL', '주문 목록 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent('ORDER_LIST_FAIL: 주문 목록 조회 실패');
    expect(await screen.findByText('목록을 읽지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('ord_admin_001')).not.toBeInTheDocument();
  });

  it('shows saga detail error and removes previous saga content after failed fetch', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_001',
                memberId: 'member-a',
                status: 'CREATED',
                sagaStatus: 'STARTED',
                paymentMethod: 'CARD',
                totalAmount: 12000,
                itemCount: 1,
                createdAt: '2026-05-13T00:00:00Z',
                updatedAt: '2026-05-13T00:10:00Z',
              },
              {
                orderId: 'ord_admin_002',
                memberId: 'member-b',
                status: 'CREATED',
                sagaStatus: 'FAILED',
                paymentMethod: 'CARD',
                totalAmount: 22000,
                itemCount: 2,
                createdAt: '2026-05-13T00:20:00Z',
                updatedAt: '2026-05-13T00:30:00Z',
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_001/saga' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_001',
            orderStatus: 'CREATED',
            sagaStatus: 'STARTED',
            failedAt: null,
            businessReason: 'CREATED',
            technicalErrorMessage: 'ok',
            lastEventType: 'OrderCreated',
            outboxAttempts: 0,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_002/saga' && method === 'GET') {
        return buildErrorResponse('SAGA_FETCH_FAILED', '주문 상세 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();
    expect(await screen.findByText('OrderCreated')).toBeInTheDocument();

    await user.click(await screen.findByText('ord_admin_002'));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('SAGA_FETCH_FAILED: 주문 상세 조회 실패');
    });

    expect(screen.queryByText('OrderCreated')).not.toBeInTheDocument();
    expect(screen.queryByText('ok')).not.toBeInTheDocument();
  });

  it('does not show cancel action for non-delayed selected order', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_ready_001',
                memberId: 'member-ready',
                status: 'CREATED',
                sagaStatus: 'COMPLETED',
                paymentMethod: 'CARD',
                totalAmount: 3000,
                itemCount: 1,
                createdAt: '2026-05-13T00:00:00Z',
                updatedAt: '2026-05-13T00:00:10Z',
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_ready_001/saga' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_ready_001',
            orderStatus: 'CREATED',
            sagaStatus: 'COMPLETED',
            failedAt: null,
            businessReason: 'NONE',
            technicalErrorMessage: null,
            lastEventType: 'OrderCompleted',
            outboxAttempts: 0,
          }),
          200,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_ready_001')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '결제 취소 요청' })).not.toBeInTheDocument();
  });

  it('shows cancel error and keeps idempotency key for payment-cancel retry', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const keys: string[] = [];

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_admin_delay_retry_002',
                memberId: 'member-delay',
                status: 'CREATED',
                sagaStatus: 'PAYMENT_DELAYED',
                paymentMethod: 'DELAY_CARD',
                totalAmount: 24000,
                itemCount: 1,
                createdAt: '2026-05-13T00:10:00Z',
                updatedAt: '2026-05-13T00:11:00Z',
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_retry_002/saga' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_retry_002',
            orderStatus: 'CREATED',
            sagaStatus: 'PAYMENT_DELAYED',
            failedAt: null,
            businessReason: 'PAYMENT_DELAYED',
            technicalErrorMessage: null,
            lastEventType: 'PaymentAuthorizationDelayed',
            outboxAttempts: 0,
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/orders/ord_admin_delay_retry_002/cancel' && method === 'POST') {
        attempt += 1;
        keys.push(headerValue(init?.headers, 'Idempotency-Key'));

        if (attempt === 1) {
          return buildErrorResponse('CANCEL_FAIL', '일시적으로 취소 요청에 실패했습니다.');
        }

        return toJsonResponse(
          buildResponse(true, {
            orderId: 'ord_admin_delay_retry_002',
            status: 'CREATED',
            sagaStatus: 'PAYMENT_CANCEL_REQUESTED',
          }),
          202,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_delay_retry_002')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '결제 취소 요청' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('CANCEL_FAIL: 일시적으로 취소 요청에 실패했습니다.');
    });

    await user.click(await screen.findByRole('button', { name: '결제 취소 요청' }));
    await waitFor(() => {
      expect(screen.getByText('ord_admin_delay_retry_002 결제 취소 요청이 접수되었습니다.')).toBeInTheDocument();
    });

    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('loads order/inventory/payment outbox lists according to selected service', async () => {
    const user = userEvent.setup();
    const outboxLoadCalls: string[] = [];

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        outboxLoadCalls.push('order');
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-order-001',
                aggregateType: 'order',
                aggregateId: 'ord_admin_001',
                eventType: 'OrderCreated',
                status: 'FAILED',
                retryCount: 1,
                maxRetryCount: 3,
                errorMessage: 'order event err',
                nextRetryAt: null,
                createdAt: '2026-05-13T00:01:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events' && method === 'GET') {
        outboxLoadCalls.push('inventory');
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-inventory-001',
                aggregateType: 'inventory',
                aggregateId: 'sku-1',
                eventType: 'InventoryReserved',
                status: 'FAILED',
                retryCount: 0,
                maxRetryCount: 3,
                errorMessage: 'inventory event err',
                nextRetryAt: null,
                createdAt: '2026-05-13T00:01:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/payment/events' && method === 'GET') {
        outboxLoadCalls.push('payment');
        return toJsonResponse(buildResponse(true, { limit: 50, offset: 0, items: [] }), 200);
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Outbox' }));

    expect(await screen.findByText('evt-order-001')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('서비스'), 'inventory');
    expect(await screen.findByText('evt-inventory-001')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('서비스'), 'payment');
    expect(await screen.findByText('조건에 맞는 이벤트가 없습니다.')).toBeInTheDocument();

    expect(outboxLoadCalls).toEqual(['order', 'inventory', 'payment']);
  });

  it('shows outbox retry and requeue failures as alerts', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/outbox-services/inventory/events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-inventory-001',
                aggregateType: 'inventory',
                aggregateId: 'ord-admin-001',
                eventType: 'InventoryReserved',
                status: 'FAILED',
                retryCount: 1,
                maxRetryCount: 3,
                errorMessage: 'retry soon',
                nextRetryAt: null,
                createdAt: '2026-05-13T00:04:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events/retry' && method === 'POST') {
        return buildErrorResponse('OUTBOX_RETRY_FAIL', '재시도 요청 처리 실패');
      }

      if (request.pathname === '/api/admin/outbox-services/inventory/events/failed/requeue' && method === 'POST') {
        return buildErrorResponse('OUTBOX_REQUEUE_FAIL', '재처리 준비 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    await user.selectOptions(screen.getByLabelText('서비스'), 'inventory');

    expect(await screen.findByText('evt-inventory-001')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '선택한 서비스 재시도' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('OUTBOX_RETRY_FAIL: 재시도 요청 처리 실패');
    });

    await user.click(screen.getByRole('button', { name: '실패 이벤트 재처리 준비' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('OUTBOX_REQUEUE_FAIL: 재처리 준비 실패');
    });
  });

  it('disables retry button while retry is in progress', async () => {
    const user = userEvent.setup();
    let retryResolver: (response: Response) => void = () => {};
    const retryDeferred = new Promise<Response>((resolve) => {
      retryResolver = resolve;
    });

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-order-001',
                aggregateType: 'order',
                aggregateId: 'ord-admin-001',
                eventType: 'OrderCreated',
                status: 'FAILED',
                retryCount: 1,
                maxRetryCount: 3,
                errorMessage: 'retry soon',
                nextRetryAt: null,
                createdAt: '2026-05-13T00:04:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/order/events/retry' && method === 'POST') {
        return retryDeferred;
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    expect(await screen.findByText('evt-order-001')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '선택한 서비스 재시도' }));
    expect(screen.getByRole('button', { name: '재시도 진행 중' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '실패 이벤트 재처리 준비' })).toBeEnabled();

    retryResolver(
      new Response(
        JSON.stringify(buildResponse(true, { claimed: 0, published: 0, failed: 0 })),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '선택한 서비스 재시도' })).toBeEnabled();
    });
  });

  it('disables requeue button while requeue is in progress', async () => {
    const user = userEvent.setup();
    let requeueResolver: (response: Response) => void = () => {};
    const requeueDeferred = new Promise<Response>((resolve) => {
      requeueResolver = resolve;
    });

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [
              {
                eventId: 'evt-order-001',
                aggregateType: 'order',
                aggregateId: 'ord-admin-001',
                eventType: 'OrderCreated',
                status: 'FAILED',
                retryCount: 1,
                maxRetryCount: 3,
                errorMessage: 'requeue soon',
                nextRetryAt: null,
                createdAt: '2026-05-13T00:04:00Z',
                publishedAt: null,
              },
            ],
          }),
          200,
        );
      }

      if (request.pathname === '/api/admin/outbox-services/order/events/failed/requeue' && method === 'POST') {
        return requeueDeferred;
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    expect(await screen.findByText('evt-order-001')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '실패 이벤트 재처리 준비' }));
    expect(screen.getByRole('button', { name: '재처리 준비 중' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '선택한 서비스 재시도' })).toBeEnabled();

    requeueResolver(
      new Response(
        JSON.stringify(buildResponse(true, { updated: 1 })),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '실패 이벤트 재처리 준비' })).toBeEnabled();
    });
  });

  it('shows empty outbox state for a service with no events', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, { limit: 50, offset: 0, items: [] }),
          200,
        );
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Outbox' }));

    expect(await screen.findByText('조건에 맞는 이벤트가 없습니다.')).toBeInTheDocument();
  });

  it('validates product create form for missing code and name', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('LAPTOP-001');
    await user.click(screen.getByRole('button', { name: '새로 등록' }));

    await user.clear(screen.getByLabelText('상품코드(등록/수정)'));
    await user.clear(screen.getByLabelText('상품명'));
    await user.clear(screen.getByLabelText('가격'));
    await user.type(screen.getByLabelText('가격'), '1000');
    await user.click(screen.getByRole('button', { name: '상품 등록' }));

    expect(screen.getByRole('alert')).toHaveTextContent('상품코드, 상품명, 판매가격을 모두 입력하세요.');
  });

  it.each(['0', '-100'])('validates non-positive product list price: %s', async (listPrice) => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('LAPTOP-001');
    await user.click(screen.getByRole('button', { name: '새로 등록' }));
    await user.clear(screen.getByLabelText('상품코드(등록/수정)'));
    await user.type(screen.getByLabelText('상품코드(등록/수정)'), 'NEW-001');
    await user.clear(screen.getByLabelText('상품명'));
    await user.type(screen.getByLabelText('상품명'), 'wireless bag');
    await user.clear(screen.getByLabelText('가격'));
    await user.type(screen.getByLabelText('가격'), listPrice);

    await user.click(screen.getByRole('button', { name: '상품 등록' }));
    expect(screen.getByRole('alert')).toHaveTextContent('판매가격은 0보다 커야 합니다.');
  });

  it('validates stock quantity input: blank, negative, and non-integer values', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('재고 조회 / 설정');
    await user.click(await screen.findByRole('button', { name: '재고 조회' }));
    await screen.findByText('SKU-001');

    await user.click(screen.getByRole('button', { name: '선택' }));
    await user.clear(screen.getByLabelText('가능 수량'));
    await user.click(screen.getByRole('button', { name: '재고 설정' }));

    expect(screen.getByRole('alert')).toHaveTextContent('SKU, 상품코드, 재고 수량을 모두 입력하세요.');

    await user.clear(screen.getByLabelText('가능 수량'));
    await user.type(screen.getByLabelText('가능 수량'), '-1');
    await user.click(screen.getByRole('button', { name: '재고 설정' }));
    expect(screen.getByRole('alert')).toHaveTextContent('재고 수량은 0 이상의 정수여야 합니다.');

    await user.clear(screen.getByLabelText('가능 수량'));
    await user.type(screen.getByLabelText('가능 수량'), '3.5');
    await user.click(screen.getByRole('button', { name: '재고 설정' }));
    expect(screen.getByRole('alert')).toHaveTextContent('재고 수량은 0 이상의 정수여야 합니다.');
  });

  it('validates stock lookup requires product code', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    await screen.findByText('재고 조회 / 설정');
    await user.clear(screen.getByLabelText('조회 상품코드'));
    await user.click(screen.getByRole('button', { name: '재고 조회' }));

    expect(screen.getByText('상품코드를 입력하거나 상품을 선택해 주세요.')).toBeInTheDocument();
  });

  it('shows catalog list and stock lookup failures', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/products' && method === 'GET') {
        return buildErrorResponse('PRODUCT_LIST_FAIL', '상품 목록 조회 실패');
      }

      if (request.pathname === '/api/admin/stocks' && method === 'GET') {
        return buildErrorResponse('STOCK_LIST_FAIL', '재고 조회 실패');
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);
    await user.click(screen.getByRole('tab', { name: 'Products' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('PRODUCT_LIST_FAIL: 상품 목록 조회 실패');

    await user.click(screen.getByRole('button', { name: '재고 조회' }));
    await user.type(screen.getByLabelText('조회 상품코드'), 'LAPTOP-001');
    await user.click(screen.getByRole('button', { name: '재고 조회' }));
    expect(await screen.findByText('STOCK_LIST_FAIL: 재고 조회 실패')).toBeInTheDocument();
  });

  it('isolates per-tab states when switching tabs', async () => {
    const user = userEvent.setup();
    const callCounts = {
      orders: 0,
      outbox: 0,
      products: 0,
    };

    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        callCounts.orders += 1;
      }

      if (request.pathname === '/api/admin/outbox-services/order/events' && method === 'GET') {
        callCounts.outbox += 1;
      }

      if (request.pathname === '/api/admin/products' && method === 'GET') {
        callCounts.products += 1;
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    expect(await screen.findByText('Outbox 운영')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Products' }));
    expect(await screen.findByText('LAPTOP-001')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Orders' }));
    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();

    expect(callCounts.outbox).toBe(1);
    expect(callCounts.products).toBe(1);
    expect(callCounts.orders).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('evt-order-001')).not.toBeInTheDocument();
  });

  it('blocks admin app usage when no access token is stored', async () => {
    const user = userEvent.setup();
    clearAuthenticatedState();
    const tokenUrlSearchParams = new URLSearchParams(window.location.search);
    tokenUrlSearchParams.delete('code');
    tokenUrlSearchParams.delete('state');
    window.history.replaceState({}, '', `/${tokenUrlSearchParams.toString() ? `?${tokenUrlSearchParams}` : ''}`);

    render(<App />);

    expect(await screen.findByText('관리자 기능은 로그인 후 이용할 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
    expect(screen.getByText('상태:')).toBeInTheDocument();
    expect(screen.getByText('unauthenticated')).toBeInTheDocument();
    const adminCalls = fetchMock.mock.calls.map(([url]) => url).filter(isAdminApiCall);
    expect(adminCalls).toHaveLength(0);
    await expect(user.click(screen.getByRole('button', { name: '로그인' }))).resolves.not.toThrow();
  });

  it('injects bearer token into admin API requests', async () => {
    const user = userEvent.setup();

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();

    const orderCalls = fetchMock.mock.calls.filter(
      ([url]) => new URL(String(url), 'http://localhost:5173').pathname === '/api/admin/orders',
    );
    expect(orderCalls.length).toBeGreaterThan(0);
    expect(headerValue(orderCalls[0][1]?.headers, 'Authorization')).toBe(`Bearer ${TEST_ACCESS_TOKEN}`);

    await user.click(screen.getByRole('tab', { name: 'Outbox' }));
    const outboxCalls = fetchMock.mock.calls.filter(
      ([url]) => new URL(String(url), 'http://localhost:5173').pathname === '/api/admin/outbox-services/order/events',
    );
    expect(outboxCalls.length).toBeGreaterThan(0);
    expect(headerValue(outboxCalls[0][1]?.headers, 'Authorization')).toBe(`Bearer ${TEST_ACCESS_TOKEN}`);
  });

  it('clears stored token and stops protected API calls after logout', async () => {
    const user = userEvent.setup();

    render(<App />);
    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();
    expect(await screen.findByText('OrderCancelled')).toBeInTheDocument();
    expect(localStorage.getItem(AUTH_ACCESS_TOKEN_STORAGE_KEY)).toBe(TEST_ACCESS_TOKEN);

    fetchMock.mockClear();
    const beforeLogoutCalls = fetchMock.mock.calls.length;

    await user.click(screen.getByRole('button', { name: '로그아웃' }));

    expect(localStorage.getItem(AUTH_ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    expect(await screen.findByText('상태:')).toBeInTheDocument();
    expect(screen.getByText('unauthenticated')).toBeInTheDocument();

    await waitFor(() => {
      const newCalls = fetchMock.mock.calls.slice(beforeLogoutCalls).map(([url]) => url);
      expect(newCalls.every((url) => !isAdminApiCall(url))).toBe(true);
    });
  });

  it('shows forbidden-ish state when admin APIs deny access', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/api/admin/orders' && method === 'GET') {
        return buildErrorResponse('FORBIDDEN', '권한이 없습니다.', 403);
      }

      return defaultRequestHandler(input, init);
    });

    render(<App />);

    await user.click(screen.getByRole('tab', { name: 'Orders' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('FORBIDDEN: 권한이 없습니다.');
    expect(screen.getByText('목록을 읽지 못했습니다.')).toBeInTheDocument();
  });
});
