import assert from 'node:assert/strict'
import test from 'node:test'
import { eventReducer } from '../build-tests/eventReducer.js'

/** Convenience: build a minimal initial state for tests. */
function initState(overrides = {}) {
  return {
    messages: [],
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
    ...overrides,
  }
}

// ─── start ──────────────────────────────────────────────────────────

test('start: adds streaming message and sets loading', () => {
  const s = eventReducer(initState(), 'start', { msgId: 'm1' })
  assert.equal(s.isLoading, true)
  assert.equal(s.error, null)
  assert.equal(s.messages.length, 1)
  assert.equal(s.messages[0].id, 'm1')
  assert.equal(s.messages[0].role, 'assistant')
  assert.equal(s.messages[0].content, '')
  assert.equal(s.messages[0].isStreaming, true)
})

// ─── token ──────────────────────────────────────────────────────────

test('token: appends text to streaming message', () => {
  const s0 = initState({ messages: [{ id: 'm1', role: 'assistant', content: 'Hello', isStreaming: true }] })
  const s = eventReducer(s0, 'token', { text: ' world' })
  assert.equal(s.messages[0].content, 'Hello world')
})

test('token: does not touch non-streaming messages', () => {
  const s0 = initState({ messages: [{ id: 'm1', role: 'assistant', content: 'Done', isStreaming: false }] })
  const s = eventReducer(s0, 'token', { text: ' extra' })
  assert.equal(s.messages[0].content, 'Done')
})

// ─── end ────────────────────────────────────────────────────────────

test('end: stops loading and clears streaming flag', () => {
  const s0 = initState({
    isLoading: true,
    continuationInfo: { current: 2, max: 3 },
    messages: [{ id: 'm1', role: 'assistant', content: 'Hi', isStreaming: true }],
  })
  const s = eventReducer(s0, 'end', { msgId: 'm1' })
  assert.equal(s.isLoading, false)
  assert.equal(s.continuationInfo, null)
  assert.equal(s.messages[0].isStreaming, false)
})

// ─── round_end ──────────────────────────────────────────────────────

test('round_end: no-op in phase 1', () => {
  const s0 = initState({ isLoading: true })
  const s = eventReducer(s0, 'round_end', { msgId: 'm1' })
  assert.deepEqual(s, s0)
})

// ─── error ──────────────────────────────────────────────────────────

test('error: sets runtime error and stops loading', () => {
  const s0 = initState({
    isLoading: true,
    continuationInfo: { current: 1, max: 2 },
    messages: [{ id: 'm1', role: 'assistant', content: 'partial', isStreaming: true }],
  })
  const s = eventReducer(s0, 'error', { message: 'Something broke' })
  assert.equal(s.isLoading, false)
  assert.equal(s.continuationInfo, null)
  assert.equal(s.messages[0].isStreaming, false)
  assert.deepEqual(s.error, { type: 'runtime', message: 'Something broke' })
})

// ─── structured_error ───────────────────────────────────────────────

test('structured_error: sets typed error', () => {
  const s0 = initState({ isLoading: true, messages: [{ id: 'm1', role: 'assistant', content: '', isStreaming: true }] })
  const s = eventReducer(s0, 'structured_error', { type: 'config', message: 'No API key', action: 'openSettings' })
  assert.equal(s.isLoading, false)
  assert.equal(s.messages[0].isStreaming, false)
  assert.deepEqual(s.error, { type: 'config', message: 'No API key', action: 'openSettings' })
})

// ─── execution_card ────────────────────────────────────────────────

test('execution_card: adds execution message with running status', () => {
  const s = eventReducer(initState(), 'execution_card', { requestId: 'r1', command: 'ls', description: 'list files' })
  assert.equal(s.messages.length, 1)
  assert.equal(s.messages[0].id, 'r1')
  assert.equal(s.messages[0].role, 'execution')
  assert.equal(s.messages[0].execution.command, 'ls')
  assert.equal(s.messages[0].execution.status, 'running')
})

// ─── approval_request ──────────────────────────────────────────────

test('approval_request: opens approval and updates execution status', () => {
  const s0 = initState({
    messages: [{ id: 'r1', role: 'execution', content: '', execution: { requestId: 'r1', command: 'rm', status: 'running' } }],
  })
  const s = eventReducer(s0, 'approval_request', { requestId: 'r1', command: 'rm', description: 'delete file' })
  assert.equal(s.approvalOpen, true)
  assert.equal(s.approvalRequestId, 'r1')
  assert.equal(s.approvalCommand, 'rm')
  assert.equal(s.approvalDescription, 'delete file')
  assert.equal(s.messages[0].execution.status, 'waiting')
})

// ─── execution_status ──────────────────────────────────────────────

test('execution_status: updates execution result', () => {
  const s0 = initState({
    messages: [{ id: 'r1', role: 'execution', content: '', execution: { requestId: 'r1', command: 'ls', status: 'running' } }],
  })
  const resultJson = '{"status":"ok","exit_code":0}'
  const s = eventReducer(s0, 'execution_status', { requestId: 'r1', status: 'done', result: resultJson })
  assert.equal(s.messages[0].execution.status, 'done')
  assert.deepEqual(s.messages[0].execution.result, { status: 'ok', exit_code: 0 })
})

