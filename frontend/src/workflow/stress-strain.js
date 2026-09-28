export function isTensileTrial(id) {
  return ['TENS', 'STEEL_TENS', 'CAST_TENS'].includes(id)
}

// Engineering stress in MPa (N/mm²), engineering strain in percent.
// Instrument displacement is used as an approximation to specimen elongation.
export function stressStrainPoints(points, diameter = 10, gaugeLength = 100) {
  if (!Number.isFinite(diameter) || !Number.isFinite(gaugeLength) || diameter <= 0 || gaugeLength <= 0) return []
  const area = Math.PI * diameter * diameter / 4
  return points.filter(p => Number.isFinite(p.f) && Number.isFinite(p.d))
    .map(p => ({ f: p.f * 1000 / area, d: p.d / gaugeLength * 100 }))
}
