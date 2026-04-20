import './ErrorBanner.css'

interface Props {
  errorType: 'auth' | 'quota' | 'temp' | 'generic'
  message: string
  onClose: () => void
  onAction?: () => void
}

const ERROR_CONFIG = {
  auth: {
    label: '配置错误',
    icon: '🔐',
    actionLabel: '打开设置',
  },
  quota: {
    label: '配额不足',
    icon: '💰',
    actionLabel: '打开设置',
  },
  temp: {
    label: '临时错误',
    icon: '⏳',
    actionLabel: '重试',
  },
  generic: {
    label: '未知错误',
    icon: '❓',
    actionLabel: null,
  },
} as const

export function ErrorBanner({ errorType, message, onClose, onAction }: Props) {
  const config = ERROR_CONFIG[errorType] ?? ERROR_CONFIG.generic

  return (
    <div className={`error-banner error-banner-${errorType}`}>
      <span className="error-banner-icon">{config.icon}</span>
      <div className="error-banner-content">
        <div className="error-banner-label">{config.label}</div>
        <div className="error-banner-message">{message}</div>
      </div>
      <div className="error-banner-actions">
        {config.actionLabel && onAction && (
          <button className="error-banner-action" onClick={onAction}>
            {config.actionLabel}
          </button>
        )}
        <button className="error-banner-close" onClick={onClose}>
          ✕
        </button>
      </div>
    </div>
  )
}
