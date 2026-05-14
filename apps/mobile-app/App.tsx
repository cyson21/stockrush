import { StatusBar } from 'expo-status-bar';
import { useMemo } from 'react';
import { SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import { getApiBaseUrl, mobileRuntimeLabel } from './src/config/runtime';

const flowSteps = [
  'Product list',
  'Product detail and stock',
  'Coupon quote',
  'Checkout',
  'Order status',
  'Order history',
];

const gatewayRoutes = [
  'GET /api/products?status=ON_SALE',
  'GET /api/stocks?productCode={productCode}',
  'POST /api/coupons/quote',
  'POST /api/orders',
  'GET /api/orders/{orderId}',
  'GET /api/read-model/orders?memberId={memberId}',
];

export default function App() {
  const apiBaseUrl = useMemo(() => getApiBaseUrl(), []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Text style={styles.eyebrow}>StockRush Mobile</Text>
          <Text style={styles.title}>Customer order flow</Text>
          <Text style={styles.subtitle}>
            Gateway-first mobile shell for product browsing, coupon quote, checkout, status tracking, and order history.
          </Text>
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Runtime</Text>
          <Text style={styles.label}>Target</Text>
          <Text style={styles.value}>{mobileRuntimeLabel}</Text>
          <Text style={styles.label}>API base URL</Text>
          <Text style={styles.value}>{apiBaseUrl}</Text>
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Flow</Text>
          {flowSteps.map((step, index) => (
            <View key={step} style={styles.row}>
              <Text style={styles.stepNumber}>{index + 1}</Text>
              <Text style={styles.rowText}>{step}</Text>
            </View>
          ))}
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Gateway routes</Text>
          {gatewayRoutes.map((route) => (
            <Text key={route} style={styles.route}>
              {route}
            </Text>
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f7f8fa',
  },
  container: {
    padding: 20,
    gap: 14,
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
  panel: {
    backgroundColor: '#ffffff',
    borderColor: '#d7dee8',
    borderRadius: 8,
    borderWidth: 1,
    gap: 8,
    padding: 16,
  },
  panelTitle: {
    color: '#172033',
    fontSize: 17,
    fontWeight: '800',
    marginBottom: 4,
  },
  label: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
  },
  value: {
    color: '#111827',
    fontSize: 15,
  },
  row: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
    minHeight: 34,
  },
  stepNumber: {
    backgroundColor: '#0f766e',
    borderRadius: 17,
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '800',
    lineHeight: 34,
    minWidth: 34,
    overflow: 'hidden',
    textAlign: 'center',
  },
  rowText: {
    color: '#1f2937',
    fontSize: 15,
  },
  route: {
    backgroundColor: '#eef2f7',
    borderRadius: 6,
    color: '#172033',
    fontFamily: 'Menlo',
    fontSize: 12,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
});
