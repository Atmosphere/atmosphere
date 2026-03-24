import { createRoot } from 'react-dom/client';
import { AtmosphereProvider } from 'atmosphere.js/react';
import { App } from './App';

// StrictMode intentionally omitted: it double-fires effects in dev mode,
// creating duplicate WebSocket subscriptions that cause duplicated agent events.
createRoot(document.getElementById('root')!).render(
  <AtmosphereProvider config={{ logLevel: 'info' }}>
    <App />
  </AtmosphereProvider>,
);
