import type { ExecutionCardData } from './components/ExecutionCard.js'

type ExecutionResult = ExecutionCardData['result']

export function stringifyExecutionResultPayload(payload: unknown): string {
  if (typeof payload === 'string') return payload
  if (payload == null) return '{}'

  try {
    return JSON.stringify(payload)
  } catch {
    return '{}'
  }
}

export function parseExecutionResultPayload(payload: unknown): ExecutionResult | undefined {
  const raw = stringifyExecutionResultPayload(payload)
  try {
    return JSON.parse(raw) as ExecutionResult
  } catch {
    return undefined
  }
}
