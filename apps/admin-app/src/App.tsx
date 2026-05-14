import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { ApiClientError } from './api/client';
import {
  cancelDelayedOrder,
  createCatalogProduct,
  getOrderSaga,
  listCatalogProducts,
  listOutbox,
  listRecentOrders,
  listStocksByProductCode,
  requeueFailedOutbox,
  retryOutbox,
  setStockQuantity,
  updateCatalogProduct,
  type ServiceDomain,
} from './api/admin';
import type {
  AdminOrderSaga,
  AdminOrderSummary,
  CatalogProduct,
  OutboxEvent,
  OutboxRequeueResult,
  OutboxRetryResult,
  ProductCreatePayload,
  ProductUpdatePayload,
  SalesStatus,
  StockItem,
  StockSetPayload,
} from './types/admin';

type TabId = 'orders' | 'outbox' | 'products';
type LoadState = 'idle' | 'loading' | 'ready' | 'error';
type SubmitState = 'idle' | 'loading' | 'ready' | 'error';
type ProductSubmitMode = 'create' | 'update';

const SALES_STATUS_OPTIONS = ['ON_SALE', 'STOPPED'] as const;

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError && error.apiError) {
    return `${error.apiError.code}: ${error.apiError.message}`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return '요청 처리에 실패했습니다.';
}

function formatTime(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(iso));
}

function emptyIfNull(value: string | null | undefined): string {
  return value?.trim() ? value : '-';
}

function moneyValue(value: number): string {
  return `₩${value.toLocaleString()}`;
}

function normalizeSalesStatus(value: string): SalesStatus {
  return value === 'STOPPED' ? 'STOPPED' : 'ON_SALE';
}

function statusClass(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '_');
}

function randomTextId(prefix: string): string {
  const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
  return `${prefix}-${suffix}`;
}

function emptyListMessage(state: LoadState): string {
  if (state === 'loading') {
    return '불러오는 중';
  }
  if (state === 'ready') {
    return '조회 결과가 없습니다.';
  }
  if (state === 'error') {
    return '조회 실패';
  }
  return '상태 대기';
}

function EventRow({ event }: { event: OutboxEvent }) {
  return (
    <tr>
      <td>{emptyIfNull(event.eventId)}</td>
      <td>{emptyIfNull(event.aggregateType)}</td>
      <td>{emptyIfNull(event.aggregateId)}</td>
      <td>{emptyIfNull(event.eventType)}</td>
      <td className={`status-pill ${statusClass(event.status)}`}>{event.status}</td>
      <td>
        {event.retryCount} / {event.maxRetryCount}
      </td>
      <td>{emptyIfNull(event.errorMessage)}</td>
      <td>{emptyIfNull(event.nextRetryAt)}</td>
      <td>{formatTime(event.createdAt)}</td>
      <td>{event.publishedAt ? formatTime(event.publishedAt) : '-'}</td>
    </tr>
  );
}

