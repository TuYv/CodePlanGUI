import assert from 'node:assert/strict'
import test from 'node:test'
import { groupReducer } from '../build-tests/groupReducer.js'

/** Convenience: build a minimal initial state for tests. */
function initState(overrides = {}) {
  return {
    groups: [],
    isLoading: false,
    error: null,
    status: {
      providerName: '',
      model: '',
      connectionState: 'unconfigured',
      contextFile: '',
    },
    themeMode: 'dark',
    approvalOpen: false,
    approvalRequestId: '',
    approvalCommand: '',
    approvalDescription: '',
    continuationInfo: null,
    currentRoundTextIndex: null,
    ...overrides,
  }
}

// ─── start ──────────────────────────────────────────────────────────

test('start: creates new assistant group with isStreaming=true', () => {
  const s = groupReducer(initState(), 'start', { msgId: 'm1' })
  assert.equal(s.isLoading, true)
  assert.equal(s.error, null)
  assert.equal(s.currentRoundTextIndex, null)
  assert.equal(s.groups.length, 1)
  assert.equal(s.groups[0].type, 'assistant')
  assert.equal(s.groups[0].id, 'm1')
  assert.equal(s.groups[0].isStreaming, true)
  assert.deepEqual(s.groups[0].children, [])
})

test('start: reuses existing assistant group when still streaming (continuation round)', () => {
  const s0 = initState({
    groups: [{ type: 'assistant', id: 'm1', children: [], isStreaming: true }],
    isLoading: true,
  })
  const s = groupReducer(s0, 'start', { msgId: 'm1' })
  assert.equal(s.groups.length, 1)
  assert.equal(s.groups[0].id, 'm1')
  assert.equal(s.groups[0].isStreaming, true)
  assert.equal(s.currentRoundTextIndex, null)
})

test('start: creates new group when previous assistant group is not streaming', () => {
  const s0 = initState({
    groups: [{ type: 'assistant', id: 'm1', children: [], isStreaming: false }],
  })
  const s = groupReducer(s0, 'start', { msgId: 'm2' })
  assert.equal(s.groups.length, 2)
  assert.equal(s.groups[1].id, 'm2')
  assert.equal(s.groups[1].isStreaming, true)
})

// ─── token ──────────────────────────────────────────────────────────

test('token: creates text child and appends to it', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Hello' })
  s = groupReducer(s, 'token', { text: ' world' })

  const group = s.groups[0]
  assert.equal(group.type, 'assistant')
  assert.equal(group.children.length, 1)
  assert.equal(group.children[0].kind, 'text')
  assert.equal(group.children[0].content, 'Hello world')
  assert.equal(group.children[0].isStreaming, true)
  assert.equal(s.currentRoundTextIndex, 0)
})

test('token: no-op when no assistant group exists', () => {
  const s = groupReducer(initState(), 'token', { text: 'Hello' })
  assert.equal(s.groups.length, 0)
})

// ─── execution_card ─────────────────────────────────────────────────

test('execution_card: adds execution child to last assistant group', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: 'list files' })

  const group = s.groups[0]
  assert.equal(group.children.length, 1)
  assert.equal(group.children[0].kind, 'execution')
  assert.equal(group.children[0].data.requestId, 'r1')
  assert.equal(group.children[0].data.command, 'ls')
  assert.equal(group.children[0].data.status, 'running')
})

// ─── log ─────────────────────────────────────────────────────────────

test('log: appends log entry to matching execution child', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'log', { requestId: 'r1', line: 'file.txt', type: 'stdout' })

  const exec = s.groups[0].children[0]
  assert.equal(exec.kind, 'execution')
  assert.equal(exec.data.logs.length, 1)
  assert.equal(exec.data.logs[0].text, 'file.txt')
  assert.equal(exec.data.logs[0].type, 'stdout')
})

test('log: does not affect other children', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Hello' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'log', { requestId: 'r1', line: 'out', type: 'stdout' })

  const textChild = s.groups[0].children[0]
  assert.equal(textChild.kind, 'text')
  assert.equal(textChild.content, 'Hello')
})

