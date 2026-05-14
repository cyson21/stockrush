import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import ProductListScreen from './ProductListScreen';

const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

const jsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(response(body, status));

function deferredJsonResponse() {
  let resolveResponse!: (response: Response) => void;
  const promise = new Promise<Response>((resolve) => {
    resolveResponse = resolve;
  });

  return {
    promise,
    resolve: (body: unknown, status = 200) => {
      resolveResponse(response(body, status));
    },
  };
}

describe('ProductListScreen', () => {
  const fetchMock = jest.fn() as jest.MockedFunction<typeof fetch>;

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock;
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('loads on-sale products and shows selected SKU stock', async () => {
    fetchMock.mockImplementation((input) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/products?status=ON_SALE') {
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
          error: null,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-001') {
        return jsonResponse({
          success: true,
          data: [
            {
              skuId: 'LIMITED-001-S',
              productCode: 'LIMITED-001',
              availableQuantity: 8,
              reservedQuantity: 2,
              version: 4,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-stocks' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    render(<ProductListScreen />);

    expect(screen.getByText('상품 목록 조회 중')).toBeTruthy();
    expect(await screen.findByText('Limited Hoodie')).toBeTruthy();
    expect(screen.getByText('12,000원')).toBeTruthy();

    fireEvent.press(screen.getByText('Limited Hoodie'));

    expect(await screen.findByText('LIMITED-001-S')).toBeTruthy();
    expect(screen.getByText('주문 가능 8')).toBeTruthy();
    expect(screen.getByText('예약 중 2')).toBeTruthy();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        'http://localhost:18080/api/products?status=ON_SALE',
        expect.objectContaining({ method: 'GET' }),
      );
      expect(fetchMock).toHaveBeenCalledWith(
        'http://localhost:18080/api/stocks?productCode=LIMITED-001',
        expect.objectContaining({ method: 'GET' }),
      );
    });
  });

  it('shows retry action when product loading fails', async () => {
    fetchMock
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code: 'CATALOG_DOWN', message: 'catalog unavailable', details: {} },
            trace: { correlationId: 'corr-error' },
          }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            success: true,
            data: [],
            error: null,
            trace: { correlationId: 'corr-empty' },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      );

    render(<ProductListScreen />);

    expect(await screen.findByText('CATALOG_DOWN: catalog unavailable')).toBeTruthy();

    fireEvent.press(screen.getByText('다시 조회'));

    expect(await screen.findByText('판매 중인 상품이 없습니다.')).toBeTruthy();
  });

  it('shows retry action when stock loading fails', async () => {
    fetchMock
      .mockResolvedValueOnce(
        response({
          success: true,
          data: [
            {
              productCode: 'LIMITED-001',
              name: 'Limited Hoodie',
              status: 'ON_SALE',
              listPrice: 12000,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-products' },
        }),
      )
      .mockResolvedValueOnce(
        response(
          {
            success: false,
            data: null,
            error: { code: 'STOCK_DOWN', message: 'inventory unavailable', details: {} },
            trace: { correlationId: 'corr-stock-error' },
          },
          503,
        ),
      )
      .mockResolvedValueOnce(
        response({
          success: true,
          data: [
            {
              skuId: 'LIMITED-001-S',
              productCode: 'LIMITED-001',
              availableQuantity: 3,
              reservedQuantity: 1,
              version: 5,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-stock-retry' },
        }),
      );

    render(<ProductListScreen />);

    fireEvent.press(await screen.findByText('Limited Hoodie'));

    expect(await screen.findByText('STOCK_DOWN: inventory unavailable')).toBeTruthy();

    fireEvent.press(screen.getByText('다시 조회'));

    expect(await screen.findByText('LIMITED-001-S')).toBeTruthy();
    expect(screen.getByText('주문 가능 3')).toBeTruthy();
  });

  it('keeps the latest selected product stock when stock responses finish out of order', async () => {
    const firstStockResponse = deferredJsonResponse();
    const secondStockResponse = deferredJsonResponse();

    fetchMock.mockImplementation((input) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/products?status=ON_SALE') {
        return jsonResponse({
          success: true,
          data: [
            {
              productCode: 'LIMITED-001',
              name: 'Limited Hoodie',
              status: 'ON_SALE',
              listPrice: 12000,
            },
            {
              productCode: 'LIMITED-002',
              name: 'Second Sneakers',
              status: 'ON_SALE',
              listPrice: 34000,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-001') {
        return firstStockResponse.promise;
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-002') {
        return secondStockResponse.promise;
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    render(<ProductListScreen />);

    fireEvent.press(await screen.findByText('Limited Hoodie'));
    fireEvent.press(await screen.findByText('Second Sneakers'));

    await act(async () => {
      secondStockResponse.resolve({
        success: true,
        data: [
          {
            skuId: 'LIMITED-002-260',
            productCode: 'LIMITED-002',
            availableQuantity: 6,
            reservedQuantity: 0,
            version: 7,
          },
        ],
        error: null,
        trace: { correlationId: 'corr-stock-second' },
      });
    });

    expect(await screen.findByText('LIMITED-002-260')).toBeTruthy();

    await act(async () => {
      firstStockResponse.resolve({
        success: true,
        data: [
          {
            skuId: 'LIMITED-001-S',
            productCode: 'LIMITED-001',
            availableQuantity: 8,
            reservedQuantity: 2,
            version: 4,
          },
        ],
        error: null,
        trace: { correlationId: 'corr-stock-first' },
      });
    });

    await waitFor(() => {
      expect(screen.queryByText('LIMITED-001-S')).toBeNull();
      expect(screen.getByText('LIMITED-002-260')).toBeTruthy();
    });
  });

  it('quotes a coupon and sends a discounted order payload', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/products?status=ON_SALE') {
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
          error: null,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-001') {
        return jsonResponse({
          success: true,
          data: [
            {
              skuId: 'LIMITED-001-S',
              productCode: 'LIMITED-001',
              availableQuantity: 8,
              reservedQuantity: 2,
              version: 4,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-stocks' },
        });
      }

      if (url === 'http://localhost:18080/api/coupons/quote' && init?.method === 'POST') {
        return jsonResponse({
          success: true,
          data: {
            couponCode: 'WELCOME10',
            applied: true,
            discountAmount: 5000,
            payAmount: 7000,
            reason: 'APPLIED',
          },
          error: null,
          trace: { correlationId: 'corr-quote' },
        });
      }

      if (url === 'http://localhost:18080/api/orders' && init?.method === 'POST') {
        return jsonResponse(
          {
            success: true,
            data: {
              orderId: 'ord_mobile_001',
              status: 'CREATED',
              sagaStatus: 'STARTED',
              paymentMethod: 'FAIL_CARD',
              couponCode: 'WELCOME10',
              totalAmount: 12000,
              discountAmount: 5000,
              payableAmount: 7000,
            },
            error: null,
            trace: { correlationId: 'corr-order' },
          },
          201,
        );
      }

      if (url === 'http://localhost:18080/api/orders/ord_mobile_001' && init?.method === 'GET') {
        return jsonResponse({
          success: true,
          data: {
            orderId: 'ord_mobile_001',
            memberId: 'member-mobile-demo',
            status: 'CANCELLED',
            sagaStatus: 'FAILED',
            paymentMethod: 'FAIL_CARD',
            couponCode: 'WELCOME10',
            totalAmount: 12000,
            discountAmount: 5000,
            payableAmount: 7000,
            items: [
              {
                productCode: 'LIMITED-001',
                skuId: 'LIMITED-001-S',
                quantity: 1,
                unitPrice: 12000,
                lineAmount: 12000,
              },
            ],
          },
          error: null,
          trace: { correlationId: 'corr-order-detail' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    render(<ProductListScreen />);

    fireEvent.press(await screen.findByText('Limited Hoodie'));
    expect(await screen.findByText('LIMITED-001-S')).toBeTruthy();

    fireEvent.changeText(screen.getByLabelText('쿠폰 코드'), 'WELCOME10');
    fireEvent.press(screen.getByText('쿠폰 적용'));

    expect(await screen.findByText('쿠폰 적용: APPLIED')).toBeTruthy();
    expect(screen.getAllByText('할인 5,000원').length).toBeGreaterThan(0);
    expect(screen.getAllByText('결제 예정 7,000원').length).toBeGreaterThan(0);

    fireEvent.press(screen.getByText('FAIL_CARD'));
    fireEvent.press(screen.getByText('주문 생성'));

    expect(await screen.findByText('ord_mobile_001')).toBeTruthy();
    expect(await screen.findByText('FAILED')).toBeTruthy();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        'http://localhost:18080/api/orders/ord_mobile_001',
        expect.objectContaining({ method: 'GET' }),
      );
    });

    const quoteRequest = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === 'http://localhost:18080/api/coupons/quote' && init?.method === 'POST',
    );
    expect(JSON.parse(String(quoteRequest?.[1]?.body))).toEqual({
      couponCode: 'WELCOME10',
      orderAmount: 12000,
    });

    const orderRequest = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === 'http://localhost:18080/api/orders' && init?.method === 'POST',
    );
    expect(orderRequest?.[1]?.headers).toEqual(
      expect.objectContaining({
        'Content-Type': 'application/json',
        'Idempotency-Key': expect.stringMatching(/^mobile-order-create-/),
        'X-Correlation-Id': 'mobile-order-create',
      }),
    );
    expect(JSON.parse(String(orderRequest?.[1]?.body))).toEqual({
      memberId: 'member-mobile-demo',
      paymentMethod: 'FAIL_CARD',
      couponCode: 'WELCOME10',
      items: [
        {
          productCode: 'LIMITED-001',
          skuId: 'LIMITED-001-S',
          quantity: 1,
          unitPrice: 12000,
        },
      ],
    });
  });

  it('blocks order creation when coupon quote is not applied', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/products?status=ON_SALE') {
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
          error: null,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-001') {
        return jsonResponse({
          success: true,
          data: [
            {
              skuId: 'LIMITED-001-S',
              productCode: 'LIMITED-001',
              availableQuantity: 8,
              reservedQuantity: 2,
              version: 4,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-stocks' },
        });
      }

      if (url === 'http://localhost:18080/api/coupons/quote' && init?.method === 'POST') {
        return jsonResponse({
          success: true,
          data: {
            couponCode: 'BAD01',
            applied: false,
            discountAmount: 0,
            payAmount: 12000,
            reason: 'INVALID_COUPON',
          },
          error: null,
          trace: { correlationId: 'corr-quote' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    render(<ProductListScreen />);

    fireEvent.press(await screen.findByText('Limited Hoodie'));
    expect(await screen.findByText('LIMITED-001-S')).toBeTruthy();

    fireEvent.changeText(screen.getByLabelText('쿠폰 코드'), 'BAD01');
    fireEvent.press(screen.getByText('쿠폰 적용'));

    expect(await screen.findByText('쿠폰 적용: INVALID_COUPON')).toBeTruthy();
    expect(screen.getByText('쿠폰 적용 상태를 확인하세요.')).toBeTruthy();

    fireEvent.press(screen.getByText('주문 생성'));

    expect(
      fetchMock.mock.calls.some(([url, init]) => String(url) === 'http://localhost:18080/api/orders' && init?.method === 'POST'),
    ).toBe(false);
  });

  it('polls order status until a terminal saga status is received', async () => {
    jest.useFakeTimers();
    let detailCallCount = 0;

    fetchMock.mockImplementation((input, init) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/products?status=ON_SALE') {
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
          error: null,
          trace: { correlationId: 'corr-products' },
        });
      }

      if (url === 'http://localhost:18080/api/stocks?productCode=LIMITED-001') {
        return jsonResponse({
          success: true,
          data: [
            {
              skuId: 'LIMITED-001-S',
              productCode: 'LIMITED-001',
              availableQuantity: 8,
              reservedQuantity: 2,
              version: 4,
            },
          ],
          error: null,
          trace: { correlationId: 'corr-stocks' },
        });
      }

      if (url === 'http://localhost:18080/api/orders' && init?.method === 'POST') {
        return jsonResponse(
          {
            success: true,
            data: {
              orderId: 'ord_mobile_polling',
              status: 'CREATED',
              sagaStatus: 'STARTED',
              paymentMethod: 'CARD',
              couponCode: null,
              totalAmount: 12000,
              discountAmount: 0,
              payableAmount: 12000,
            },
            error: null,
            trace: { correlationId: 'corr-order' },
          },
          201,
        );
      }

      if (url === 'http://localhost:18080/api/orders/ord_mobile_polling' && init?.method === 'GET') {
        detailCallCount += 1;
        const completed = detailCallCount >= 2;

        return jsonResponse({
          success: true,
          data: {
            orderId: 'ord_mobile_polling',
            memberId: 'member-mobile-demo',
            status: completed ? 'CONFIRMED' : 'CREATED',
            sagaStatus: completed ? 'COMPLETED' : 'STARTED',
            paymentMethod: 'CARD',
            couponCode: null,
            totalAmount: 12000,
            discountAmount: 0,
            payableAmount: 12000,
            items: [
              {
                productCode: 'LIMITED-001',
                skuId: 'LIMITED-001-S',
                quantity: 1,
                unitPrice: 12000,
                lineAmount: 12000,
              },
            ],
          },
          error: null,
          trace: { correlationId: 'corr-order-detail' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    render(<ProductListScreen />);

    fireEvent.press(await screen.findByText('Limited Hoodie'));
    expect(await screen.findByText('LIMITED-001-S')).toBeTruthy();

    fireEvent.press(screen.getByText('주문 생성'));

    expect(await screen.findByText('ord_mobile_polling')).toBeTruthy();
    expect(await screen.findByText('STARTED')).toBeTruthy();

    await act(async () => {
      jest.advanceTimersByTime(2000);
      await Promise.resolve();
    });

    expect(await screen.findByText('COMPLETED')).toBeTruthy();

    await act(async () => {
      jest.advanceTimersByTime(4000);
      await Promise.resolve();
    });

    expect(detailCallCount).toBe(2);
  });

});
