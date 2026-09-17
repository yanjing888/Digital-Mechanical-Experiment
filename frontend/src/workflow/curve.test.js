import test from 'node:test'
import assert from 'node:assert/strict'
import { curveGeometry } from './curve.js'
test('large imports are bounded and retain final point', () => {
  const result = curveGeometry(Array.from({ length: 100000 }, (_, i) => ({ d: i, f: i })))
  assert.ok(result.line.split(' ').length <= 2001)
  assert.ok(result.line.endsWith('715,30'))
})
test('negative readings remain inside the viewport', () => {
  const result = curveGeometry([{ d: -2, f: -5 }, { d: 3, f: 7 }])
  assert.equal(result.line, '45,245 715,30')
  assert.equal(result.minF, -5)
})