// ─── execution_status ───────────────────────────────────────────────

test('execution_status: updates execution child result', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'execution_status', { requestId: 'r1', status: 'done', result: '{"status":"ok","exit_code":0}' })

  const exec = s.groups[0].children[0]
  assert.equal(exec.kind, 'execution')
  assert.equal(exec.data.status, 'done')
  assert.deepEqual(exec.data.result, { status: 'ok', exit_code: 0 })
})

// ─── approval_request ───────────────────────────────────────────────

test('approval_request: opens approval and updates execution status to waiting', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'rm', description: '' })
  s = groupReducer(s, 'approval_request', { requestId: 'r1', command: 'rm', description: 'delete file' })

  const exec = s.groups[0].children[0]
  assert.equal(exec.kind, 'execution')
  assert.equal(exec.data.status, 'waiting')
  assert.equal(s.approvalOpen, true)
  assert.equal(s.approvalRequestId, 'r1')
  assert.equal(s.approvalCommand, 'rm')
  assert.equal(s.approvalDescription, 'delete file')
})

// ─── round_end ──────────────────────────────────────────────────────

test('round_end: discards current round text child (intermediate tokens before tool_calls)', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Let me help...' })  // intermediate text
  assert.equal(s.currentRoundTextIndex, 0)

  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'round_end', { msgId: 'm1' })

  // Text child should be discarded, only execution card remains
  const group = s.groups[0]
  assert.equal(group.children.length, 1)
  assert.equal(group.children[0].kind, 'execution')
  assert.equal(s.currentRoundTextIndex, null)
})

test('round_end: no-op when currentRoundTextIndex is null', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  assert.equal(s.currentRoundTextIndex, null)

  const before = { ...s }
  s = groupReducer(s, 'round_end', { msgId: 'm1' })
  assert.equal(s.groups[0].children.length, 1)
})

// ─── end ────────────────────────────────────────────────────────────

test('end: stops loading and clears streaming flags', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Hi' })
  s = groupReducer(s, 'end', { msgId: 'm1' })

  assert.equal(s.isLoading, false)
  assert.equal(s.continuationInfo, null)
  assert.equal(s.currentRoundTextIndex, null)

  const group = s.groups[0]
  assert.equal(group.isStreaming, false)
  assert.equal(group.children[0].isStreaming, false)
})

// ─── error ──────────────────────────────────────────────────────────

test('error: sets runtime error and stops all streaming', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'partial' })
  s = groupReducer(s, 'continuation', { current: 1, max: 2 })
  s = groupReducer(s, 'error', { message: 'Something broke' })

  assert.equal(s.isLoading, false)
  assert.equal(s.continuationInfo, null)
  assert.equal(s.currentRoundTextIndex, null)
  assert.equal(s.groups[0].isStreaming, false)
  assert.equal(s.groups[0].children[0].isStreaming, false)
  assert.deepEqual(s.error, { type: 'runtime', message: 'Something broke' })
})

// ─── structured_error ───────────────────────────────────────────────

test('structured_error: sets typed error and stops streaming', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: '' })
  s = groupReducer(s, 'structured_error', { type: 'config', message: 'No API key', action: 'openSettings' })

  assert.equal(s.isLoading, false)
  assert.equal(s.groups[0].isStreaming, false)
  assert.deepEqual(s.error, { type: 'config', message: 'No API key', action: 'openSettings' })
})

// ─── continuation ──────────────────────────────────────────────────

test('continuation: sets continuation info', () => {
  const s = groupReducer(initState(), 'continuation', { current: 2, max: 5 })
  assert.deepEqual(s.continuationInfo, { current: 2, max: 5 })
})

// ─── restore_messages ──────────────────────────────────────────────

