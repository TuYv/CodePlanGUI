import { PlusOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'

interface Props {
  onNewChat: () => void
}

export function ProviderBar({ onNewChat }: Props) {
  return (
    <div className="provider-bar">
      <div>
        <Typography.Text className="provider-eyebrow">CodePlanGUI</Typography.Text>
        <Typography.Title level={5} className="provider-title">
          Editorial Terminal
        </Typography.Title>
      </div>
      <Button
        type="text"
        size="small"
        icon={<PlusOutlined />}
        onClick={onNewChat}
        title="New Chat"
        className="provider-action"
      />
    </div>
  )
}
