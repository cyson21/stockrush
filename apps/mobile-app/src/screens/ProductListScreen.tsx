import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { listOnSaleProducts } from '../api/catalog';
import { ApiClientError } from '../api/client';
import { listStocks } from '../api/inventory';
import { createOrder } from '../api/orders';
import { quoteCoupon } from '../api/promotion';
import { getDefaultMemberId } from '../config/runtime';
import type { CreateOrderResponse, Product, PromotionQuoteResponse, Stock } from '../types/api';

type LoadStatus = 'idle' | 'loading' | 'ready' | 'error';
type PaymentMethod = 'CARD' | 'FAIL_CARD' | 'DELAY_CARD';

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

export default function ProductListScreen() {
  const [products, setProducts] = useState<Product[]>([]);
  const [productStatus, setProductStatus] = useState<LoadStatus>('idle');
  const [productError, setProductError] = useState<string | null>(null);
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [stocks, setStocks] = useState<Stock[]>([]);
  const [stockStatus, setStockStatus] = useState<LoadStatus>('idle');
  const [stockError, setStockError] = useState<string | null>(null);
  const [selectedStock, setSelectedStock] = useState<Stock | null>(null);
  const [quantityText, setQuantityText] = useState('1');
  const [couponCode, setCouponCode] = useState('');
  const [couponQuote, setCouponQuote] = useState<PromotionQuoteResponse | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [couponStatus, setCouponStatus] = useState<LoadStatus>('idle');
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('CARD');
  const [orderStatus, setOrderStatus] = useState<LoadStatus>('idle');
  const [orderError, setOrderError] = useState<string | null>(null);
  const [createdOrder, setCreatedOrder] = useState<CreateOrderResponse | null>(null);
  const stockRequestIdRef = useRef(0);
  const memberId = getDefaultMemberId();

  const quantity = Number(quantityText);
  const quantityIsValid = Number.isInteger(quantity) && quantity >= 1;
  const totalAmount = selectedProduct && quantityIsValid ? selectedProduct.listPrice * quantity : 0;
  const discountAmount = couponQuote?.applied ? couponQuote.discountAmount : 0;
  const payableAmount = couponQuote?.applied ? couponQuote.payAmount : totalAmount;
  const hasBlockedCoupon = Boolean(couponError) || couponQuote?.applied === false;

  const loadProducts = useCallback(async () => {
    stockRequestIdRef.current += 1;
    setProductStatus('loading');
    setProductError(null);
    setSelectedProduct(null);
    setStocks([]);
    setStockStatus('idle');
    setStockError(null);
    setSelectedStock(null);
    setCouponQuote(null);
    setCouponError(null);
    setCouponStatus('idle');
    setCreatedOrder(null);
    setOrderError(null);
    setOrderStatus('idle');

    try {
      const response = await listOnSaleProducts();
      setProducts(response);
      setProductStatus('ready');
    } catch (error) {
      setProducts([]);
      setProductStatus('error');
      setProductError(formatError(error));
    }
  }, []);

  const loadStocks = useCallback(async (product: Product) => {
    const requestId = stockRequestIdRef.current + 1;
    stockRequestIdRef.current = requestId;
    setSelectedProduct(product);
    setStockStatus('loading');
    setStockError(null);
    setStocks([]);
    setSelectedStock(null);
    setCouponQuote(null);
    setCouponError(null);
    setCouponStatus('idle');
    setCreatedOrder(null);
    setOrderError(null);
    setOrderStatus('idle');

    try {
      const response = await listStocks(product.productCode);
      if (stockRequestIdRef.current !== requestId) {
        return;
      }
      setStocks(response);
      setSelectedStock(response[0] ?? null);
      setStockStatus('ready');
    } catch (error) {
      if (stockRequestIdRef.current !== requestId) {
        return;
      }
      setStockStatus('error');
      setStockError(formatError(error));
    }
  }, []);

  useEffect(() => {
    void loadProducts();
  }, [loadProducts]);

  const updateCouponCode = (value: string) => {
    setCouponCode(value);
    setCouponQuote(null);
    setCouponError(null);
    setCouponStatus('idle');
  };

  const applyCoupon = async () => {
    const trimmedCouponCode = couponCode.trim();
    if (!selectedProduct || !selectedStock || !quantityIsValid || totalAmount <= 0 || trimmedCouponCode.length === 0) {
      setCouponError('주문 항목과 쿠폰 코드를 확인하세요.');
      return;
    }

    setCouponStatus('loading');
    setCouponError(null);
    setCouponQuote(null);

    try {
      const response = await quoteCoupon({
        couponCode: trimmedCouponCode,
        orderAmount: totalAmount,
      });
      setCouponQuote(response);
      setCouponStatus('ready');
    } catch (error) {
      setCouponStatus('error');
      setCouponError(`쿠폰 적용 실패: ${formatError(error)}`);
    }
  };

  const submitOrder = async () => {
    if (!selectedProduct || !selectedStock || !quantityIsValid || totalAmount <= 0) {
      setOrderError('상품, SKU, 수량을 확인하세요.');
      return;
    }

    if (hasBlockedCoupon) {
      setOrderError('쿠폰 적용 상태를 확인하세요.');
      return;
    }

    setOrderStatus('loading');
    setOrderError(null);

    try {
      const response = await createOrder({
        memberId,
        paymentMethod,
        couponCode: couponCode.trim() || undefined,
        items: [
          {
            productCode: selectedProduct.productCode,
            skuId: selectedStock.skuId,
            quantity,
            unitPrice: selectedProduct.listPrice,
          },
        ],
      });
      setCreatedOrder(response);
      setOrderStatus('ready');
    } catch (error) {
      setOrderStatus('error');
      setOrderError(formatError(error));
    }
  };

  return (
    <View style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Text style={styles.eyebrow}>StockRush Mobile</Text>
          <Text style={styles.title}>상품 선택</Text>
          <Text style={styles.subtitle}>판매 중인 상품을 고르고 SKU별 주문 가능 수량을 확인합니다.</Text>
        </View>

        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>판매 상품</Text>
            {productStatus === 'loading' ? <ActivityIndicator color="#0f766e" /> : null}
          </View>

          {productStatus === 'loading' ? <Text style={styles.stateText}>상품 목록 조회 중</Text> : null}

          {productStatus === 'error' ? (
            <View style={styles.notice}>
              <Text style={styles.errorText}>{productError}</Text>
              <Pressable accessibilityRole="button" onPress={loadProducts} style={styles.secondaryButton}>
                <Text style={styles.secondaryButtonText}>다시 조회</Text>
              </Pressable>
            </View>
          ) : null}

          {productStatus === 'ready' && products.length === 0 ? (
            <Text style={styles.stateText}>판매 중인 상품이 없습니다.</Text>
          ) : null}

          {products.map((product) => {
            const selected = selectedProduct?.productCode === product.productCode;

            return (
              <Pressable
                accessibilityRole="button"
                key={product.productCode}
                onPress={() => void loadStocks(product)}
                style={[styles.productCard, selected ? styles.selectedCard : null]}
              >
                <View style={styles.productMain}>
                  <Text style={styles.productName}>{product.name}</Text>
                  <Text style={styles.productCode}>{product.productCode}</Text>
                </View>
                <Text style={styles.price}>{formatPrice(product.listPrice)}</Text>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>SKU 재고</Text>
            {stockStatus === 'loading' ? <ActivityIndicator color="#0f766e" /> : null}
          </View>

          {selectedProduct === null ? (
            <Text style={styles.stateText}>상품을 선택하면 재고를 조회합니다.</Text>
          ) : null}

          {selectedProduct !== null && stockStatus === 'loading' ? (
            <Text style={styles.stateText}>SKU 재고 조회 중</Text>
          ) : null}

          {selectedProduct !== null && stockStatus === 'error' ? (
            <View style={styles.notice}>
              <Text style={styles.errorText}>{stockError}</Text>
              <Pressable
                accessibilityRole="button"
                onPress={() => void loadStocks(selectedProduct)}
                style={styles.secondaryButton}
              >
                <Text style={styles.secondaryButtonText}>다시 조회</Text>
              </Pressable>
            </View>
          ) : null}

          {selectedProduct !== null && stockStatus === 'ready' && stocks.length === 0 ? (
            <Text style={styles.stateText}>선택한 상품의 SKU 재고가 없습니다.</Text>
          ) : null}

          {stocks.map((stock) => (
            <Pressable
              accessibilityRole="button"
              key={stock.skuId}
              onPress={() => setSelectedStock(stock)}
              style={[styles.stockRow, selectedStock?.skuId === stock.skuId ? styles.selectedCard : null]}
            >
              <View style={styles.stockMain}>
                <Text style={styles.skuId}>{stock.skuId}</Text>
                <Text style={styles.productCode}>version {stock.version}</Text>
              </View>
              <View style={styles.quantityGroup}>
                <Text style={styles.quantity}>주문 가능 {stock.availableQuantity}</Text>
                <Text style={styles.reserved}>예약 중 {stock.reservedQuantity}</Text>
              </View>
            </Pressable>
          ))}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>체크아웃</Text>

          {selectedProduct && selectedStock ? (
            <>
              <View style={styles.summaryRow}>
                <Text style={styles.summaryLabel}>선택 SKU</Text>
                <Text style={styles.summaryValue}>선택됨 {selectedStock.skuId}</Text>
              </View>

              <View style={styles.field}>
                <Text style={styles.fieldLabel}>수량</Text>
                <TextInput
                  accessibilityLabel="수량"
                  inputMode="numeric"
                  keyboardType="number-pad"
                  onChangeText={(value) => {
                    setQuantityText(value);
                    setCouponQuote(null);
                    setCouponError(null);
                    setCouponStatus('idle');
                  }}
                  style={styles.input}
                  value={quantityText}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.fieldLabel}>쿠폰 코드</Text>
                <TextInput
                  accessibilityLabel="쿠폰 코드"
                  autoCapitalize="characters"
                  onChangeText={updateCouponCode}
                  placeholder="WELCOME10"
                  style={styles.input}
                  value={couponCode}
                />
              </View>

              <Pressable
                accessibilityRole="button"
                disabled={couponStatus === 'loading' || couponCode.trim().length === 0}
                onPress={() => void applyCoupon()}
                style={[styles.secondaryButton, couponStatus === 'loading' ? styles.disabledButton : null]}
              >
                <Text style={styles.secondaryButtonText}>
                  {couponStatus === 'loading' ? '쿠폰 적용 중' : '쿠폰 적용'}
                </Text>
              </Pressable>

              {couponQuote ? (
                <View style={styles.summaryBox}>
                  <Text style={styles.summaryValue}>
                    쿠폰 적용: {couponQuote.applied ? 'APPLIED' : couponQuote.reason}
                  </Text>
                  <Text style={styles.summaryText}>할인 {formatPrice(couponQuote.discountAmount)}</Text>
                  <Text style={styles.summaryText}>결제 예정 {formatPrice(couponQuote.payAmount)}</Text>
                </View>
              ) : null}

              {couponError ? <Text style={styles.errorText}>{couponError}</Text> : null}
              {couponQuote?.applied === false ? (
                <Text style={styles.errorText}>쿠폰 적용 상태를 확인하세요.</Text>
              ) : null}

              <View style={styles.paymentGroup}>
                {(['CARD', 'FAIL_CARD', 'DELAY_CARD'] as const).map((method) => (
                  <Pressable
                    accessibilityRole="button"
                    key={method}
                    onPress={() => setPaymentMethod(method)}
                    style={[styles.paymentButton, paymentMethod === method ? styles.paymentButtonSelected : null]}
                  >
                    <Text style={paymentMethod === method ? styles.paymentTextSelected : styles.paymentText}>{method}</Text>
                  </Pressable>
                ))}
              </View>

              <View style={styles.summaryBox}>
                <Text style={styles.summaryText}>주문 금액 {formatPrice(totalAmount)}</Text>
                <Text style={styles.summaryText}>할인 {formatPrice(discountAmount)}</Text>
                <Text style={styles.summaryValue}>결제 예정 {formatPrice(payableAmount)}</Text>
              </View>

              {orderError ? <Text style={styles.errorText}>{orderError}</Text> : null}

              <Pressable
                accessibilityRole="button"
                disabled={orderStatus === 'loading' || hasBlockedCoupon || !quantityIsValid}
                onPress={() => void submitOrder()}
                style={[
                  styles.primaryButton,
                  orderStatus === 'loading' || hasBlockedCoupon || !quantityIsValid ? styles.disabledButton : null,
                ]}
              >
                <Text style={styles.primaryButtonText}>{orderStatus === 'loading' ? '주문 생성 중' : '주문 생성'}</Text>
              </Pressable>

              {createdOrder ? (
                <View style={styles.summaryBox}>
                  <Text style={styles.summaryLabel}>생성된 주문</Text>
                  <Text style={styles.summaryValue}>{createdOrder.orderId}</Text>
                  <Text style={styles.summaryText}>{createdOrder.status}</Text>
                  <Text style={styles.summaryText}>{createdOrder.sagaStatus}</Text>
                </View>
              ) : null}
            </>
          ) : (
            <Text style={styles.stateText}>상품과 SKU를 선택하면 주문 입력을 확인합니다.</Text>
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f7f8fa',
  },
  container: {
    gap: 14,
    padding: 20,
  },
  header: {
    gap: 8,
    paddingVertical: 8,
  },
  eyebrow: {
    color: '#0f766e',
    fontSize: 13,
    fontWeight: '700',
  },
  title: {
    color: '#172033',
    fontSize: 28,
    fontWeight: '800',
  },
  subtitle: {
    color: '#4b5563',
    fontSize: 15,
    lineHeight: 22,
  },
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
  notice: {
    gap: 10,
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
  primaryButton: {
    alignItems: 'center',
    backgroundColor: '#172033',
    borderRadius: 6,
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  primaryButtonText: {
    color: '#ffffff',
    fontSize: 15,
    fontWeight: '800',
  },
  disabledButton: {
    opacity: 0.55,
  },
  productCard: {
    alignItems: 'center',
    borderColor: '#e2e8f0',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
    minHeight: 76,
    padding: 14,
  },
  selectedCard: {
    borderColor: '#0f766e',
    backgroundColor: '#ecfdf5',
  },
  productMain: {
    flex: 1,
    gap: 4,
  },
  productName: {
    color: '#172033',
    fontSize: 16,
    fontWeight: '800',
  },
  productCode: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '700',
  },
  price: {
    color: '#111827',
    fontSize: 15,
    fontWeight: '800',
  },
  stockRow: {
    alignItems: 'center',
    backgroundColor: '#f8fafc',
    borderRadius: 8,
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
    minHeight: 70,
    padding: 14,
  },
  stockMain: {
    flex: 1,
    gap: 4,
  },
  skuId: {
    color: '#172033',
    fontSize: 15,
    fontWeight: '800',
  },
  quantityGroup: {
    alignItems: 'flex-end',
    gap: 4,
  },
  quantity: {
    color: '#047857',
    fontSize: 14,
    fontWeight: '800',
  },
  reserved: {
    color: '#475569',
    fontSize: 13,
    fontWeight: '700',
  },
  summaryRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
  summaryBox: {
    backgroundColor: '#f8fafc',
    borderRadius: 8,
    gap: 6,
    padding: 12,
  },
  summaryLabel: {
    color: '#64748b',
    fontSize: 13,
    fontWeight: '700',
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
  field: {
    gap: 6,
  },
  fieldLabel: {
    color: '#475569',
    fontSize: 13,
    fontWeight: '800',
  },
  input: {
    backgroundColor: '#ffffff',
    borderColor: '#cbd5e1',
    borderRadius: 6,
    borderWidth: 1,
    color: '#172033',
    fontSize: 15,
    minHeight: 42,
    paddingHorizontal: 12,
  },
  paymentGroup: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  paymentButton: {
    borderColor: '#cbd5e1',
    borderRadius: 6,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
  paymentButtonSelected: {
    backgroundColor: '#0f766e',
    borderColor: '#0f766e',
  },
  paymentText: {
    color: '#334155',
    fontSize: 13,
    fontWeight: '800',
  },
  paymentTextSelected: {
    color: '#ffffff',
    fontSize: 13,
    fontWeight: '800',
  },
});
