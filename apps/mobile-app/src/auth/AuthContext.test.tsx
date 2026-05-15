import { fireEvent, render, screen } from '@testing-library/react-native';
import { Pressable, Text, View } from 'react-native';
import { useAuth } from './AuthContext';
import { AuthProvider } from './AuthContext';

function AuthStatePanel() {
  const { isAuthenticated, accessToken, logout } = useAuth();

  return (
    <View>
      <Text>{isAuthenticated ? `로그인됨:${accessToken}` : '로그인 필요'}</Text>
      <Pressable onPress={() => void logout()}>
        <Text>로그아웃</Text>
      </Pressable>
    </View>
  );
}

describe('AuthContext', () => {
  it('logs out and clears in-memory token state', async () => {
    render(
      <AuthProvider initialAccessToken="e2e-test-token">
        <AuthStatePanel />
      </AuthProvider>,
    );

    expect(await screen.findByText('로그인됨:e2e-test-token')).toBeTruthy();

    fireEvent.press(screen.getByText('로그아웃'));

    expect(await screen.findByText('로그인 필요')).toBeTruthy();
  });
});
