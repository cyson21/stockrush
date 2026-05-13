import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

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

describe('admin app operations', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let defaultRequestHandler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);

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

      if (request.pathname === '/orders/api/admin/orders' && method === 'GET') {
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
        request.pathname.startsWith('/orders/api/admin/orders/') &&
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
        return toJsonResponse(buildResponse(true, { claimed: 2, published: 1, failed: 0 }), 200);
      }

      if (request.pathname === '/catalog/api/products' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, catalogProducts.filter((product) => request.searchParams.get('status') === product.status)),
          200,
        );
      }

      if (request.pathname === '/catalog/api/admin/products' && method === 'POST') {
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

      if (request.pathname === '/catalog/api/admin/products/LAPTOP-001' && method === 'PUT') {
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

      if (request.pathname === '/inventory/api/stocks' && method === 'GET') {
        const productCode = request.searchParams.get('productCode');
        return toJsonResponse(
          buildResponse(
            true,
            stocks.filter((stock) => !productCode || stock.productCode === productCode),
          ),
          200,
        );
      }

      if (request.pathname.startsWith('/inventory/api/stocks/') && method === 'PUT') {
        const pathSkuId = request.pathname.replace('/inventory/api/stocks/', '');
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
  });

  it('loads orders and fetches selected order saga', async () => {
    const user = userEvent.setup();

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /ord_admin_002/ }));

    await waitFor(() => {
      const sagaRequests = fetchMock.mock.calls.filter(([url]) =>
        String(url).includes('/orders/api/admin/orders/ord_admin_002/saga'),
      );
      expect(sagaRequests.length).toBeGreaterThanOrEqual(1);
    });

    expect(await screen.findByText('PAYMENT_DECLINED')).toBeInTheDocument();
    expect(screen.getByText('OrderCancelled')).toBeInTheDocument();
  });

  it('requests cancel for a payment delayed order', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((input, init) => {
      const request = new URL(String(input), 'http://localhost:5173');
      const method = init?.method ?? 'GET';

      if (request.pathname === '/orders/api/admin/orders' && method === 'GET') {
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

      if (request.pathname === '/orders/api/admin/orders/ord_admin_delay_001/saga' && method === 'GET') {
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

      if (request.pathname === '/orders/api/admin/orders/ord_admin_delay_001/cancel' && method === 'POST') {
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
        String(url).includes('/orders/api/admin/orders/ord_admin_delay_001/cancel') &&
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

      if (request.pathname === '/orders/api/admin/orders' && method === 'GET') {
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

      if (request.pathname === '/orders/api/admin/orders/ord_admin_delay_retry_001/saga' && method === 'GET') {
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

      if (request.pathname === '/orders/api/admin/orders/ord_admin_delay_retry_001/cancel' && method === 'POST') {
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
      ([url, init]) => String(url).includes('/catalog/api/admin/products') && (init?.method ?? 'GET') === 'POST',
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
        String(url).includes('/catalog/api/admin/products/LAPTOP-001') && (init?.method ?? 'GET') === 'PUT',
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

      if (request.pathname === '/catalog/api/admin/products' && method === 'POST') {
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
        String(url).includes('/inventory/api/stocks/SKU-001') && (init?.method ?? 'GET') === 'PUT',
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
      ([url, init]) => String(url).includes('/inventory/api/stocks/SKU-NEW') && (init?.method ?? 'GET') === 'PUT',
    );
    expect(stockCalls.length).toBe(1);
    const [, stockInit] = stockCalls[0];
    expect(headerValue(stockInit?.headers, 'Content-Type')).toBe('application/json');
    expect(stockInit?.body).toBe(JSON.stringify({ productCode: 'NEW-PROD-001', availableQuantity: 7 }));
  });
});
