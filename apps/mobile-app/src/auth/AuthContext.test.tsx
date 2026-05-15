import { fireEvent, render, screen } from '@testing-library/react-native';
import { Linking, Pressable, Text, View } from 'react-native';
import { useAuth } from './AuthContext';
import { AuthProvider } from './AuthContext';

function AuthStatePanel() {
  const { isAuthenticated, accessToken, error, logout } = useAuth();

  return (
    <View>
      <Text>{isAuthenticated ? `로그인됨:${accessToken}` : '로그인 필요'}</Text>
      {error ? <Text>{`오류:${error}`}</Text> : null}
      <Pressable onPress={() => void logout()}>
        <Text>로그아웃</Text>
      </Pressable>
    </View>
  );
}

describe('AuthContext', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

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

  it('subscribes to auth callback events before reading the initial URL', async () => {
    const callOrder: string[] = [];
    const remove = jest.fn();

    jest.spyOn(Linking, 'addEventListener').mockImplementation(() => {
      callOrder.push('addEventListener');
      return { remove } as unknown as ReturnType<typeof Linking.addEventListener>;
    });
    jest.spyOn(Linking, 'getInitialURL').mockImplementation(() => {
      callOrder.push('getInitialURL');
      return Promise.resolve('exp://127.0.0.1:8081');
    });

    render(
      <AuthProvider initialAccessToken={null}>
        <AuthStatePanel />
      </AuthProvider>,
    );

    expect(await screen.findByText('로그인 필요')).toBeTruthy();
    expect(callOrder).toEqual(['addEventListener', 'getInitialURL']);
    expect(screen.queryByText(/^오류:/)).toBeNull();
  });
});
