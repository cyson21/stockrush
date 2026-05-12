import { useEffect, useMemo, useState } from 'react';
import { ApiClientError } from './api/client';
import { getOrderSaga, listOutbox, listRecentOrders, retryOutbox, type ServiceDomain } from './api/admin';
import type { AdminOrderSaga, AdminOrderSummary, OutboxEvent, OutboxRetryResult } from './types/admin';

type TabId = 'orders' | 'outbox';
type LoadState = 'idle' | 'loading' | 'ready' | 'error';

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

function EventRow({ event }: { event: OutboxEvent }) {
  return (
    <tr>
      <td>{emptyIfNull(event.eventId)}</td>
      <td>{emptyIfNull(event.aggregateType)}</td>
      <td>{emptyIfNull(event.aggregateId)}</td>
      <td>{emptyIfNull(event.eventType)}</td>
      <td className={`status-pill ${event.status.toLowerCase()}`}>{event.status}</td>
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

  useEffect(() => {
    if (!selectedOrderId && orders.length > 0) {
      setSelectedOrderId(orders[0].orderId);
    }
  }, [orders, selectedOrderId]);

  const selectedOrder = useMemo(
    () => orders.find((order) => order.orderId === selectedOrderId) ?? null,
    [orders, selectedOrderId],
  );

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
            {ordersState === 'loading'
              ? '불러오는 중'
              : ordersState === 'ready'
                ? `총 ${orders.length}건`
                : '상태 대기'}
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
                <strong>₩{selectedOrder.totalAmount.toLocaleString()}</strong>
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
              {sagaState === 'loading'
                ? '상세를 불러오는 중입니다.'
                : '선택한 주문의 Saga 상세가 여기에 표시됩니다.'}
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
    loadOutbox();
  }, [service]);

  const onRetry = async () => {
    setRetryState('loading');
    setRetryResult(null);
    setRetryError(null);

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

        {(outboxError || retryError) && (
          <p className="error-banner" role="alert">
            {outboxError ?? retryError}
          </p>
        )}
        {retryResult && (
          <p className="success-banner" aria-live="polite">
            {retryResult.claimed}건 claim, {retryResult.published}건 publish, {retryResult.failed}건 fail
          </p>
        )}

        <button type="button" className="action-btn" onClick={() => onRetry()} disabled={retryState === 'loading'}>
          {retryState === 'loading' ? '재시도 진행 중' : '선택한 서비스 재시도'}
        </button>

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

export default function App() {
  const [tab, setTab] = useState<TabId>('orders');

  return (
    <main className="app-shell">
      <header className="top-bar">
        <div>
          <p className="eyebrow">StockRush</p>
          <h1>포트폴리오 운영</h1>
        </div>
        <p className="runtime-note">Orders / Outbox</p>
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
      </div>

      {tab === 'orders' ? <OrdersTab /> : <OutboxTab />}
    </main>
  );
}