test('restore_messages: converts flat messages to grouped structure', () => {
  const messagesJson = JSON.stringify([
    { id: 'u1', role: 'user', content: 'hello' },
    { id: 'a1', role: 'assistant', content: '' },
    { id: 'a2', role: 'assistant', content: 'world' },
    { id: 's1', role: 'system', content: 'ignore me' },
  ])
  const s = groupReducer(initState(), 'restore_messages', { messages: messagesJson })

  assert.equal(s.groups.length, 2)
  // human group
  assert.equal(s.groups[0].type, 'human')
  assert.equal(s.groups[0].id, 'u1')
  assert.equal(s.groups[0].message.content, 'hello')
  // assistant group (a2 only, a1 was empty)
  assert.equal(s.groups[1].type, 'assistant')
  assert.equal(s.groups[1].id, 'a2')
  assert.equal(s.groups[1].children.length, 1)
  assert.equal(s.groups[1].children[0].kind, 'text')
  assert.equal(s.groups[1].children[0].content, 'world')
  assert.equal(s.groups[1].children[0].isStreaming, false)
})

test('restore_messages: merges consecutive assistant messages into one group', () => {
  const messagesJson = JSON.stringify([
    { id: 'u1', role: 'user', content: 'hello' },
    { id: 'a1', role: 'assistant', content: 'part 1' },
    { id: 'a2', role: 'assistant', content: 'part 2' },
  ])
  const s = groupReducer(initState(), 'restore_messages', { messages: messagesJson })

  assert.equal(s.groups.length, 2)
  assert.equal(s.groups[0].type, 'human')
  assert.equal(s.groups[1].type, 'assistant')
  // Both assistant messages merged into one group
  assert.equal(s.groups[1].children.length, 2)
  assert.equal(s.groups[1].children[0].content, 'part 1')
  assert.equal(s.groups[1].children[1].content, 'part 2')
})

// ─── status ─────────────────────────────────────────────────────────

test('status: merges provider status preserving contextFile', () => {
  const s0 = initState({
    status: { providerName: '', model: '', connectionState: 'unconfigured', contextFile: 'file.ts' },
  })
  const s = groupReducer(s0, 'status', { providerName: 'OpenAI', model: 'gpt-4', connectionState: 'ready' })
  assert.equal(s.status.providerName, 'OpenAI')
  assert.equal(s.status.model, 'gpt-4')
  assert.equal(s.status.connectionState, 'ready')
  assert.equal(s.status.contextFile, 'file.ts')
})

// ─── context_file ──────────────────────────────────────────────────

test('context_file: updates context file in status', () => {
  const s = groupReducer(initState(), 'context_file', { fileName: 'src/App.tsx' })
  assert.equal(s.status.contextFile, 'src/App.tsx')
})

// ─── theme ──────────────────────────────────────────────────────────

test('theme: toggles theme mode', () => {
  const s = groupReducer(initState(), 'theme', { mode: 'light' })
  assert.equal(s.themeMode, 'light')
})

// ─── unknown event ──────────────────────────────────────────────────

test('unknown event type returns state unchanged', () => {
  const s0 = initState({ isLoading: true })
  const s = groupReducer(s0, 'unknown_event', {})
  assert.deepEqual(s, s0)
})

// ─── full streaming lifecycle ───────────────────────────────────────

test('full streaming lifecycle: start → token → token → end', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Hello' })
  s = groupReducer(s, 'token', { text: ' world' })
  s = groupReducer(s, 'end', { msgId: 'm1' })

  assert.equal(s.isLoading, false)
  assert.equal(s.groups.length, 1)
  const group = s.groups[0]
  assert.equal(group.type, 'assistant')
  assert.equal(group.isStreaming, false)
  assert.equal(group.children.length, 1)
  assert.equal(group.children[0].content, 'Hello world')
  assert.equal(group.children[0].isStreaming, false)
})

// ─── tool call flow: intermediate tokens discarded ──────────────────

