import { useEffect, useMemo, useState } from 'react';
import { listOnSaleProducts } from './api/catalog';
import { ApiClientError } from './api/client';
import {
  clearAuthSession,
  completeLoginFromCallback,
  getAuthToken,
  startLogin,
} from './auth/oidc';
import { listStocks } from './api/inventory';
import { createOrder, getOrder } from './api/orders';
import { quoteCoupon } from './api/promotion';
import type { CreateOrderResponse, OrderDetail, Product, PromotionQuoteResponse, Stock } from './types/api';

type LoadState = 'idle' | 'loading' | 'ready' | 'error';

const terminalSagaStatuses = new Set(['COMPLETED', 'FAILED']);
const maxStatusPollingFailures = 3;

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(value);
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError && error.apiError) {
    return `${error.apiError.code}: ${error.apiError.message}`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return '요청을 처리하지 못했습니다.';
}

export default function App() {
  const [products, setProducts] = useState<Product[]>([]);
  const [productsState, setProductsState] = useState<LoadState>('idle');
  const [productQuery, setProductQuery] = useState('');
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [stocks, setStocks] = useState<Stock[]>([]);
  const [selectedSkuId, setSelectedSkuId] = useState('');
  const [quantityInput, setQuantityInput] = useState('1');
  const [memberId, setMemberId] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('CARD');
  const [authToken, setAuthToken] = useState<string | null>(getAuthToken());
  const [createdOrder, setCreatedOrder] = useState<CreateOrderResponse | null>(null);
  const [orderDetail, setOrderDetail] = useState<OrderDetail | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [couponCode, setCouponCode] = useState('');
  const [couponQuote, setCouponQuote] = useState<PromotionQuoteResponse | null>(null);
  const [couponQuoteError, setCouponQuoteError] = useState<string | null>(null);
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    completeLoginFromCallback()
      .then((session) => {
        if (!cancelled && session?.accessToken) {
          setAuthToken(session.accessToken);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setMessage(errorMessage(error));
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setProductsState('loading');

    listOnSaleProducts(productQuery)
      .then((nextProducts) => {
        if (cancelled) {
          return;
        }
        setProducts(nextProducts);
        setMessage(null);
        setSelectedProduct((current) =>
          current && nextProducts.some((product) => product.productCode === current.productCode)
            ? current
            : null,
        );
        setProductsState('ready');
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        setProductsState('error');
        setMessage(errorMessage(error));
      });

    return () => {
      cancelled = true;
    };
  }, [productQuery]);

  useEffect(() => {
    if (!selectedProduct) {
      return;
    }

    let cancelled = false;
    setStocks([]);
    setSelectedSkuId('');

    listStocks(selectedProduct.productCode)
      .then((nextStocks) => {
        if (cancelled) {
          return;
        }
        setStocks(nextStocks);
        setSelectedSkuId(nextStocks[0]?.skuId ?? '');
        setCouponQuote(null);
        setCouponQuoteError(null);
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        setMessage(errorMessage(error));
      });

    return () => {
      cancelled = true;
    };
  }, [selectedProduct]);

  useEffect(() => {
    if (!createdOrder?.orderId) {
      return;
    }

    let cancelled = false;
    let intervalId: number | undefined;
    let failureCount = 0;

    const stopPolling = () => {
      if (intervalId) {
        window.clearInterval(intervalId);
      }
    };

    const refresh = () => {
      getOrder(createdOrder.orderId)
        .then((detail) => {
          if (cancelled) {
            return;
          }
          failureCount = 0;
          setOrderDetail(detail);
          if (terminalSagaStatuses.has(detail.sagaStatus)) {
            stopPolling();
          }
        })
        .catch((error) => {
          if (cancelled) {
            return;
          }
          failureCount += 1;
          if (failureCount >= maxStatusPollingFailures) {
            stopPolling();
            setMessage(`상태 조회가 반복 실패해 자동 조회를 중단했습니다. ${errorMessage(error)}`);
            return;
          }
          setMessage(errorMessage(error));
        });
    };

    refresh();
    intervalId = window.setInterval(refresh, 2000);

    return () => {
      cancelled = true;
      if (intervalId) {
        window.clearInterval(intervalId);
      }
    };
  }, [createdOrder?.orderId]);

  const selectedStock = useMemo(
    () => stocks.find((stock) => stock.skuId === selectedSkuId) ?? null,
    [selectedSkuId, stocks],
  );

  const parsedQuantity = Number(quantityInput);
  const quantityIsValid = Number.isInteger(parsedQuantity) && parsedQuantity >= 1;
  const totalAmount = selectedProduct && quantityIsValid ? selectedProduct.listPrice * parsedQuantity : 0;
  const hasBlockedCouponState = Boolean(couponQuoteError) || couponQuote?.applied === false;
  const canSubmitOrder =
    Boolean(selectedProduct) &&
    Boolean(selectedStock) &&
    quantityIsValid &&
    memberId.trim().length > 0 &&
    Boolean(authToken) &&
    !submitting &&
    !applyingCoupon &&
    !hasBlockedCouponState;
  const discountAmount = couponQuote?.applied ? couponQuote.discountAmount : 0;
  const payableAmount = couponQuote?.applied ? couponQuote.payAmount : totalAmount;
  const couponStatusLabel = couponQuote ? (couponQuote.applied ? 'APPLIED' : couponQuote.reason) : '미적용';

  useEffect(() => {
    setCouponQuote(null);
    setCouponQuoteError(null);
  }, [quantityInput]);

  const applyCouponQuote = async () => {
    const trimmedCouponCode = couponCode.trim();
    if (!selectedProduct || !quantityIsValid || trimmedCouponCode.length === 0 || totalAmount <= 0) {
      setCouponQuoteError('주문 항목과 쿠폰 코드를 확인하세요.');
      return;
    }

    setApplyingCoupon(true);
    setCouponQuoteError(null);

    try {
      const nextQuote = await quoteCoupon({
        couponCode: trimmedCouponCode,
        orderAmount: totalAmount,
      });
      setCouponQuote(nextQuote);
    } catch (error) {
      setCouponQuoteError(`쿠폰 적용 실패: ${errorMessage(error)}`);
      setCouponQuote(null);
    } finally {
      setApplyingCoupon(false);
    }
  };

  const submitOrder = async () => {
    if (!selectedProduct || !selectedStock || !quantityIsValid || memberId.trim().length === 0) {
      setMessage('상품, SKU, 수량, 회원 ID를 확인하세요.');
      return;
    }
    if (!authToken) {
      setMessage('로그인 후 주문을 진행하세요.');
      return;
    }
    if (hasBlockedCouponState) {
      setMessage('쿠폰 적용 상태를 확인하세요.');
      return;
    }

    setSubmitting(true);
    setMessage(null);

    try {
      const payload = {
        memberId: memberId.trim(),
        paymentMethod,
        items: [
          {
            productCode: selectedProduct.productCode,
            skuId: selectedStock.skuId,
            quantity: parsedQuantity,
            unitPrice: selectedProduct.listPrice,
          },
        ],
        couponCode: couponCode.trim() || undefined,
      };

      const order = await createOrder(payload);
      setCreatedOrder(order);
      setOrderDetail(null);
      setAuthToken(getAuthToken());
    } catch (error) {
      setCreatedOrder(null);
      setOrderDetail(null);
      setMessage(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  const login = async () => {
    try {
      await startLogin();
    } catch (error) {
      setMessage(errorMessage(error));
    }
  };

  const logout = () => {
    clearAuthSession();
    setAuthToken(null);
  };

  return (
    <main className="app-shell">
      <header className="top-bar">
        <div>
          <p className="eyebrow">StockRush</p>
          <h1>한정 상품 주문</h1>
        </div>
        <div className="auth-panel" aria-label="인증 상태">
          <p>인증 상태: {authToken ? '인증됨' : '미인증'}</p>
          <button type="button" className="auth-action" onClick={authToken ? logout : login}>
            {authToken ? '로그아웃' : '로그인'}
          </button>
        </div>
        <div className="runtime-note">Catalog · Inventory · Order</div>
      </header>

      {message && (
        <div className="error-banner" role="alert">
          {message}
        </div>
      )}

      <section className="workspace" aria-label="고객 주문 흐름">
        <section className="catalog-pane" aria-label="상품 목록">
          <div className="section-heading">
            <h2>상품</h2>
            <span>{productsState === 'loading' ? '불러오는 중' : `${products.length}개`}</span>
          </div>
          <label className="field">
            <span>상품 검색</span>
            <input
              value={productQuery}
              onChange={(event) => setProductQuery(event.target.value)}
              placeholder="상품명 또는 코드 검색"
            />
          </label>
          <div className="product-list">
            {products.map((product) => (
              <button
                className={selectedProduct?.productCode === product.productCode ? 'product-row selected' : 'product-row'}
                key={product.productCode}
                onClick={() => setSelectedProduct(product)}
                type="button"
              >
                <span>
                  <strong>{product.name}</strong>
                  <small>{product.productCode}</small>
                </span>
                <span className="price">{formatCurrency(product.listPrice)}</span>
              </button>
            ))}
          </div>
        </section>

        <section className="checkout-pane" aria-label="주문 생성">
          <div className="section-heading">
            <h2>주문</h2>
            <span>{selectedProduct ? selectedProduct.status : '상품 선택 필요'}</span>
          </div>

          {selectedProduct ? (
            <>
              <div className="summary-row">
                <span>선택 상품</span>
                <strong>{selectedProduct.name}</strong>
              </div>

              <label className="field">
                <span>SKU</span>
                <select value={selectedSkuId} onChange={(event) => setSelectedSkuId(event.target.value)}>
                  {stocks.map((stock) => (
                    <option key={stock.skuId} value={stock.skuId}>
                      {stock.skuId}
                    </option>
                  ))}
                </select>
              </label>

              {selectedStock && (
                <div className="stock-strip">
                  <span>선택됨</span>
                  <span>가용 {selectedStock.availableQuantity}</span>
                  <span>예약 {selectedStock.reservedQuantity}</span>
                </div>
              )}

              <label className="field">
                <span>수량</span>
                <input
                  min={1}
                  inputMode="numeric"
                  type="number"
                  value={quantityInput}
                  onChange={(event) => setQuantityInput(event.target.value)}
                />
              </label>

              <label className="field">
                <span>회원 ID</span>
                <input value={memberId} onChange={(event) => setMemberId(event.target.value)} placeholder="member-1" />
              </label>

              <label className="field">
                <span>쿠폰 코드</span>
                <input
                  value={couponCode}
                  onChange={(event) => {
                    setCouponCode(event.target.value);
                    setCouponQuote(null);
                    setCouponQuoteError(null);
                  }}
                  placeholder="WELCOME10"
                />
              </label>

              <button
                className="primary-action"
                disabled={applyingCoupon || couponCode.trim().length === 0 || !selectedProduct || totalAmount <= 0}
                onClick={applyCouponQuote}
                type="button"
              >
                {applyingCoupon ? '쿠폰 적용 중' : '쿠폰 적용'}
              </button>

              {couponQuote && (
                <div className="summary-row">
                  <span>쿠폰 적용</span>
                  <strong>{`쿠폰 적용: ${couponStatusLabel}`}</strong>
                </div>
              )}

              {couponQuoteError && (
                <div className="error-banner" role="alert">
                  {couponQuoteError}
                </div>
              )}

              <label className="field">
                <span>결제수단</span>
                <select value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value)}>
                  <option value="CARD">CARD</option>
                  <option value="FAIL_CARD">FAIL_CARD</option>
                  <option value="DELAY_CARD">DELAY_CARD</option>
                </select>
              </label>

              <div className="summary-row total">
                <span>주문 금액</span>
                <strong>{formatCurrency(totalAmount)}</strong>
              </div>
              <div className="summary-row total">
                <span>할인 금액</span>
                <strong>{formatCurrency(discountAmount)}</strong>
              </div>
              <div className="summary-row total">
                <span>결제 예정 금액</span>
                <strong>{formatCurrency(payableAmount)}</strong>
              </div>

              <button className="primary-action" disabled={!canSubmitOrder} onClick={submitOrder} type="button">
                {submitting ? '처리 중' : '주문 생성'}
              </button>
            </>
          ) : (
            <p className="empty-note">상품을 선택하면 재고와 주문 입력을 확인할 수 있습니다.</p>
          )}
        </section>

        <section className="status-pane" aria-label="주문 상태" aria-live="polite" role="region">
          <div className="section-heading">
            <h2>상태</h2>
            <span>{createdOrder ? '추적 중' : '대기'}</span>
          </div>

          {createdOrder ? (
            <>
              <div className="status-grid">
                <div>
                  <span>주문번호</span>
                  <strong>{createdOrder.orderId}</strong>
                </div>
                <div>
                  <span>주문 상태</span>
                  <strong className={statusClass(orderDetail?.status ?? createdOrder.status)}>
                    {orderDetail?.status ?? createdOrder.status}
                  </strong>
                </div>
                <div>
                  <span>Saga 상태</span>
                  <strong className={statusClass(orderDetail?.sagaStatus ?? createdOrder.sagaStatus)}>
                    {orderDetail?.sagaStatus ?? createdOrder.sagaStatus}
                  </strong>
                </div>
                <div>
                  <span>결제수단</span>
                  <strong>{orderDetail?.paymentMethod ?? createdOrder.paymentMethod}</strong>
                </div>
              </div>

              <div className="order-lines">
                {(orderDetail?.items ?? []).map((item) => (
                  <div className="order-line" key={`${item.productCode}-${item.skuId}`}>
                    <span>{item.skuId}</span>
                    <span>{item.quantity}개</span>
                    <strong>{formatCurrency(item.lineAmount)}</strong>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <p className="empty-note">주문 생성 후 진행 상태가 표시됩니다.</p>
          )}
        </section>
      </section>
    </main>
  );
}

function statusClass(status: string): string {
  if (status === 'COMPLETED' || status === 'CONFIRMED') {
    return 'status-success';
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'status-failed';
  }
  return 'status-active';
}
