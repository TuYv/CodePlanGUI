import assert from 'node:assert/strict'
import test from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ExecutionCard } from '../build-tests/components/ExecutionCard.js'

test('ExecutionCard renders waiting status without inline approval buttons', () => {
  const html = renderToStaticMarkup(
    React.createElement(ExecutionCard, {
      data: {
        requestId: 'req-1',
        command: 'ls -la',
        status: 'waiting',
      },
    })
  )

  assert.match(html, /等待审批/)
  assert.doesNotMatch(html, /允许执行/)
  assert.doesNotMatch(html, /拒\s*绝/)
})
