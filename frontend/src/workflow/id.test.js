import test from 'node:test'
import assert from 'node:assert/strict'
import { newId } from './id.js'

test('creates distinct request IDs when randomUUID is unavailable on HTTP', () => {
  const first = newId(null)
  const second = newId(null)
  assert.ok(first)
  assert.notEqual(first, second)
})
