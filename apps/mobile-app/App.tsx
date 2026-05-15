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
