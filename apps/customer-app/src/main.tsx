// 앱 부트스트랩 진입점: 루트 컴포넌트를 렌더링하거나 등록합니다.
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
