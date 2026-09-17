// Bounded rendering keeps large imports responsive and preserves negative instrument readings.
export function curveGeometry(points) {
  if (!points.length) return { line: '', minF: 0, maxF: 1, minD: 0, maxD: 1 }
  let minF = 0, maxF = 0, minD = 0, maxD = 0
  for (const p of points) { minF = Math.min(minF, p.f); maxF = Math.max(maxF, p.f); minD = Math.min(minD, p.d); maxD = Math.max(maxD, p.d) }
  if (maxF === minF) maxF = minF + 1
  if (maxD === minD) maxD = minD + 1
  const stride = Math.max(1, Math.ceil(points.length / 2000))
  const line = points.filter((_, i) => i % stride === 0 || i === points.length - 1).map(p => `${45 + (p.d - minD) / (maxD - minD) * 670},${245 - (p.f - minF) / (maxF - minF) * 215}`).join(' ')
  return { line, minF, maxF, minD, maxD }
}
