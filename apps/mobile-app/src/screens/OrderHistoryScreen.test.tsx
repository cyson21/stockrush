// 화면 컴포넌트 단위로 사용자 플로우를 구성하는 뷰 레이어입니다.
import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AuthProvider } from '../auth/AuthContext';
import OrderHistoryScreen from './OrderHistoryScreen';

const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

const jsonResponse = (body: unknown, status = 200) => Promise.resolve(response(body, status));

describe('OrderHistoryScreen', () => {
  const fetchMock = jest.fn() as jest.MockedFunction<typeof fetch>;

  const renderWithAuth = (ui: ReactNode, token?: string | null) => {
    return render(<AuthProvider initialAccessToken={token ?? null}>{ui}</AuthProvider>);
  };

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock;
  });

  it('loads read model order history for the default member', async () => {
    fetchMock.mockImplementation((input) => {
      const url = String(input);

      if (url === 'http://localhost:18080/api/read-model/orders?memberId=member-mobile-demo&page=0&size=20') {
        return jsonResponse({
          success: true,
          data: {
            page: 0,
            size: 20,
            items: [
              {
                orderId: 'ord_history_001',
                memberId: 'member-mobile-demo',
                status: 'CONFIRMED',
                sagaStatus: 'COMPLETED',
                couponCode: 'WELCOME10',
                totalAmount: 12000,
                discountAmount: 5000,
                payableAmount: 7000,
                itemCount: 1,
                cancellationReason: null,
                createdAt: '2026-05-14T04:30:00Z',
                updatedAt: '2026-05-14T04:31:00Z',
              },
            ],
          },
          error: null,
          trace: { correlationId: 'corr-history' },
        });
      }

      throw new Error(`Unexpected request: ${url}`);
    });

    renderWithAuth(<OrderHistoryScreen />, 'member-mobile-token');

    fireEvent.press(screen.getByText('내역 새로고침'));

    expect(await screen.findByText('ord_history_001')).toBeTruthy();
    expect(screen.getByText('COMPLETED')).toBeTruthy();
    expect(screen.getByText('결제 7,000원')).toBeTruthy();
    expect(screen.getByText('쿠폰 WELCOME10')).toBeTruthy();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        'http://localhost:18080/api/read-model/orders?memberId=member-mobile-demo&page=0&size=20',
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            'X-Correlation-Id': 'mobile-read-model-member-mobile-demo',
            Authorization: 'Bearer member-mobile-token',
          }),
        }),
      );
    });
  });

  it('shows retry action when read model order history fails', async () => {
    fetchMock
      .mockResolvedValueOnce(
        response(
          {
            success: false,
            data: null,
            error: { code: 'READ_MODEL_DOWN', message: 'read model unavailable', details: {} },
            trace: { correlationId: 'corr-history-error' },
          },
          503,
        ),
      )
      .mockResolvedValueOnce(
        response({
          success: true,
          data: {
            page: 0,
            size: 20,
            items: [],
          },
          error: null,
          trace: { correlationId: 'corr-history-empty' },
        }),
      );

    renderWithAuth(<OrderHistoryScreen />, 'member-mobile-token');

    fireEvent.press(screen.getByText('내역 새로고침'));

    expect(await screen.findByText('READ_MODEL_DOWN: read model unavailable')).toBeTruthy();

    fireEvent.press(screen.getByText('내역 새로고침'));

    expect(await screen.findByText('표시할 주문 내역이 없습니다.')).toBeTruthy();
  });

  it('blocks order history fetch when not logged in', async () => {
    renderWithAuth(<OrderHistoryScreen />, null);

    expect(screen.getByText('로그인 후 주문 내역을 조회할 수 있습니다.')).toBeTruthy();

    fireEvent.press(screen.getByText('내역 새로고침'));

    expect(await screen.findByText('주문 내역 대기')).toBeTruthy();

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
