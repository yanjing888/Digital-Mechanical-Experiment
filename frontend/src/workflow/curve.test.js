import test from 'node:test'
import assert from 'node:assert/strict'
import { curveGeometry } from './curve.js'
import { stressStrainPoints } from './stress-strain.js'

test('measured geometry converts kN and mm to MPa and percent', () => {
  assert.deepEqual(stressStrainPoints([{ f: 10, d: 2.5 }], 80, 50), [{ f: 125, d: 5 }])
})
test('missing geometry never produces an assumed stress strain curve', () => {
  const points = [{ f: 10, d: 2.5 }]
  assert.deepEqual(stressStrainPoints(points), [])
  assert.deepEqual(stressStrainPoints(points, 0, 50), [])
})
test('large imports are bounded and retain final point', () => {
  const result = curveGeometry(Array.from({ length: 100000 }, (_, i) => ({ d: i, f: i })))
  assert.ok(result.line.split(' ').length <= 2001)
  assert.ok(result.line.includes(','))
})
test('negative readings remain inside the viewport', () => {
  const result = curveGeometry([{ d: -2, f: -5 }, { d: 3, f: 7 }])
  assert.equal(result.line, '52,245 720,22')
  assert.equal(result.minF, -5)
})
