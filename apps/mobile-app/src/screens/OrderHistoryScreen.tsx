// 화면 컴포넌트 단위로 사용자 플로우를 구성하는 뷰 레이어입니다.
import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { ApiClientError } from '../api/client';
import { listOrderHistory } from '../api/readModel';
import { getDefaultMemberId } from '../config/runtime';
import { useAuth } from '../auth/AuthContext';
import type { OrderSummary } from '../types/api';

type LoadStatus = 'idle' | 'loading' | 'ready' | 'error';

function formatPrice(value: number): string {
  return `${new Intl.NumberFormat('ko-KR').format(value)}원`;
}

function formatError(error: unknown): string {
  if (error instanceof ApiClientError && error.apiError) {
    return `${error.apiError.code}: ${error.apiError.message}`;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return '요청을 처리하지 못했습니다.';
}

export default function OrderHistoryScreen() {
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [status, setStatus] = useState<LoadStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const memberId = getDefaultMemberId();
  const { isAuthenticated, accessToken } = useAuth();

  const loadOrders = async () => {
    if (!isAuthenticated) {
      setStatus('error');
      setErrorMessage('로그인 후 주문 내역을 조회할 수 있습니다.');
      return;
    }

    setStatus('loading');
    setErrorMessage(null);

    try {
      const response = await listOrderHistory(memberId, 0, 20, accessToken);
      setOrders(response.items);
      setStatus('ready');
    } catch (error) {
      setOrders([]);
      setStatus('error');
      setErrorMessage(formatError(error));
    }
  };

  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>최근 주문 내역</Text>
        {status === 'loading' ? <ActivityIndicator color="#0f766e" /> : null}
      </View>

      <Pressable
        accessibilityRole="button"
        disabled={status === 'loading' || !isAuthenticated}
        onPress={() => void loadOrders()}
        style={[
          styles.secondaryButton,
          status === 'loading' || !isAuthenticated ? styles.disabledButton : null,
        ]}
      >
        <Text style={styles.secondaryButtonText}>{status === 'loading' ? '내역 조회 중' : '내역 새로고침'}</Text>
      </Pressable>

      {!isAuthenticated ? <Text style={styles.errorText}>로그인 후 주문 내역을 조회할 수 있습니다.</Text> : null}
      {status === 'idle' ? <Text style={styles.stateText}>주문 내역 대기</Text> : null}
      {status === 'error' ? <Text style={styles.errorText}>{errorMessage}</Text> : null}
      {status === 'ready' && orders.length === 0 ? <Text style={styles.stateText}>표시할 주문 내역이 없습니다.</Text> : null}

      {orders.map((order) => (
        <View key={order.orderId} style={styles.summaryBox}>
          <Text style={styles.summaryValue}>{order.orderId}</Text>
          <Text style={styles.summaryText}>{order.status}</Text>
          <Text style={styles.summaryText}>{order.sagaStatus}</Text>
          <Text style={styles.summaryText}>결제 {formatPrice(order.payableAmount)}</Text>
          <Text style={styles.summaryText}>상품 {order.itemCount}개</Text>
          {order.couponCode ? <Text style={styles.summaryText}>쿠폰 {order.couponCode}</Text> : null}
          {order.cancellationReason ? <Text style={styles.errorText}>{order.cancellationReason}</Text> : null}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    backgroundColor: '#ffffff',
    borderColor: '#d7dee8',
    borderRadius: 8,
    borderWidth: 1,
    gap: 10,
    padding: 16,
  },
  sectionHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    minHeight: 28,
  },
  sectionTitle: {
    color: '#172033',
    fontSize: 17,
    fontWeight: '800',
  },
  stateText: {
    color: '#64748b',
    fontSize: 14,
    lineHeight: 20,
  },
  errorText: {
    color: '#b42318',
    fontSize: 14,
    lineHeight: 20,
  },
  secondaryButton: {
    alignSelf: 'flex-start',
    backgroundColor: '#0f766e',
    borderRadius: 6,
    paddingHorizontal: 14,
    paddingVertical: 9,
  },
  secondaryButtonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '800',
  },
  disabledButton: {
    opacity: 0.55,
  },
  summaryBox: {
    backgroundColor: '#f8fafc',
    borderRadius: 8,
    gap: 6,
    padding: 12,
  },
  summaryValue: {
    color: '#172033',
    fontSize: 15,
    fontWeight: '800',
  },
  summaryText: {
    color: '#475569',
    fontSize: 14,
    fontWeight: '700',
  },
});
