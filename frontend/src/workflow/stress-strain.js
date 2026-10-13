export function isTensileTrial(id) {
  return ['TENS', 'STEEL_TENS', 'CAST_TENS'].includes(id)
}
export function isStressStrainTrial(id) {
  return isTensileTrial(id) || ['COMP', 'STEEL_COMP', 'CAST_COMP'].includes(id)
}

// Engineering stress in MPa (N/mm²), engineering strain in percent.
// Instrument displacement is used as an approximation to specimen elongation.
export function stressStrainPoints(points, area, gaugeLength) {
  if (!Number.isFinite(area) || !Number.isFinite(gaugeLength) || area <= 0 || gaugeLength <= 0) return []
  return points.filter(p => Number.isFinite(p.f) && Number.isFinite(p.d))
    .map(p => ({ f: p.f * 1000 / area, d: p.d / gaugeLength * 100 }))
}
