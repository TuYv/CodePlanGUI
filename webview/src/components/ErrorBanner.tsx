import { Alert, Button, Space } from 'antd'
import type { BridgeError } from '../types/bridge'

interface Props {
  error: BridgeError
  onClose: () => void
}

export function ErrorBanner({ error, onClose }: Props) {
  const alertType = error.type === 'config' ? 'warning' : 'error'

  const action = error.action === 'openSettings' ? (
    <Button size="small" type="link" onClick={() => window.__bridge?.openSettings()}>
      打开设置
    </Button>
  ) : error.action === 'retry' ? (
    <Button size="small" type="link" onClick={onClose}>
      关闭
    </Button>
  ) : undefined

  return (
    <Alert
      message={error.message}
      type={alertType}
      closable
      onClose={onClose}
      className="error-banner"
      action={action ? <Space>{action}</Space> : undefined}
    />
  )
}
