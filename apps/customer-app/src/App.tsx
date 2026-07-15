// 앱의 최상위 UI 레이어: 핵심 상태와 화면 구성을 담당합니다.
import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardActionArea,
  Chip,
  Container,
  CssBaseline,
  Divider,
  Paper,
  Stack,
  TextField,
  ThemeProvider,
  Typography,
  createTheme,
} from '@mui/material';
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
const appTheme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#155e75',
    },
    secondary: {
      main: '#4f46e5',
    },
    background: {
      default: '#f4f7fb',
      paper: '#ffffff',
    },
  },
  shape: {
    borderRadius: 8,
  },
  typography: {
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  },
});

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
    paymentMethod.trim().length > 0 &&
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
    if (!selectedProduct || !selectedStock || !quantityIsValid || paymentMethod.trim().length === 0) {
      setMessage('상품, SKU, 수량, 결제수단을 확인하세요.');
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
        paymentMethod: paymentMethod.trim(),
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
    <ThemeProvider theme={appTheme}>
      <CssBaseline />
      <Box component="main" sx={{ minHeight: '100vh', py: { xs: 3, md: 5 } }}>
        <Container maxWidth="xl">
          <Paper
            component="header"
            elevation={0}
            sx={{
              alignItems: { xs: 'flex-start', md: 'center' },
              border: '1px solid',
              borderColor: 'divider',
              display: 'flex',
              flexDirection: { xs: 'column', md: 'row' },
              gap: 2,
              justifyContent: 'space-between',
              mb: 3,
              p: { xs: 2, md: 3 },
            }}
          >
            <Box>
              <Typography color="primary" sx={{ fontWeight: 800, letterSpacing: 0 }} variant="overline">
                StockRush
              </Typography>
              <Typography component="h1" sx={{ fontWeight: 900 }} variant="h4">
                한정 상품 주문
              </Typography>
              <Typography color="text.secondary" sx={{ mt: 0.75 }} variant="body2">
                Catalog · Inventory · Order
              </Typography>
            </Box>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{ alignItems: { xs: 'flex-start', sm: 'center' } }}
            >
              <Chip
                color={authToken ? 'success' : 'default'}
                label={`인증 상태: ${authToken ? '인증됨' : '미인증'}`}
                variant={authToken ? 'filled' : 'outlined'}
              />
              <Button onClick={authToken ? logout : login} type="button" variant="contained">
                {authToken ? '로그아웃' : '로그인'}
              </Button>
            </Stack>
          </Paper>

          {message && (
            <Alert role="alert" severity="error" sx={{ mb: 2 }}>
              {message}
            </Alert>
          )}

          <Box
            aria-label="고객 주문 흐름"
            component="section"
            sx={{
              display: 'grid',
              gap: 2,
              gridTemplateColumns: {
                xs: '1fr',
                lg: 'minmax(280px, 0.9fr) minmax(340px, 1.1fr) minmax(320px, 1fr)',
              },
            }}
          >
            <Paper aria-label="상품 목록" component="section" elevation={0} sx={{ p: 2.5 }}>
              <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', mb: 2 }}>
                <Typography component="h2" sx={{ fontWeight: 800 }} variant="h6">
                  상품
                </Typography>
                <Chip
                  label={productsState === 'loading' ? '불러오는 중' : `${products.length}개`}
                  size="small"
                  variant="outlined"
                />
              </Stack>
              <TextField
                fullWidth
                label="상품 검색"
                onChange={(event) => setProductQuery(event.target.value)}
                placeholder="상품명 또는 코드 검색"
                size="small"
                value={productQuery}
              />
              <Stack spacing={1.25} sx={{ mt: 2 }}>
                {products.map((product) => (
                  <Card
                    key={product.productCode}
                    elevation={0}
                    sx={{
                      border: '1px solid',
                      borderColor: selectedProduct?.productCode === product.productCode ? 'primary.main' : 'divider',
                      overflow: 'hidden',
                    }}
                  >
                    <CardActionArea onClick={() => setSelectedProduct(product)} sx={{ p: 1.75 }}>
                      <Stack
                        direction={{ xs: 'column', sm: 'row' }}
                        spacing={1}
                        sx={{ alignItems: { xs: 'flex-start', sm: 'center' }, justifyContent: 'space-between' }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <Typography sx={{ fontWeight: 800 }}>{product.name}</Typography>
                          <Typography color="text.secondary" sx={{ overflowWrap: 'anywhere' }} variant="body2">
                            {product.productCode}
                          </Typography>
                        </Box>
                        <Typography color="secondary.main" sx={{ fontWeight: 900, whiteSpace: 'nowrap' }}>
                          {formatCurrency(product.listPrice)}
                        </Typography>
                      </Stack>
                    </CardActionArea>
                  </Card>
                ))}
              </Stack>
            </Paper>

            <Paper aria-label="주문 생성" component="section" elevation={0} sx={{ p: 2.5 }}>
              <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', mb: 2 }}>
                <Typography component="h2" sx={{ fontWeight: 800 }} variant="h6">
                  주문
                </Typography>
                <Chip label={selectedProduct ? selectedProduct.status : '상품 선택 필요'} size="small" />
              </Stack>

              {selectedProduct ? (
                <Stack spacing={2}>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={0.5}
                    sx={{ justifyContent: 'space-between' }}
                  >
                    <Typography color="text.secondary">선택 상품</Typography>
                    <Typography sx={{ fontWeight: 800, overflowWrap: 'anywhere', textAlign: { xs: 'left', sm: 'right' } }}>
                      {selectedProduct.name}
                    </Typography>
                  </Stack>

                  <TextField
                    fullWidth
                    label="SKU"
                    onChange={(event) => setSelectedSkuId(event.target.value)}
                    select
                    size="small"
                    slotProps={{
                      select: {
                        native: true,
                        sx: { '& select': { textOverflow: 'ellipsis' } },
                      },
                    }}
                    value={selectedSkuId}
                  >
                    {stocks.map((stock) => (
                      <option key={stock.skuId} value={stock.skuId}>
                        {stock.skuId}
                      </option>
                    ))}
                  </TextField>

                  {selectedStock && (
                    <Alert role="status" severity="success" variant="outlined">
                      선택됨 · 가용 {selectedStock.availableQuantity} · 예약 {selectedStock.reservedQuantity}
                    </Alert>
                  )}

                  <TextField
                    fullWidth
                    inputMode="numeric"
                    label="수량"
                    onChange={(event) => setQuantityInput(event.target.value)}
                    size="small"
                    slotProps={{ htmlInput: { min: 1 } }}
                    type="number"
                    value={quantityInput}
                  />

                  <TextField
                    fullWidth
                    label="쿠폰 코드"
                    onChange={(event) => {
                      setCouponCode(event.target.value);
                      setCouponQuote(null);
                      setCouponQuoteError(null);
                    }}
                    placeholder="WELCOME10"
                    size="small"
                    value={couponCode}
                  />

                  <Button
                    disabled={applyingCoupon || couponCode.trim().length === 0 || !selectedProduct || totalAmount <= 0}
                    onClick={applyCouponQuote}
                    type="button"
                    variant="outlined"
                  >
                    {applyingCoupon ? '쿠폰 적용 중' : '쿠폰 적용'}
                  </Button>

                  {couponQuote && (
                    <Alert role="status" severity="info">{`쿠폰 적용: ${couponStatusLabel}`}</Alert>
                  )}

                  {couponQuoteError && (
                    <Alert role="alert" severity="error">
                      {couponQuoteError}
                    </Alert>
                  )}

                  <TextField
                    fullWidth
                    label="결제수단"
                    onChange={(event) => setPaymentMethod(event.target.value)}
                    select
                    size="small"
                    slotProps={{ select: { native: true } }}
                    value={paymentMethod}
                  >
                    <option value="CARD">CARD</option>
                    <option value="FAIL_CARD">FAIL_CARD</option>
                    <option value="DELAY_CARD">DELAY_CARD</option>
                  </TextField>

                  <Divider />
                  <SummaryLine label="주문 금액" value={formatCurrency(totalAmount)} />
                  <SummaryLine label="할인 금액" value={formatCurrency(discountAmount)} />
                  <SummaryLine label="결제 예정 금액" value={formatCurrency(payableAmount)} strong />

                  <Button disabled={!canSubmitOrder} onClick={submitOrder} size="large" type="button" variant="contained">
                    {submitting ? '처리 중' : '주문 생성'}
                  </Button>
                </Stack>
              ) : (
                <Typography color="text.secondary">상품을 선택하면 재고와 주문 입력을 확인할 수 있습니다.</Typography>
              )}
            </Paper>

            <Paper
              aria-label="주문 상태"
              aria-live="polite"
              component="section"
              elevation={0}
              role="region"
              sx={{ p: 2.5 }}
            >
              <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', mb: 2 }}>
                <Typography component="h2" sx={{ fontWeight: 800 }} variant="h6">
                  상태
                </Typography>
                <Chip label={createdOrder ? '추적 중' : '대기'} size="small" />
              </Stack>

              {createdOrder ? (
                <Stack spacing={2}>
                  <Box sx={{ display: 'grid', gap: 1.25, gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
                    <StatusCard label="주문번호" value={createdOrder.orderId} />
                    <StatusCard label="주문 상태" value={orderDetail?.status ?? createdOrder.status} />
                    <StatusCard label="Saga 상태" value={orderDetail?.sagaStatus ?? createdOrder.sagaStatus} />
                    <StatusCard label="결제수단" value={orderDetail?.paymentMethod ?? createdOrder.paymentMethod} />
                  </Box>

                  <Stack spacing={1}>
                    {(orderDetail?.items ?? []).map((item) => (
                      <Paper key={`${item.productCode}-${item.skuId}`} elevation={0} sx={{ p: 1.5 }}>
                        <Stack
                          direction={{ xs: 'column', sm: 'row' }}
                          spacing={1}
                          sx={{ justifyContent: 'space-between' }}
                        >
                          <Typography sx={{ overflowWrap: 'anywhere' }}>{item.skuId}</Typography>
                          <Typography>{item.quantity}개</Typography>
                          <Typography sx={{ fontWeight: 800 }}>{formatCurrency(item.lineAmount)}</Typography>
                        </Stack>
                      </Paper>
                    ))}
                  </Stack>
                </Stack>
              ) : (
                <Typography color="text.secondary">주문 생성 후 진행 상태가 표시됩니다.</Typography>
              )}
            </Paper>
          </Box>
        </Container>
      </Box>
    </ThemeProvider>
  );
}

function SummaryLine({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
      <Typography color="text.secondary">{label}</Typography>
      <Typography sx={{ fontWeight: strong ? 900 : 700 }}>{value}</Typography>
    </Stack>
  );
}

function StatusCard({ label, value }: { label: string; value: string }) {
  return (
    <Paper elevation={0} sx={{ bgcolor: 'grey.50', minHeight: 78, p: 1.5 }}>
      <Typography color="text.secondary" variant="body2">
        {label}
      </Typography>
      <Typography
        className={statusClass(value)}
        component="strong"
        sx={{ display: 'block', fontWeight: 900, mt: 0.5, overflowWrap: 'anywhere' }}
      >
        {value}
      </Typography>
    </Paper>
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
