import { render, screen, waitFor } from '@testing-library/react';
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

describe('admin app operations', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
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

      if (request.pathname === '/orders/api/admin/outbox-events' && method === 'GET') {
        return toJsonResponse(
          buildResponse(true, {
            limit: 50,
            offset: 0,
            items: [],
          }),
          200,
        );
      }

      if (request.pathname === '/inventory/api/admin/outbox-events' && method === 'GET') {
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

      if (request.pathname === '/inventory/api/admin/outbox-events/retry' && method === 'POST') {
        return toJsonResponse(
          buildResponse(true, {
            claimed: 2,
            published: 1,
            failed: 0,
          }),
          200,
        );
      }

      throw new Error(`Unexpected request: ${String(input)}`);
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('loads orders and fetches selected order saga', async () => {
    const user = userEvent.setup();

    render(<App />);

    expect(await screen.findByText('ord_admin_001')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /ord_admin_002/ }));

    await waitFor(() => {
      const sagaRequests = fetchMock.mock.calls.filter(
        ([url]) => String(url).includes('/orders/api/admin/orders/ord_admin_002/saga'),
      );
      expect(sagaRequests.length).toBeGreaterThanOrEqual(1);
    });

    expect(await screen.findByText('PAYMENT_DECLINED')).toBeInTheDocument();
    expect(screen.getByText('OrderCancelled')).toBeInTheDocument();
  });

  it('lists outbox events for inventory and shows retry result', async () => {
    const user = userEvent.setup();
    render(<App />);

    const outboxTabs = screen.getAllByRole('tab', { name: 'Outbox' });
    await user.click(outboxTabs[0]);
    await user.selectOptions(screen.getByLabelText('서비스'), 'inventory');

    expect(await screen.findByText('evt-inv-001')).toBeInTheDocument();
    expect(await screen.findByText('temporary network issue')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '선택한 서비스 재시도' }));

    await waitFor(() => {
      expect(screen.getByText(/2건 claim, 1건 publish, 0건 fail/)).toBeInTheDocument();
    });
  });
});