test('tool call flow: tokens before tool_calls are discarded by round_end', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })

  // Round 1: intermediate text + execution card
  s = groupReducer(s, 'token', { text: 'Let me help you with that.' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'execution_status', { requestId: 'r1', status: 'done', result: '{"status":"ok","exit_code":0}' })
  s = groupReducer(s, 'round_end', { msgId: 'm1' })

  // Round 2: reuse group, new text
  s = groupReducer(s, 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Here are the files.' })
  s = groupReducer(s, 'end', { msgId: 'm1' })

  const group = s.groups[0]
  assert.equal(s.groups.length, 1)
  assert.equal(group.children.length, 2)
  assert.equal(group.children[0].kind, 'execution')
  assert.equal(group.children[0].data.requestId, 'r1')
  assert.equal(group.children[1].kind, 'text')
  assert.equal(group.children[1].content, 'Here are the files.')
  assert.equal(group.isStreaming, false)
})

// ─── multiple tool calls ────────────────────────────────────────────

test('multiple tool calls within one assistant group', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })

  // Round 1: first tool call
  s = groupReducer(s, 'token', { text: 'Searching...' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'grep foo', description: '' })
  s = groupReducer(s, 'execution_status', { requestId: 'r1', status: 'done', result: '{"status":"ok","exit_code":0}' })
  s = groupReducer(s, 'round_end', { msgId: 'm1' })

  // Round 2: second tool call
  s = groupReducer(s, 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'Now editing...' })
  s = groupReducer(s, 'execution_card', { requestId: 'r2', command: 'sed -i', description: '' })
  s = groupReducer(s, 'execution_status', { requestId: 'r2', status: 'done', result: '{"status":"ok","exit_code":0}' })
  s = groupReducer(s, 'round_end', { msgId: 'm1' })

  // Round 3: final answer
  s = groupReducer(s, 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'All done!' })
  s = groupReducer(s, 'end', { msgId: 'm1' })

  assert.equal(s.groups.length, 1)
  const group = s.groups[0]
  // 2 execution cards + 1 final text
  assert.equal(group.children.length, 3)
  assert.equal(group.children[0].kind, 'execution')
  assert.equal(group.children[1].kind, 'execution')
  assert.equal(group.children[2].kind, 'text')
  assert.equal(group.children[2].content, 'All done!')
})

// ─── multiple assistant groups (different turns) ────────────────────

test('multiple assistant turns create separate groups', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'First' })
  s = groupReducer(s, 'end', { msgId: 'm1' })

  s = groupReducer(s, 'start', { msgId: 'm2' })
  s = groupReducer(s, 'token', { text: 'Second' })
  s = groupReducer(s, 'end', { msgId: 'm2' })

  assert.equal(s.groups.length, 2)
  assert.equal(s.groups[0].children[0].content, 'First')
  assert.equal(s.groups[1].children[0].content, 'Second')
})

// ─── error stops streaming on all assistant groups ──────────────────

test('error: stops streaming on all assistant groups', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'token', { text: 'partial' })
  // Simulate a second group also streaming (edge case)
  s = groupReducer(s, 'error', { message: 'fail' })

  assert.equal(s.groups[0].isStreaming, false)
  assert.equal(s.groups[0].children[0].isStreaming, false)
  assert.equal(s.isLoading, false)
})

// ─── approval_request: updates correct execution child ──────────────

test('approval_request: updates only the matching execution child', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'execution_card', { requestId: 'r2', command: 'rm', description: '' })
  s = groupReducer(s, 'approval_request', { requestId: 'r2', command: 'rm', description: 'delete' })

  assert.equal(s.groups[0].children[0].data.status, 'running')  // r1 unchanged
  assert.equal(s.groups[0].children[1].data.status, 'waiting')  // r2 updated
})

// ─── log: updates correct execution child ───────────────────────────

test('log: appends to only the matching execution child', () => {
  let s = groupReducer(initState(), 'start', { msgId: 'm1' })
  s = groupReducer(s, 'execution_card', { requestId: 'r1', command: 'ls', description: '' })
  s = groupReducer(s, 'execution_card', { requestId: 'r2', command: 'cat', description: '' })
  s = groupReducer(s, 'log', { requestId: 'r2', line: 'output', type: 'stdout' })

  assert.equal(s.groups[0].children[0].data.logs, undefined)  // r1 no logs
  assert.equal(s.groups[0].children[1].data.logs.length, 1)   // r2 has log
})
