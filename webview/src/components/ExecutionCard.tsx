import { useState } from 'react'
import { Typography } from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  LoadingOutlined,
  StopOutlined,
  LockOutlined,
} from '@ant-design/icons'

export type ExecutionStatus = 'waiting' | 'running' | 'done' | 'blocked' | 'denied' | 'timeout'

export interface ExecutionCardData {
  requestId: string
  command: string
  status: ExecutionStatus
  result?: {
    status: 'ok' | 'error' | 'blocked' | 'denied' | 'timeout'
    exit_code?: number
    stdout?: string
    stderr?: string
    duration_ms?: number
    truncated?: boolean
    reason?: string
    timeout_seconds?: number
  }
}

interface ExecutionCardProps {
  data: ExecutionCardData
}

const PREVIEW_LINES = 5

function OutputBlock({ text, label }: { text: string; label: string }) {
  const lines = text.split('\n')
  const [expanded, setExpanded] = useState(lines.length <= PREVIEW_LINES)
  const visible = expanded ? lines : lines.slice(0, PREVIEW_LINES)

  return (
    <div style={{ marginTop: 8 }}>
      {label && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {label}
        </Typography.Text>
      )}
      <pre
        style={{
          margin: '4px 0',
          fontSize: 12,
          overflowX: 'auto',
          background: 'rgba(0,0,0,0.04)',
          padding: '6px 10px',
          borderRadius: 4,
        }}
      >
        {visible.join('\n')}
      </pre>
      {!expanded && (
        <Typography.Link style={{ fontSize: 12 }} onClick={() => setExpanded(true)}>
          ▼ show {lines.length - PREVIEW_LINES} more lines
        </Typography.Link>
      )}
    </div>
  )
}

export function ExecutionCard({ data }: ExecutionCardProps) {
  const { command, status, result } = data

  const header = () => {
    switch (status) {
      case 'waiting':
        return <><LockOutlined style={{ marginRight: 6 }} />等待审批</>
      case 'running':
        return <><LoadingOutlined style={{ marginRight: 6 }} />执行中</>
      case 'blocked':
        return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />已拦截 · {result?.reason}</>
      case 'denied':
        return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />用户拒绝</>
      case 'timeout':
        return <><ClockCircleOutlined style={{ marginRight: 6, color: '#faad14' }} />超时 · {result?.timeout_seconds}s</>
      case 'done': {
        if (!result) return null
        const success = result.status === 'ok'
        const duration = result.duration_ms ? `${(result.duration_ms / 1000).toFixed(1)}s` : ''
        return success
          ? <><CheckCircleOutlined style={{ marginRight: 6, color: '#52c41a' }} />完成 · exit {result.exit_code} · {duration}</>
          : <><CloseCircleOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />失败 · exit {result.exit_code} · {duration}</>
      }
    }
  }

  return (
    <div
      style={{
        border: '1px solid rgba(128,128,128,0.2)',
        borderRadius: 8,
        padding: '10px 14px',
        margin: '6px 0',
        fontSize: 13,
      }}
    >
      <div style={{ marginBottom: 6 }}>{header()}</div>
      <Typography.Text code style={{ fontSize: 12 }}>$ {command}</Typography.Text>
      {result?.stdout && <OutputBlock text={result.stdout} label="stdout" />}
      {result?.stderr && <OutputBlock text={result.stderr} label="stderr" />}
      {result?.truncated && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          [output truncated]
        </Typography.Text>
      )}
    </div>
  )
}
