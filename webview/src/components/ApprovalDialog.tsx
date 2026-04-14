import { Button, Modal, Typography } from 'antd'
import { WarningOutlined } from '@ant-design/icons'

interface ApprovalDialogProps {
  open: boolean
  command: string
  description: string
  onAllow: () => void
  onDeny: () => void
}

export function ApprovalDialog({ open, command, description, onAllow, onDeny }: ApprovalDialogProps) {
  return (
    <Modal
      open={open}
      getContainer={false}
      title={<span><WarningOutlined style={{ color: '#faad14', marginRight: 8 }} />AI 请求执行命令</span>}
      footer={[
        <Button key="deny" onClick={onDeny}>拒绝</Button>,
        <Button key="allow" type="primary" danger onClick={onAllow}>允许执行</Button>,
      ]}
      closable={false}
      maskClosable={false}
    >
      <div style={{ marginBottom: 12 }}>
        <Typography.Text
          code
          style={{ fontSize: 13, display: 'block', padding: '8px 12px', background: 'rgba(0,0,0,0.06)', borderRadius: 6 }}
        >
          $ {command}
        </Typography.Text>
      </div>
      {description && (
        <Typography.Text type="secondary">{description}</Typography.Text>
      )}
    </Modal>
  )
}