function OrdersTab() {
  const [orders, setOrders] = useState<AdminOrderSummary[]>([]);
  const [ordersState, setOrdersState] = useState<LoadState>('idle');
  const [orderError, setOrderError] = useState<string | null>(null);

  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
  const [saga, setSaga] = useState<AdminOrderSaga | null>(null);
  const [sagaState, setSagaState] = useState<LoadState>('idle');
  const [sagaError, setSagaError] = useState<string | null>(null);
  const [cancelState, setCancelState] = useState<SubmitState>('idle');
  const [cancelMessage, setCancelMessage] = useState<string | null>(null);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const cancelRequestKeyRef = useRef<{ orderId: string; key: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setOrdersState('loading');
    setOrderError(null);

    listRecentOrders()
      .then((response) => {
        if (cancelled) {
          return;
        }
        setOrders(response.items);
        setOrdersState('ready');
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        setOrdersState('error');
        setOrderError(errorMessage(error));
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!selectedOrderId && orders.length > 0) {
      setSelectedOrderId(orders[0].orderId);
    }
  }, [orders, selectedOrderId]);

  useEffect(() => {
    cancelRequestKeyRef.current = null;
    setCancelState('idle');
    setCancelMessage(null);
    setCancelError(null);
  }, [selectedOrderId]);

  useEffect(() => {
    if (!selectedOrderId) {
      return;
    }

    let cancelled = false;
    setSaga(null);
    setSagaState('loading');
    setSagaError(null);

    getOrderSaga(selectedOrderId)
      .then((nextSaga) => {
        if (cancelled) {
          return;
        }
        setSaga(nextSaga);
        setSagaState('ready');
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        setSagaState('error');
        setSagaError(errorMessage(error));
      });

    return () => {
      cancelled = true;
    };
  }, [selectedOrderId]);

  const selectedOrder = useMemo(
    () => orders.find((order) => order.orderId === selectedOrderId) ?? null,
    [orders, selectedOrderId],
  );
  const canRequestCancel = selectedOrder?.status === 'CREATED' && selectedOrder.sagaStatus === 'PAYMENT_DELAYED';

  const onCancelDelayedOrder = () => {
    if (!selectedOrder || !canRequestCancel) {
      return;
    }

    const targetOrderId = selectedOrder.orderId;
    const idempotencyKey =
      cancelRequestKeyRef.current?.orderId === targetOrderId
        ? cancelRequestKeyRef.current.key
        : randomTextId('admin-order-cancel');
    cancelRequestKeyRef.current = { orderId: targetOrderId, key: idempotencyKey };
    setCancelState('loading');
    setCancelMessage(null);
    setCancelError(null);

    cancelDelayedOrder(targetOrderId, idempotencyKey)
      .then((result) => {
        setOrders((previous) =>
          previous.map((order) =>
            order.orderId === result.orderId
              ? {
                  ...order,
                  status: result.status,
                  sagaStatus: result.sagaStatus,
                }
              : order,
          ),
        );
        setSaga((previous) =>
          previous && previous.orderId === result.orderId
            ? {
                ...previous,
                orderStatus: result.status,
                sagaStatus: result.sagaStatus,
              }
            : previous,
        );
        setCancelState('ready');
        setCancelMessage(`${result.orderId} 결제 취소 요청이 접수되었습니다.`);
        cancelRequestKeyRef.current = null;
      })
      .catch((error) => {
        setCancelState('error');
        setCancelError(errorMessage(error));
      });
  };

  return (
    <section className="panel-shell">
      {orderError && (
        <p className="error-banner" role="alert">
          {orderError}
        </p>
      )}

      <div className="panel-grid">
        <section className="panel" aria-label="주문 목록">
          <h2>최근 주문</h2>
          <p className="panel-meta">
            {ordersState === 'loading' ? '불러오는 중' : ordersState === 'ready' ? `총 ${orders.length}건` : '상태 대기'}
          </p>

          {ordersState === 'error' && <p className="empty-message">목록을 읽지 못했습니다.</p>}

          <div className="order-list">
            {orders.map((order) => (
              <button
                type="button"
                key={order.orderId}
                className={order.orderId === selectedOrderId ? 'order-item selected' : 'order-item'}
                onClick={() => setSelectedOrderId(order.orderId)}
              >
                <div>
                  <strong>{order.orderId}</strong>
                  <span>{order.memberId}</span>
                </div>
                <div className="muted">
                  <span>{order.status}</span>
                  <span>{order.sagaStatus}</span>
                </div>
              </button>
            ))}
          </div>
        </section>

        <section className="panel" aria-label="주문 상세 상태">
          <h2>Saga 상세</h2>
          <p className="panel-meta">
            {sagaState === 'loading' && selectedOrderId
              ? `${selectedOrderId} 상태 조회 중`
              : selectedOrder
                ? `${selectedOrder.orderId} 요약`
                : '주문을 선택하세요'}
          </p>

          {sagaError && (
            <p className="error-banner" role="alert">
              {sagaError}
            </p>
          )}

          {!selectedOrder ? (
            <p className="empty-message">주문 선택이 필요합니다.</p>
          ) : (
            <div className="detail-grid">
              <div>
                <span>회원</span>
                <strong>{selectedOrder.memberId}</strong>
              </div>
              <div>
                <span>주문 상태</span>
                <strong>{selectedOrder.status}</strong>
              </div>
              <div>
                <span>Saga 상태</span>
                <strong>{selectedOrder.sagaStatus}</strong>
              </div>
              <div>
                <span>결제수단</span>
                <strong>{selectedOrder.paymentMethod}</strong>
              </div>
              <div>
                <span>총액</span>
                <strong>{moneyValue(selectedOrder.totalAmount)}</strong>
              </div>
              <div>
                <span>항목 수</span>
                <strong>{selectedOrder.itemCount}</strong>
              </div>
              <div>
                <span>생성 시각</span>
                <strong>{formatTime(selectedOrder.createdAt)}</strong>
              </div>
              <div>
                <span>수정 시각</span>
                <strong>{formatTime(selectedOrder.updatedAt)}</strong>
              </div>
            </div>
          )}

          {canRequestCancel && (
            <button
              type="button"
              className="action-btn"
              onClick={onCancelDelayedOrder}
              disabled={cancelState === 'loading'}
            >
              {cancelState === 'loading' ? '취소 요청 중' : '결제 취소 요청'}
            </button>
          )}
          {cancelMessage && <p className="success-banner">{cancelMessage}</p>}
          {cancelError && (
            <p className="error-banner" role="alert">
              {cancelError}
            </p>
          )}

          <hr />
          <h3>Saga 상세 값</h3>
          {saga ? (
            <div className="detail-grid">
              <div>
                <span>실패 시각</span>
                <strong>{saga.failedAt ? formatTime(saga.failedAt) : '-'}</strong>
              </div>
              <div>
                <span>마지막 이벤트</span>
                <strong>{emptyIfNull(saga.lastEventType)}</strong>
              </div>
              <div>
                <span>실패 사유</span>
                <strong>{emptyIfNull(saga.businessReason)}</strong>
              </div>
              <div>
                <span>기술 오류</span>
                <strong className="mono">{emptyIfNull(saga.technicalErrorMessage)}</strong>
              </div>
              <div>
                <span>발행 시도</span>
                <strong>{saga.outboxAttempts}</strong>
              </div>
              <div>
                <span>상세 상태</span>
                <strong>{emptyIfNull(saga.orderStatus)}</strong>
              </div>
            </div>
          ) : (
            <p className="empty-message">
              {sagaState === 'loading' ? '상세를 불러오는 중입니다.' : '선택한 주문의 Saga 상세가 여기에 표시됩니다.'}
            </p>
          )}
        </section>
      </div>
    </section>
  );
}

