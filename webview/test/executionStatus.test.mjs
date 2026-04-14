import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseExecutionResultPayload,
  stringifyExecutionResultPayload,
} from '../build-tests/executionStatus.js'

test('stringifyExecutionResultPayload preserves JSON strings', () => {
  const raw = '{"status":"ok","exit_code":0}'

  assert.equal(stringifyExecutionResultPayload(raw), raw)
})

test('stringifyExecutionResultPayload serializes object payloads', () => {
  const raw = stringifyExecutionResultPayload({ status: 'ok', exit_code: 0 })

  assert.equal(raw, '{"status":"ok","exit_code":0}')
})

test('parseExecutionResultPayload parses object payloads after normalization', () => {
  const result = parseExecutionResultPayload({ status: 'ok', exit_code: 0 })

  assert.deepEqual(result, { status: 'ok', exit_code: 0 })
})
