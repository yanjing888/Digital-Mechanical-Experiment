const PL = { left: 52, top: 22, right: 720, bottom: 245, width: 668, height: 223 }

function fmtAxis(v) {
  const a = Math.abs(Number(v))
  if (a >= 100) return Number(v).toFixed(0)
  if (a >= 10) return Number(v).toFixed(1)
  return Number(v).toFixed(2)
}

function mapX(d, minD, maxD) {
  return PL.left + ((d - minD) / (maxD - minD)) * PL.width
}

function mapY(f, minF, maxF) {
  return PL.bottom - ((f - minF) / (maxF - minF)) * PL.height
}

// Bounded rendering keeps large imports responsive and preserves negative instrument readings.
export function curveGeometry(points) {
  if (!points.length) return { line: '', minF: 0, maxF: 1, minD: 0, maxD: 1 }
  let minF = 0, maxF = 0, minD = 0, maxD = 0
  for (const p of points) { minF = Math.min(minF, p.f); maxF = Math.max(maxF, p.f); minD = Math.min(minD, p.d); maxD = Math.max(maxD, p.d) }
  if (maxF === minF) maxF = minF + 1
  if (maxD === minD) maxD = minD + 1
  const stride = Math.max(1, Math.ceil(points.length / 2000))
  const line = points.filter((_, i) => i % stride === 0 || i === points.length - 1).map(p => `${mapX(p.d, minD, maxD)},${mapY(p.f, minF, maxF)}`).join(' ')
  return { line, minF, maxF, minD, maxD }
}

export function curveChartLayout(points) {
  const g = curveGeometry(points)
  if (!points.length) return { ...g, empty: true, xTicks: [], yTicks: [] }
  const midF = (g.minF + g.maxF) / 2
  const midD = (g.minD + g.maxD) / 2
  return {
    ...g,
    empty: false,
    xTicks: [
      { x: mapX(g.minD, g.minD, g.maxD), y: PL.bottom + 16, label: fmtAxis(g.minD) },
      { x: mapX(midD, g.minD, g.maxD), y: PL.bottom + 16, label: fmtAxis(midD) },
      { x: mapX(g.maxD, g.minD, g.maxD), y: PL.bottom + 16, label: fmtAxis(g.maxD) },
    ],
    yTicks: [
      { x: PL.left - 8, y: mapY(g.minF, g.minF, g.maxF), label: fmtAxis(g.minF), anchor: 'end' },
      { x: PL.left - 8, y: mapY(midF, g.minF, g.maxF), label: fmtAxis(midF), anchor: 'end' },
      { x: PL.left - 8, y: mapY(g.maxF, g.minF, g.maxF), label: fmtAxis(g.maxF), anchor: 'end' },
    ],
    axis: { x1: PL.left, y1: PL.bottom, x2: PL.right, y2: PL.bottom, y0: PL.top, y2: PL.bottom, x0: PL.left },
    xLabel: { x: (PL.left + PL.right) / 2, y: 272, text: '位移 (mm)' },
    yLabel: { x: 14, y: (PL.top + PL.bottom) / 2, text: '力 (kN)' },
  }
}
