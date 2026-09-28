<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { fileBlob } from './api.js'

const props = defineProps({ file: Object })
const loading = ref(false), error = ref(''), pdfUrl = ref(''), wordHtml = ref(''), kind = ref('')
let generation = 0
function release() {
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfUrl.value = ''; wordHtml.value = ''
}
async function load() {
  const current = ++generation
  release(); error.value = ''; loading.value = false
  const file = props.file
  if (!file?.fileId) return
  kind.value = file.fileName?.split('.').pop().toLowerCase()
  if (!['pdf', 'docx'].includes(kind.value)) return
  loading.value = true
  try {
    const blob = await fileBlob(file.fileId)
    if (current !== generation) return
    if (kind.value === 'pdf') pdfUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
    else {
      const { renderAsync } = await import('docx-preview')
      const container = document.createElement('div')
      await renderAsync(await blob.arrayBuffer(), container, null, { useBase64URL: true, renderAltChunks: false, ignoreLastRenderedPageBreak: false })
      if (current !== generation) return
      // Keep document styles and links isolated from the application. No external resources or scripts.
      wordHtml.value = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'"><style>body{margin:0;background:#edf1f4}.docx-wrapper{padding:24px!important;background:#edf1f4!important}.docx-wrapper>section.docx{margin:0 auto 24px!important;box-shadow:0 2px 8px #162e4214!important}</style></head><body>${container.innerHTML}</body></html>`
    }
  } catch (e) {
    if (current === generation) error.value = '报告预览加载失败，请重试或下载原文件。'
  } finally { if (current === generation) loading.value = false }
}
watch(() => props.file?.fileId, load, { immediate: true })
onBeforeUnmount(() => { generation++; release() })
</script>

<template>
  <div class="report-preview" :aria-busy="loading">
    <div v-if="!file" class="report-preview__empty"><strong>报告预览</strong><p>上传报告后可预览文档内容。</p></div>
    <div v-else-if="loading" class="report-preview__empty" role="status"><strong>正在加载报告…</strong><p>正在准备文档预览，请稍候。</p></div>
    <div v-else-if="error" class="report-preview__empty" role="alert"><p>{{ error }}</p><button type="button" class="btn btn-ghost" @click="load">重新加载</button></div>
    <iframe v-else-if="pdfUrl" :src="pdfUrl" title="报告 PDF 原文预览" />
    <template v-else-if="wordHtml"><p class="report-preview__note">预览效果仅供参考，文档格式以原文件为准。</p><iframe :srcdoc="wordHtml" sandbox="" title="报告 Word 原文预览" /></template>
    <div v-else class="report-preview__empty"><strong>此格式暂不支持在线预览</strong><p>可下载原文件查看，或将报告另存为 DOCX / PDF 后重新上传。</p></div>
  </div>
</template>

<style scoped>
.report-preview{background:#edf1f4;min-height:620px}.report-preview iframe{display:block;width:100%;height:720px;border:0;background:#edf1f4}.report-preview__empty{min-height:620px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:32px;box-sizing:border-box;color:#6c7b87}.report-preview__empty strong{font-size:18px;color:#405766}.report-preview__empty p{font-size:14px;max-width:360px}.report-preview__note{margin:0;padding:10px 20px;font-size:12px;color:#61717d;background:#f7f9fb;border-bottom:1px solid #dfe6eb}@media(max-width:700px){.report-preview,.report-preview__empty{min-height:420px}.report-preview iframe{height:560px}}
</style>