function OutboxTab() {
  const [service, setService] = useState<ServiceDomain>('order');
  const [events, setEvents] = useState<OutboxEvent[]>([]);
  const [outboxState, setOutboxState] = useState<LoadState>('idle');
  const [outboxError, setOutboxError] = useState<string | null>(null);
  const [retryState, setRetryState] = useState<'idle' | 'loading' | 'ready'>('idle');
  const [retryResult, setRetryResult] = useState<OutboxRetryResult | null>(null);
  const [retryError, setRetryError] = useState<string | null>(null);
  const [requeueState, setRequeueState] = useState<'idle' | 'loading' | 'ready'>('idle');
  const [requeueResult, setRequeueResult] = useState<OutboxRequeueResult | null>(null);
  const [requeueError, setRequeueError] = useState<string | null>(null);

  const loadOutbox = () => {
    setOutboxState('loading');
    setOutboxError(null);

    listOutbox(service)
      .then((response) => {
        setEvents(response.items);
        setOutboxState('ready');
      })
      .catch((error) => {
        setOutboxState('error');
        setOutboxError(errorMessage(error));
      });
  };

  useEffect(() => {
    setRetryResult(null);
    setRetryError(null);
    setRetryState('idle');
    setRequeueResult(null);
    setRequeueError(null);
    setRequeueState('idle');
    loadOutbox();
  }, [service]);

  const onRetry = async () => {
    setRetryState('loading');
    setRetryResult(null);
    setRetryError(null);
    setRequeueResult(null);
    setRequeueError(null);

    retryOutbox(service, 10)
      .then((result) => {
        setRetryResult(result);
        setRetryState('ready');
        return loadOutbox();
      })
      .catch((error) => {
        setRetryState('idle');
        setRetryError(errorMessage(error));
      });
  };

  const onRequeueFailed = async () => {
    setRequeueState('loading');
    setRequeueResult(null);
    setRequeueError(null);
    setRetryResult(null);
    setRetryError(null);

    requeueFailedOutbox(service, 10)
      .then((result) => {
        setRequeueResult(result);
        setRequeueState('ready');
        return loadOutbox();
      })
      .catch((error) => {
        setRequeueState('idle');
        setRequeueError(errorMessage(error));
      });
  };

  return (
    <section className="panel-shell">
      <section className="panel">
        <div className="panel-head">
          <h2>Outbox 운영</h2>
          <label className="service-select">
            <span>서비스</span>
            <select value={service} onChange={(event) => setService(event.target.value as ServiceDomain)}>
              <option value="order">order</option>
              <option value="inventory">inventory</option>
              <option value="payment">payment</option>
            </select>
          </label>
        </div>

        <p className="panel-meta">
          {outboxState === 'loading'
            ? `${service} 이벤트 조회 중`
            : outboxState === 'ready'
              ? `${service} pending/failed: ${events.length}건`
              : '대기 중'}
        </p>

        {(outboxError || retryError || requeueError) && (
          <p className="error-banner" role="alert">
            {outboxError ?? retryError ?? requeueError}
          </p>
        )}
        {retryResult && (
          <p className="success-banner" aria-live="polite">
            {retryResult.claimed}건 claim, {retryResult.published}건 publish, {retryResult.failed}건 fail
          </p>
        )}
        {requeueResult && (
          <p className="success-banner" aria-live="polite">
            {requeueResult.updated}건 requeue
          </p>
        )}

        <div className="action-row">
          <button type="button" className="action-btn" onClick={() => onRetry()} disabled={retryState === 'loading'}>
            {retryState === 'loading' ? '재시도 진행 중' : '선택한 서비스 재시도'}
          </button>
          <button
            type="button"
            className="action-btn secondary"
            onClick={() => onRequeueFailed()}
            disabled={requeueState === 'loading'}
          >
            {requeueState === 'loading' ? '재처리 준비 중' : '실패 이벤트 재처리 준비'}
          </button>
        </div>

        {outboxState === 'error' ? (
          <p className="empty-message">Outbox 목록을 불러오지 못했습니다.</p>
        ) : (
          <>
            <table className="events-table">
              <thead>
                <tr>
                  <th>이벤트 ID</th>
                  <th>도메인</th>
                  <th>집계 ID</th>
                  <th>타입</th>
                  <th>상태</th>
                  <th>재시도</th>
                  <th>오류</th>
                  <th>다음시도</th>
                  <th>생성</th>
                  <th>발행</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event) => (
                  <EventRow key={event.eventId} event={event} />
                ))}
              </tbody>
            </table>
            {events.length === 0 && outboxState === 'ready' ? (
              <p className="empty-message">조건에 맞는 이벤트가 없습니다.</p>
            ) : null}
          </>
        )}
      </section>
    </section>
  );
}

