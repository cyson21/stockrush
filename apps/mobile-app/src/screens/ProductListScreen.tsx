import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { listOnSaleProducts } from '../api/catalog';
import { ApiClientError } from '../api/client';
import { listStocks } from '../api/inventory';
import type { Product, Stock } from '../types/api';

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

export default function ProductListScreen() {
  const [products, setProducts] = useState<Product[]>([]);
  const [productStatus, setProductStatus] = useState<LoadStatus>('idle');
  const [productError, setProductError] = useState<string | null>(null);
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [stocks, setStocks] = useState<Stock[]>([]);
  const [stockStatus, setStockStatus] = useState<LoadStatus>('idle');
  const [stockError, setStockError] = useState<string | null>(null);
  const stockRequestIdRef = useRef(0);

  const loadProducts = useCallback(async () => {
    stockRequestIdRef.current += 1;
    setProductStatus('loading');
    setProductError(null);
    setSelectedProduct(null);
    setStocks([]);
    setStockStatus('idle');
    setStockError(null);

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

    try {
      const response = await listStocks(product.productCode);
      if (stockRequestIdRef.current !== requestId) {
        return;
      }
      setStocks(response);
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
            <View key={stock.skuId} style={styles.stockRow}>
              <View style={styles.stockMain}>
                <Text style={styles.skuId}>{stock.skuId}</Text>
                <Text style={styles.productCode}>version {stock.version}</Text>
              </View>
              <View style={styles.quantityGroup}>
                <Text style={styles.quantity}>주문 가능 {stock.availableQuantity}</Text>
                <Text style={styles.reserved}>예약 중 {stock.reservedQuantity}</Text>
              </View>
            </View>
          ))}
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
});
