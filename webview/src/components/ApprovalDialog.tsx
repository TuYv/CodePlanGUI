import { useState } from 'react'
import { Button, Checkbox, Modal, Typography } from 'antd'
import { WarningOutlined } from '@ant-design/icons'

interface ApprovalDialogProps {
  open: boolean
  command: string
  description: string
  onAllow: (addToWhitelist: boolean) => void
  onDeny: () => void
}

function extractBaseCommand(command: string): string {
  const stripped = command.trimStart()
  const base = stripped.split(/\s+|[|;><&]/)[0]?.trim() ?? ''
  return base.substring(base.lastIndexOf('/') + 1)
}

export function ApprovalDialog({ open, command, description, onAllow, onDeny }: ApprovalDialogProps) {
  const [rememberCommand, setRememberCommand] = useState(false)

  const handleAllow = () => {
    onAllow(rememberCommand)
    setRememberCommand(false)
  }

  const handleDeny = () => {
    onDeny()
    setRememberCommand(false)
  }

  const baseCommand = extractBaseCommand(command)
  const whitelistLabel = baseCommand
    ? `允许所有 ${baseCommand} 命令自动执行`
    : '记住此命令，以后自动执行'

  return (
    <Modal
      open={open}
      getContainer={false}
      title={<span><WarningOutlined style={{ color: '#faad14', marginRight: 8 }} />AI 请求执行命令</span>}
      footer={[
        <Button key="deny" onClick={handleDeny}>拒绝</Button>,
        <Button key="allow" type="primary" danger onClick={handleAllow}>允许执行</Button>,
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
      <div style={{ marginTop: 12 }}>
        <Checkbox
          checked={rememberCommand}
          onChange={(e) => setRememberCommand(e.target.checked)}
        >
          {whitelistLabel}
        </Checkbox>
      </div>
    </Modal>
  )
}