function CatalogTab() {
  const [products, setProducts] = useState<CatalogProduct[]>([]);
  const [productsState, setProductsState] = useState<LoadState>('idle');
  const [productsError, setProductsError] = useState<string | null>(null);

  const [selectedProductCode, setSelectedProductCode] = useState<string | null>(null);
  const [isCreatingProduct, setIsCreatingProduct] = useState(false);
  const [productCodeInput, setProductCodeInput] = useState('');
  const [productNameInput, setProductNameInput] = useState('');
  const [salesStatusInput, setSalesStatusInput] = useState<SalesStatus>('ON_SALE');
  const [listPriceInput, setListPriceInput] = useState('');
  const [productSubmitState, setProductSubmitState] = useState<SubmitState>('idle');
  const [productSubmitError, setProductSubmitError] = useState<string | null>(null);
  const [productSubmitMessage, setProductSubmitMessage] = useState<string | null>(null);

  const [stockProductCode, setStockProductCode] = useState('');
  const [stocksState, setStocksState] = useState<LoadState>('idle');
  const [stocks, setStocks] = useState<StockItem[]>([]);
  const [stocksError, setStocksError] = useState<string | null>(null);
  const [skuIdInput, setSkuIdInput] = useState('');
  const [stockProductCodeInput, setStockProductCodeInput] = useState('');
  const [stockQuantityInput, setStockQuantityInput] = useState('');
  const [stockSubmitState, setStockSubmitState] = useState<SubmitState>('idle');
  const [stockSubmitError, setStockSubmitError] = useState<string | null>(null);
  const [stockSubmitMessage, setStockSubmitMessage] = useState<string | null>(null);
  const isMountedRef = useRef(true);

  const productListRequestId = useRef(0);
  const stockListRequestId = useRef(0);
  const productSubmitModeRef = useRef<ProductSubmitMode>('create');
  const productSubmitPayloadRef = useRef('');
  const productSubmitKeyRef = useRef('');

  const selectedProduct = useMemo(
    () => products.find((product) => product.productCode === selectedProductCode) ?? null,
    [selectedProductCode, products],
  );

  useEffect(() => {
    isMountedRef.current = true;

    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const resetProductSubmitDraft = () => {
    productSubmitModeRef.current = 'create';
    productSubmitPayloadRef.current = '';
    productSubmitKeyRef.current = '';
  };

  const loadProducts = () => {
    const requestId = ++productListRequestId.current;
    setProductsState('loading');
    setProductsError(null);

    listCatalogProducts('ON_SALE')
      .then((response) => {
        if (!isMountedRef.current || requestId !== productListRequestId.current) {
          return;
        }

        setProducts(response);
        setProductsState('ready');
      })
      .catch((error) => {
        if (!isMountedRef.current || requestId !== productListRequestId.current) {
          return;
        }

        setProductsState('error');
        setProductsError(errorMessage(error));
      });
  };

  useEffect(() => {
    loadProducts();
  }, []);

  useEffect(() => {
    if (!selectedProduct && products.length > 0 && !isCreatingProduct) {
      const next = products[0].productCode;
      setSelectedProductCode(next);
      setStockProductCode(next);
      return;
    }

    if (!selectedProduct) {
      return;
    }

    setProductCodeInput(selectedProduct.productCode);
    setProductNameInput(selectedProduct.name);
    setSalesStatusInput(normalizeSalesStatus(selectedProduct.status));
    setListPriceInput(String(selectedProduct.listPrice));
  }, [selectedProduct, products, isCreatingProduct]);

  const onChangeSelectedProduct = (product: CatalogProduct | null) => {
    resetProductSubmitDraft();
    if (!product) {
      setSelectedProductCode(null);
      setIsCreatingProduct(true);
      setProductCodeInput('');
      setProductNameInput('');
      setSalesStatusInput('ON_SALE');
      setListPriceInput('');
      return;
    }

    setSelectedProductCode(product.productCode);
    setProductCodeInput(product.productCode);
    setProductNameInput(product.name);
    setSalesStatusInput(normalizeSalesStatus(product.status));
    setListPriceInput(String(product.listPrice));
    setIsCreatingProduct(false);
    setStockProductCode(product.productCode);
    setProductSubmitError(null);
    setProductSubmitMessage(null);
  };

  const onSubmitProduct = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const listPrice = Number(listPriceInput);
    if (!productCodeInput.trim() || !productNameInput.trim() || !Number.isFinite(listPrice)) {
      setProductSubmitState('error');
      setProductSubmitError('상품코드, 상품명, 판매가격을 모두 입력하세요.');
      return;
    }

    if (listPrice <= 0) {
      setProductSubmitState('error');
      setProductSubmitError('판매가격은 0보다 커야 합니다.');
      return;
    }

    const isUpdating = selectedProduct !== null;
    const requestMode: ProductSubmitMode = isUpdating ? 'update' : 'create';
    const submitPayload =
      requestMode === 'update'
        ? {
            productCode: selectedProductCode,
            name: productNameInput.trim(),
            salesStatus: salesStatusInput,
            listPrice,
          }
        : {
            productCode: productCodeInput.trim(),
            name: productNameInput.trim(),
            salesStatus: salesStatusInput,
            listPrice,
          };

    const submitFingerprint = `${requestMode}:${JSON.stringify(submitPayload)}`;
    const idempotencyKey =
      productSubmitModeRef.current === requestMode &&
      productSubmitPayloadRef.current === submitFingerprint &&
      productSubmitKeyRef.current
        ? productSubmitKeyRef.current
        : randomTextId('catalog');

    productSubmitModeRef.current = requestMode;
    productSubmitPayloadRef.current = submitFingerprint;
    productSubmitKeyRef.current = idempotencyKey;
    setProductSubmitState('loading');
    setProductSubmitError(null);
    setProductSubmitMessage(null);

    try {
      let nextSubmitMessage: string;

      if (isUpdating && selectedProductCode) {
        const payload: ProductUpdatePayload = {
          name: productNameInput.trim(),
          salesStatus: salesStatusInput,
          listPrice,
        };

        const updated = await updateCatalogProduct(selectedProductCode, payload, idempotencyKey);
        nextSubmitMessage = `${updated.productCode} 상품 수정이 완료되었습니다.`;
      } else {
        const payload: ProductCreatePayload = {
          productCode: productCodeInput.trim(),
          name: productNameInput.trim(),
          salesStatus: salesStatusInput,
          listPrice,
        };

        const created = await createCatalogProduct(payload, idempotencyKey);
        nextSubmitMessage = `${created.productCode} 상품 등록이 완료되었습니다.`;
      }

      if (!isMountedRef.current) {
        productSubmitModeRef.current = 'create';
        productSubmitPayloadRef.current = '';
        productSubmitKeyRef.current = '';
        return;
      }

      setProductSubmitMessage(nextSubmitMessage);
      setProductSubmitState('ready');
      setProductsState('idle');
      await loadProducts();

      productSubmitModeRef.current = 'create';
      productSubmitPayloadRef.current = '';
      productSubmitKeyRef.current = '';

      if (!isMountedRef.current) {
        return;
      }

      setProductCodeInput('');
      setProductNameInput('');
      setSalesStatusInput('ON_SALE');
      setListPriceInput('');
      setSelectedProductCode(null);
      setIsCreatingProduct(false);
    } catch (error) {
      if (!isMountedRef.current) {
        return;
      }

      setProductSubmitState('error');
      setProductSubmitError(errorMessage(error));
    }
  };

  const loadStocks = async () => {
    const requestId = ++stockListRequestId.current;
    if (!stockProductCode.trim()) {
      setStocksError('상품코드를 입력하거나 상품을 선택해 주세요.');
      setStocksState('error');
      return;
    }

    setStocksState('loading');
    setStocksError(null);
    setStockSubmitMessage(null);
    setStocks([]);
    setStockSubmitState('idle');
    setStockSubmitError(null);

    try {
      const response = await listStocksByProductCode(stockProductCode.trim());

      if (!isMountedRef.current || requestId !== stockListRequestId.current) {
        return;
      }

      setStocks(response);
      setStocksState('ready');
    } catch (error) {
      if (!isMountedRef.current || requestId !== stockListRequestId.current) {
        return;
      }

      setStocksState('error');
      setStocksError(errorMessage(error));
    }
  };

  const fillStockFormFromItem = (stock: StockItem) => {
    setSkuIdInput(stock.skuId);
    setStockProductCodeInput(stock.productCode);
    setStockQuantityInput(String(stock.availableQuantity));
    setStockSubmitError(null);
    setStockSubmitMessage(null);
  };

  const onSubmitStock = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const trimmedStockQuantity = stockQuantityInput.trim();
    const availableQuantity = Number(trimmedStockQuantity);

    if (!skuIdInput.trim() || !stockProductCodeInput.trim() || !trimmedStockQuantity || !Number.isFinite(availableQuantity)) {
      setStockSubmitState('error');
      setStockSubmitError('SKU, 상품코드, 재고 수량을 모두 입력하세요.');
      return;
    }

    if (availableQuantity < 0 || !Number.isInteger(availableQuantity)) {
      setStockSubmitState('error');
      setStockSubmitError('재고 수량은 0 이상의 정수여야 합니다.');
      return;
    }

    const payload: StockSetPayload = {
      productCode: stockProductCodeInput.trim(),
      availableQuantity,
    };

    setStockSubmitState('loading');
    setStockSubmitError(null);
    setStockSubmitMessage(null);

    try {
      const updated = await setStockQuantity(skuIdInput, payload);
      if (!isMountedRef.current) {
        return;
      }

      setStocks((prev) => {
        const nextIndex = prev.findIndex((stock) => stock.skuId === updated.skuId);
        if (nextIndex === -1) {
          return [...prev, updated];
        }

        return prev.map((stock) => (stock.skuId === updated.skuId ? updated : stock));
      });
      setStockSubmitState('ready');
      setStockSubmitMessage(`${updated.skuId} 재고가 ${updated.availableQuantity}개로 저장되었습니다.`);
    } catch (error) {
      if (!isMountedRef.current) {
        return;
      }

      setStockSubmitState('error');
      setStockSubmitError(errorMessage(error));
    }
  };

  return (
    <section className="panel-shell">
      <section className="panel-grid">
        <section className="panel" aria-label="상품 목록">
          <div className="panel-head">
            <h2>상품 목록</h2>
            <p className="panel-meta">{emptyListMessage(productsState)}</p>
          </div>
          {(productsState === 'error' || productsError) && (
            <p className="error-banner" role="alert">
              {productsError}
            </p>
          )}
          {productsState === 'loading' ? (
            <p className="empty-message">상품을 조회 중입니다.</p>
          ) : (
            <>
              <table className="events-table">
                <thead>
                  <tr>
                    <th>상품코드</th>
                    <th>상품명</th>
                    <th>상태</th>
                    <th>판매가</th>
                  </tr>
                </thead>
                <tbody>
                  {products.map((product) => (
                    <tr key={product.productCode}>
                      <td>
                        <button
                          type="button"
                          className={product.productCode === selectedProductCode ? 'catalog-item selected' : 'catalog-item'}
                          onClick={() => onChangeSelectedProduct(product)}
                        >
                          {product.productCode}
                        </button>
                      </td>
                      <td>{product.name}</td>
                      <td>
                        <span className={`status-pill ${statusClass(product.status)}`}>{product.status}</span>
                      </td>
                      <td>{moneyValue(product.listPrice)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {products.length === 0 && productsState === 'ready' ? (
                <p className="empty-message">ON_SALE 상품이 없습니다.</p>
              ) : null}
            </>
          )}
        </section>

        <section className="panel" aria-label="상품 등록 및 수정">
          <h2>상품 등록 / 수정</h2>
          <p className="panel-meta">
            {productSubmitState === 'ready'
              ? '처리가 완료되었습니다.'
              : selectedProduct
                ? `${selectedProduct.productCode} 선택됨`
                : '상품을 선택하거나 새로 등록하세요'}
          </p>

          {productSubmitError && (
            <p className="error-banner" role="alert">
              {productSubmitError}
            </p>
          )}

          <form className="form-grid" onSubmit={onSubmitProduct} noValidate>
            <label className="form-field">
              <span>상품코드(등록/수정)</span>
              <input
                value={productCodeInput}
                onChange={(event) => setProductCodeInput(event.target.value)}
                placeholder="예: BAG-001"
                readOnly={selectedProduct !== null}
              />
            </label>
            <label className="form-field">
              <span>상품명</span>
              <input
                value={productNameInput}
                onChange={(event) => setProductNameInput(event.target.value)}
                placeholder="예: premium bag"
              />
            </label>
            <label className="form-field">
              <span>판매 상태</span>
              <select
                value={salesStatusInput}
                onChange={(event) => setSalesStatusInput(event.target.value as SalesStatus)}
              >
                {SALES_STATUS_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label className="form-field">
              <span>가격</span>
              <input
                type="number"
                min="1"
                step="1"
                value={listPriceInput}
                onChange={(event) => setListPriceInput(event.target.value)}
                placeholder="예: 50000"
              />
            </label>
            <div className="form-actions">
              <button type="submit" className="action-btn" disabled={productSubmitState === 'loading'}>
                {productSubmitState === 'loading'
                  ? selectedProduct
                    ? '상품 수정 요청 중'
                    : '상품 등록 요청 중'
                  : selectedProduct
                    ? '상품 수정'
                    : '상품 등록'}
              </button>
              <button
                type="button"
                className="secondary-btn"
                onClick={() => onChangeSelectedProduct(null)}
                disabled={productSubmitState === 'loading'}
              >
                새로 등록
              </button>
            </div>
          </form>
          {productSubmitMessage && <p className="success-banner">{productSubmitMessage}</p>}

          <hr />

          <h2>재고 조회 / 설정</h2>
          <p className="panel-meta">{emptyListMessage(stocksState)}</p>
          {stocksError && (
            <p className="error-banner" role="alert">
              {stocksError}
            </p>
          )}
          {stockSubmitMessage && <p className="success-banner">{stockSubmitMessage}</p>}
          {stockSubmitError && (
            <p className="error-banner" role="alert">
              {stockSubmitError}
            </p>
          )}

          <form className="stock-search" onSubmit={(event) => {
            event.preventDefault();
            loadStocks();
          }}>
            <label className="form-field stock-search-field">
              <span>조회 상품코드</span>
              <input
                value={stockProductCode}
                onChange={(event) => setStockProductCode(event.target.value)}
                placeholder="상품코드 입력"
              />
            </label>
            <button type="submit" className="action-btn">
              재고 조회
            </button>
          </form>

          <div className="stock-results">
            <table className="events-table">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>상품코드</th>
                  <th>가능 수량</th>
                  <th>예약 수량</th>
                  <th>버전</th>
                  <th>동작</th>
                </tr>
              </thead>
              <tbody>
                {stocks.map((stock) => (
                  <tr key={stock.skuId}>
                    <td>{stock.skuId}</td>
                    <td>{stock.productCode}</td>
                    <td>{stock.availableQuantity}</td>
                    <td>{stock.reservedQuantity}</td>
                    <td>{stock.version}</td>
                    <td>
                      <button type="button" className="secondary-btn" onClick={() => fillStockFormFromItem(stock)}>
                        선택
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {stocks.length === 0 && stocksState === 'ready' ? (
              <p className="empty-message">재고 데이터가 없습니다.</p>
            ) : null}
          </div>

          <form className="stock-update" onSubmit={onSubmitStock} noValidate>
            <div className="form-grid">
              <label className="form-field">
                <span>SKU</span>
                <input value={skuIdInput} onChange={(event) => setSkuIdInput(event.target.value)} />
              </label>
              <label className="form-field">
                <span>재고 대상 상품코드</span>
                <input value={stockProductCodeInput} onChange={(event) => setStockProductCodeInput(event.target.value)} />
              </label>
              <label className="form-field">
                <span>가능 수량</span>
                <input
                  type="number"
                  min="0"
                  step="1"
                  value={stockQuantityInput}
                  onChange={(event) => setStockQuantityInput(event.target.value)}
                  placeholder="예: 10"
                />
              </label>
            </div>
            <div className="form-actions">
              <button type="submit" className="action-btn" disabled={stockSubmitState === 'loading'}>
                {stockSubmitState === 'loading' ? '재고 저장 중' : '재고 설정'}
              </button>
            </div>
          </form>

          {stocksState === 'loading' && <p className="empty-message">재고를 조회 중입니다.</p>}
          {stocksState === 'error' && <p className="empty-message">재고 조회를 실패했습니다.</p>}
        </section>
      </section>
    </section>
  );
}

export default function App() {
  const [tab, setTab] = useState<TabId>('orders');

  return (
    <main className="app-shell">
      <header className="top-bar">
        <div>
          <p className="eyebrow">StockRush</p>
          <h1>포트폴리오 운영</h1>
        </div>
        <p className="runtime-note">Orders / Outbox / Products</p>
      </header>

      <div className="segmented" role="tablist" aria-label="작업 영역">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'orders'}
          className={tab === 'orders' ? 'segment selected' : 'segment'}
          onClick={() => setTab('orders')}
        >
          Orders
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'outbox'}
          className={tab === 'outbox' ? 'segment selected' : 'segment'}
          onClick={() => setTab('outbox')}
        >
          Outbox
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'products'}
          className={tab === 'products' ? 'segment selected' : 'segment'}
          onClick={() => setTab('products')}
        >
          Products
        </button>
      </div>

      {tab === 'orders' ? (
        <OrdersTab />
      ) : tab === 'outbox' ? (
        <OutboxTab />
      ) : (
        <CatalogTab />
      )}
    </main>
  );
}
