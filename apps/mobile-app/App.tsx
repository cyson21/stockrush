// 앱의 최상위 UI 레이어: 핵심 상태와 화면 구성을 담당합니다.
import { StatusBar } from 'expo-status-bar';
import { AuthProvider } from './src/auth/AuthContext';
import ProductListScreen from './src/screens/ProductListScreen';

export default function App() {
  return (
    <AuthProvider>
      <StatusBar style="dark" />
      <ProductListScreen />
    </AuthProvider>
  );
}
