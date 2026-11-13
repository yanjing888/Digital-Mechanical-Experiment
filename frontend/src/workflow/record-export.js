// Export the visible record, including its current status, charts and photographs.
async function raster(node) {
  let url = node.src, temporary
  if (node.tagName.toLowerCase() === 'svg') {
    const copy = node.cloneNode(true)
    copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
    const originals = node.querySelectorAll('text')
    copy.querySelectorAll('text').forEach((text, i) => {
      const style = getComputedStyle(originals[i])
      text.setAttribute('style', `font-size:${style.fontSize};font-family:Arial,SimSun;fill:${style.fill}`)
    })
    const box = node.viewBox.baseVal
    copy.setAttribute('width', box.width || 750)
    copy.setAttribute('height', box.height || 280)
    url = temporary = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(copy)], { type: 'image/svg+xml' }))
  }
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    const context = canvas.getContext('2d')
    context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    return { data: new Uint8Array(await (await new Promise(resolve => canvas.toBlob(resolve, 'image/png'))).arrayBuffer()), width: canvas.width, height: canvas.height }
  } finally { if (temporary) URL.revokeObjectURL(temporary) }
}

export async function exportRecordWord(element, filename) {
  const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, ImageRun, WidthType, AlignmentType } = await import('docx')
  const text = node => node.textContent.replace(/\s+/g, ' ').trim()
  const paragraph = (value, options = {}) => new Paragraph({ children: [new TextRun({ text: value, font: '宋体', size: 22 })], spacing: { after: 120, line: 320 }, ...options })
  async function blocks(node) {
    const tag = node.tagName?.toLowerCase()
    if (!tag) return []
    if (tag === 'svg' || tag === 'img') {
      const picture = await raster(node)
      const scale = Math.min(600 / picture.width, 360 / picture.height, 1)
      return [new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 160 }, children: [new ImageRun({ type: 'png', data: picture.data, transformation: { width: Math.round(picture.width * scale), height: Math.round(picture.height * scale) } })] })]
    }
    if (tag === 'table') {
      const rows = [...node.querySelectorAll('tr')].map(row => new TableRow({ children: [...row.cells].map(cell => new TableCell({ children: [paragraph(text(cell))] })) }))
      return rows.length ? [new Table({ width: { size: 100, type: WidthType.PERCENTAGE }, rows }), paragraph('')] : []
    }
    if (tag === 'dl') return [...node.children].map(row => paragraph([...row.children].map(text).join('：')))
    if (/^h[1-6]$/.test(tag)) return [new Paragraph({ children: [new TextRun({ text: text(node), bold: true, font: '宋体', size: tag === 'h1' ? 32 : 26 })], spacing: { before: 200, after: 140 }, keepNext: true })]
    if (['p', 'li', 'figcaption'].includes(tag)) return text(node) ? [paragraph((tag === 'li' ? '• ' : '') + text(node))] : []
    const result = []
    for (const child of node.children) result.push(...await blocks(child))
    return result
  }
  const document = new Document({ sections: [{ properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 850, bottom: 850, left: 1000, right: 1000 } } }, children: await blocks(element) }] })
  const blob = await Packer.toBlob(document)
  const url = URL.createObjectURL(blob), link = window.document.createElement('a')
  link.href = url; link.download = filename + '.docx'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}

export async function printRecord(element, filename) {
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;width:0;height:0;border:0;'
  document.body.append(frame)
  try {
    const target = frame.contentDocument
    target.title = filename
    for (const stylesheet of document.querySelectorAll('style,link[rel="stylesheet"]')) target.head.append(stylesheet.cloneNode(true))
    const style = target.createElement('style')
    style.textContent = '@page{size:A4;margin:16mm}html,body{margin:0;background:white}.wf-op-doc{max-width:none!important;min-width:0!important;padding:0!important;border:0!important;box-shadow:none!important;font-size:12pt}.wf-op-doc__section{break-inside:auto}h2,h3{break-after:avoid}figure,table{break-inside:avoid}'
    target.head.append(style)
    const copy = element.cloneNode(true)
    target.body.append(copy)
    // Use self-contained images for both photos and SVG charts in the print document.
    const sources = [...element.querySelectorAll('img,svg')]
    const copies = [...copy.querySelectorAll('img,svg')]
    for (let i = 0; i < sources.length; i++) {
      const picture = await raster(sources[i])
      const img = target.createElement('img')
      img.src = URL.createObjectURL(new Blob([picture.data], { type: 'image/png' }))
      img.style.cssText = 'max-width:100%;height:auto;max-height:80mm;object-fit:contain'
      copies[i].replaceWith(img)
    }
    await Promise.all([...target.images].map(image => image.decode()))
    await target.fonts.ready
    frame.contentWindow.focus()
    // Printing can be asynchronous; retain the frame until the dialog closes.
    frame.contentWindow.addEventListener('afterprint', () => {
      for (const image of target.images) URL.revokeObjectURL(image.src)
      frame.remove()
    }, { once: true })
    frame.contentWindow.print()
  } catch (error) { frame.remove(); throw error }
}
