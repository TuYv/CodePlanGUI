import type { ExecutionCardData, ExecutionStatus, LogEntry } from './components/ExecutionCard.js'
import type { BridgeError, BridgeStatus } from './types/bridge.js'
import { parseExecutionResultPayload } from './executionStatus.js'
import { applyBridgeStatus, applyContextFile } from './statusState.js'

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'execution'
  content: string
  isStreaming?: boolean
  execution?: ExecutionCardData
}

export interface AppState {
  messages: Message[]
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: 'dark' | 'light'
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
}

function restoreFlatMessages(raw: Array<{ id: string; role: string; content: string }>): Message[] {
  return raw.flatMap((message) => {
    if (message.role !== 'user' && message.role !== 'assistant') return []
    if (message.role === 'assistant' && message.content.trim().length === 0) return []
    return [{
      id: message.id,
      role: message.role as 'user' | 'assistant',
      content: message.content,
      isStreaming: false,
    }]
  })
}

export function eventReducer(state: AppState, type: string, payload: any): AppState {
  switch (type) {
    case 'start':
      return {
        ...state,
        isLoading: true,
        error: null,
        messages: [...state.messages, { id: payload.msgId, role: 'assistant' as const, content: '', isStreaming: true }],
      }

    case 'token':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, content: m.content + payload.text } : m
        ),
      }

    case 'end':
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
      }

    case 'round_end':
      return state

    case 'error':
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: 'runtime' as const, message: payload.message },
      }

    case 'structured_error':
      return {
        ...state,
        isLoading: false,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: payload.type, message: payload.message, action: payload.action },
      }

    case 'execution_card':
      return {
        ...state,
        messages: [...state.messages, {
          id: payload.requestId,
          role: 'execution' as const,
          content: '',
          execution: { requestId: payload.requestId, command: payload.command, status: 'running' as ExecutionStatus },
        }],
      }

    case 'approval_request':
      return {
        ...state,
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: 'waiting' as ExecutionStatus } }
            : m
        ),
      }

    case 'execution_status': {
      const result = parseExecutionResultPayload(payload.result)
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: payload.status as ExecutionStatus, result } }
            : m
        ),
      }
    }

    case 'log':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, logs: [...(m.execution?.logs || []), { text: payload.line, type: payload.type as LogEntry['type'] }] } }
            : m
        ),
      }

    case 'continuation':
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case 'remove_message':
      return { ...state, messages: state.messages.filter(m => m.id !== payload.msgId) }

    case 'restore_messages':
      return { ...state, messages: restoreFlatMessages(JSON.parse(payload.messages)) }

    case 'status':
      return { ...state, status: applyBridgeStatus(state.status, payload) }

    case 'context_file':
      return { ...state, status: applyContextFile(state.status, payload.fileName) }

    case 'theme':
      return { ...state, themeMode: payload.mode }

    default:
      return state
  }
}
