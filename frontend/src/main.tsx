/*
 * Monaco is configured with the locally bundled editor before the app renders,
 * so no remote (CDN) Monaco loader is requested at runtime.
 */
import './monacoSetup';

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';
import './styles-stage2.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
