import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AtmosphereProvider } from 'atmosphere.js/react';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AtmosphereProvider config={{ logLevel: 'info' }}>
      <App />
    </AtmosphereProvider>
  </StrictMode>,
);
