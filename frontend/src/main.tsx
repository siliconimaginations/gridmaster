import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './index.css'

// Install the __e2e store bridge in dev and e2e builds.
// Dynamic import + MODE guard ensures the bridge module is never bundled
// in production even if the guard were accidentally removed.
if (import.meta.env.MODE !== 'production') {
  // eslint-disable-next-line @typescript-eslint/no-floating-promises
  import('./e2ebridge').then(({ installE2EBridge }) => installE2EBridge())
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
