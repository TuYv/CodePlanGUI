import { Button } from 'antd'
import type { BridgeError } from '../types/bridge'
import './ErrorBanner.css'

interface Props {
  error: BridgeError
  onClose: () => void
  onAction?: (action: 'openSettings' | 'retry') => void
}

const ERROR_CONFIG = {
  config: {
    label: '配置错误',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
      </svg>
    ),
    cssClass: 'error-banner-config',
  },
  quota: {
    label: '配额不足',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="12" y1="1" x2="12" y2="23"/>
        <path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
      </svg>
    ),
    cssClass: 'error-banner-quota',
  },
  network: {
    label: '网络错误',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
      </svg>
    ),
    cssClass: 'error-banner-network',
  },
  runtime: {
    label: '操作失败',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"/>
        <line x1="15" y1="9" x2="9" y2="15"/>
        <line x1="9" y1="9" x2="15" y2="15"/>
      </svg>
    ),
    cssClass: 'error-banner-runtime',
  },
}

export function ErrorBanner({ error, onClose, onAction }: Props) {
  const config = ERROR_CONFIG[error.type as keyof typeof ERROR_CONFIG] ?? ERROR_CONFIG.runtime

  return (
    <div className={`error-banner ${config.cssClass}`}>
      <div className="error-banner-icon">{config.icon}</div>
      <div className="error-banner-body">
        <div className="error-banner-header">
          <span className="error-banner-label">{config.label}</span>
          <button className="error-banner-close" onClick={onClose} aria-label="关闭">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div className="error-banner-message">{error.message}</div>
      </div>
      {error.action === 'openSettings' && (
        <Button
          size="small"
          className="error-banner-btn-settings"
          onClick={() => onAction?.('openSettings')}
        >
          打开设置
        </Button>
      )}
      {error.action === 'retry' && (
        <Button
          size="small"
          className="error-banner-btn-retry"
          onClick={() => onAction?.('retry')}
        >
          重试
        </Button>
      )}
    </div>
  )
}