// ─── log ────────────────────────────────────────────────────────────

test('log: appends log entry to matching execution message', () => {
  const s0 = initState({
    messages: [{ id: 'r1', role: 'execution', content: '', execution: { requestId: 'r1', command: 'ls', status: 'running', logs: [] } }],
  })
  const s = eventReducer(s0, 'log', { requestId: 'r1', line: 'file.txt', type: 'stdout' })
  assert.equal(s.messages[0].execution.logs.length, 1)
  assert.equal(s.messages[0].execution.logs[0].text, 'file.txt')
  assert.equal(s.messages[0].execution.logs[0].type, 'stdout')
})

test('log: does not affect other messages', () => {
  const s0 = initState({
    messages: [
      { id: 'm1', role: 'assistant', content: 'hi' },
      { id: 'r1', role: 'execution', content: '', execution: { requestId: 'r1', command: 'ls', status: 'running', logs: [] } },
    ],
  })
  const s = eventReducer(s0, 'log', { requestId: 'r1', line: 'out', type: 'stdout' })
  assert.equal(s.messages[0].content, 'hi')
  assert.equal(s.messages[1].execution.logs.length, 1)
})

// ─── continuation ──────────────────────────────────────────────────

test('continuation: sets continuation info', () => {
  const s = eventReducer(initState(), 'continuation', { current: 2, max: 5 })
  assert.deepEqual(s.continuationInfo, { current: 2, max: 5 })
})

// ─── remove_message ────────────────────────────────────────────────

test('remove_message: removes message by id', () => {
  const s0 = initState({
    messages: [
      { id: 'm1', role: 'user', content: 'hello' },
      { id: 'm2', role: 'assistant', content: 'world' },
    ],
  })
  const s = eventReducer(s0, 'remove_message', { msgId: 'm1' })
  assert.equal(s.messages.length, 1)
  assert.equal(s.messages[0].id, 'm2')
})

// ─── restore_messages ──────────────────────────────────────────────

test('restore_messages: restores user and non-empty assistant messages', () => {
  const messagesJson = JSON.stringify([
    { id: 'u1', role: 'user', content: 'hello' },
    { id: 'a1', role: 'assistant', content: '' },
    { id: 'a2', role: 'assistant', content: 'world' },
    { id: 's1', role: 'system', content: 'ignore me' },
  ])
  const s = eventReducer(initState(), 'restore_messages', { messages: messagesJson })
  assert.equal(s.messages.length, 2)
  assert.equal(s.messages[0].id, 'u1')
  assert.equal(s.messages[1].id, 'a2')
  assert.equal(s.messages.every(m => m.isStreaming === false), true)
})

// ─── status ─────────────────────────────────────────────────────────

test('status: merges provider status preserving contextFile', () => {
  const s0 = initState({
    status: { providerName: '', model: '', connectionState: 'unconfigured', contextFile: 'file.ts' },
  })
  const s = eventReducer(s0, 'status', { providerName: 'OpenAI', model: 'gpt-4', connectionState: 'ready' })
  assert.equal(s.status.providerName, 'OpenAI')
  assert.equal(s.status.model, 'gpt-4')
  assert.equal(s.status.connectionState, 'ready')
  assert.equal(s.status.contextFile, 'file.ts')
})

// ─── context_file ──────────────────────────────────────────────────

test('context_file: updates context file in status', () => {
  const s = eventReducer(initState(), 'context_file', { fileName: 'src/App.tsx' })
  assert.equal(s.status.contextFile, 'src/App.tsx')
})

// ─── theme ──────────────────────────────────────────────────────────

test('theme: toggles theme mode', () => {
  const s = eventReducer(initState(), 'theme', { mode: 'light' })
  assert.equal(s.themeMode, 'light')
})

// ─── unknown event ──────────────────────────────────────────────────

test('unknown event type returns state unchanged', () => {
  const s0 = initState({ isLoading: true })
  const s = eventReducer(s0, 'unknown_event', {})
  assert.deepEqual(s, s0)
})

// ─── streaming lifecycle ────────────────────────────────────────────

test('full streaming lifecycle: start → token → token → end', () => {
  let s = eventReducer(initState(), 'start', { msgId: 'm1' })
  s = eventReducer(s, 'token', { text: 'Hello' })
  s = eventReducer(s, 'token', { text: ' world' })
  s = eventReducer(s, 'end', { msgId: 'm1' })

  assert.equal(s.isLoading, false)
  assert.equal(s.messages.length, 1)
  assert.equal(s.messages[0].content, 'Hello world')
  assert.equal(s.messages[0].isStreaming, false)
})

// ─── multiple messages ─────────────────────────────────────────────

test('multiple messages preserved through events', () => {
  let s = initState()
  s = eventReducer(s, 'start', { msgId: 'm1' })
  s = eventReducer(s, 'token', { text: 'First' })
  s = eventReducer(s, 'end', { msgId: 'm1' })
  s = eventReducer(s, 'start', { msgId: 'm2' })
  s = eventReducer(s, 'token', { text: 'Second' })
  s = eventReducer(s, 'end', { msgId: 'm2' })

  assert.equal(s.messages.length, 2)
  assert.equal(s.messages[0].content, 'First')
  assert.equal(s.messages[1].content, 'Second')
})
