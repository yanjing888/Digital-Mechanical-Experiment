<script setup>
import { computed, ref, watch } from 'vue'
import ReportPreview from './ReportPreview.vue'

const props = defineProps({ run: Object, draft: Object, reports: Array, locked: Boolean, busy: Boolean, template: Object })
const emit = defineEmits(['upload', 'submit', 'download'])
const selected = ref(null), picker = ref(null)
const current = computed(() => selected.value || props.draft || props.reports?.at(-1))
const disabled = computed(() => props.busy || !props.run.archived || props.locked)
const status = computed(() => props.locked ? '已提交' : props.reports?.at(-1)?.returned ? '待修改' : props.draft ? '待提交' : '待上传')
const stamp = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
watch(() => [props.run.id, props.draft?.fileId], () => { selected.value = null })
function choose(event) {
  const file = event.target.files?.[0]
  if (file) emit('upload', file)
  event.target.value = ''
}
</script>

<template>
  <section class="student-report">
    <div class="report-workspace">
      <aside class="report-sidebar">
        <header class="report-sidebar__head"><h3>个人报告</h3><span class="report-status" :class="{ 'is-submitted': locked }">{{ status }}</span></header>
        <p class="report-instruction">上传本人实验报告，核对原文后完成正式提交。</p>
        <button v-if="template" type="button" class="btn btn-ghost report-template" :disabled="busy" @click="emit('download', { fileId: template.id, fileName: template.name })">下载报告模板</button>
        <div class="report-upload">
          <strong>{{ draft ? '报告附件' : '上传报告文件' }}</strong>
          <p v-if="draft" class="report-filename">{{ draft.fileName }}</p>
          <p v-else>支持 PDF、DOCX、DOC<br>单个文件不超过 20 MB</p>
          <input ref="picker" class="report-file-input" type="file" accept=".doc,.docx,.pdf" :disabled="disabled" aria-label="上传个人实验报告" @change="choose">
          <button type="button" class="btn btn-ghost" :disabled="disabled" @click="picker.click()">{{ busy ? '处理中…' : draft ? '重新上传' : '选择文件' }}</button>
          <small v-if="draft">上传于 {{ stamp(draft.uploadedAt) }}</small>
        </div>
        <p v-if="!run.archived" class="report-alert">请先在「现场实验 · 操作记录」提交实验操作记录。</p>
        <p v-else-if="locked" class="report-instruction">报告已提交并锁定。如需修改，请联系教师退回。</p>
        <p v-else-if="reports?.at(-1)?.returned" class="report-alert">教师已退回：{{ reports.at(-1).returnReason || '请修改报告后重新提交。' }}</p>
        <p v-if="draft?.warning" class="report-alert">{{ draft.warning }}</p>
        <div class="report-submit"><button type="button" class="btn btn-primary" :disabled="disabled || !draft" @click="emit('submit')">{{ locked ? '已正式提交' : '正式提交报告' }}</button><small v-if="!locked">上传文件后，仍需点击此按钮完成提交。</small></div>
      </aside>
      <section class="report-document">
        <header class="report-document__head"><div><h3>报告预览</h3><p>{{ current?.fileName || '尚未上传报告' }}</p></div><div class="report-document__actions"><button v-if="selected && draft && selected.fileId !== draft.fileId" type="button" class="btn btn-ghost" @click="selected = null">当前附件</button><button v-if="current" type="button" class="btn btn-ghost" :disabled="busy" @click="emit('download', current)">下载原文件</button></div></header>
        <ReportPreview :file="current" />
      </section>
    </div>
    <section class="report-history"><header><h3>提交记录</h3><span>共 {{ reports?.length || 0 }} 个版本</span></header><div v-if="!reports?.length" class="report-history__empty">暂无正式提交记录</div><div v-else class="report-history__table"><table><thead><tr><th>版本</th><th>报告文件</th><th>提交时间</th><th>状态</th><th>教师评分 / 评语</th><th>操作</th></tr></thead><tbody><tr v-for="r in [...reports].reverse()" :key="r.id"><td>第 {{ r.version }} 版</td><td class="report-history__name">{{ r.fileName }}</td><td>{{ stamp(r.submittedAt) }}</td><td>{{ r.returned ? '已退回' : r.finalScore != null ? '已评阅' : '待评阅' }}</td><td>{{ r.finalScore != null ? `${r.finalScore} 分` : '—' }}<small v-if="r.finalComment">{{ r.finalComment }}</small></td><td><button type="button" class="wf-text-button" @click="selected = r">查看原文</button></td></tr></tbody></table></div></section>
  </section>
</template>

<style scoped>
.student-report{border:1px solid #e1e9ed;border-radius:0 0 12px 12px;background:#f5f7f9;padding:24px}.report-workspace{display:grid;grid-template-columns:280px minmax(0,1fr);gap:24px;align-items:start}.report-sidebar,.report-document,.report-history{background:#fff;border:1px solid #dfe6eb;border-radius:8px;overflow:hidden;min-width:0}.report-sidebar{padding:22px}.report-sidebar__head,.report-document__head,.report-history>header{display:flex;align-items:center;justify-content:space-between;gap:12px}.student-report h3{font-size:16px;margin:0}.report-status{padding:4px 10px;background:#fff5de;color:#94621b;border-radius:4px;font-size:12px;white-space:nowrap}.report-status.is-submitted{background:#e8f5ef;color:#247457}.report-instruction{color:#677986;font-size:13px;margin:16px 0}.report-template{width:100%;margin-bottom:18px}.report-upload{padding:22px 14px;background:#f8fafb;border:1px dashed #bfcdd5;border-radius:6px;text-align:center}.report-upload strong{font-size:14px}.report-upload p{font-size:13px;color:#71818d;margin:12px 0}.report-upload .report-filename{color:#294551;overflow-wrap:anywhere}.report-upload small{display:block;font-size:11px;margin-top:14px}.report-file-input{display:none}.report-alert{padding:12px;color:#915c23;background:#fff8e9;font-size:13px;border-radius:4px}.report-submit{border-top:1px solid #e8edf0;margin-top:22px;padding-top:22px}.report-submit .btn{width:100%}.report-submit small{display:block;margin-top:10px;font-size:12px}.report-document__head{padding:18px 22px;border-bottom:1px solid #dfe6eb;min-height:42px}.report-document__head>div:first-child{min-width:0}.report-document__head p{margin:5px 0 0;font-size:12px;color:#71818d;overflow-wrap:anywhere}.report-document__actions{display:flex;gap:8px;flex-shrink:0}.report-document__actions .btn{font-size:12px;padding:8px 12px}.report-history{margin-top:24px}.report-history>header{padding:20px 22px;border-bottom:1px solid #e5ebef}.report-history>header span{font-size:12px;color:#71818d}.report-history__empty{text-align:center;padding:32px;color:#8795a0;font-size:13px}.report-history__table{overflow-x:auto}.report-history table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}.report-history th{background:#f8fafb;color:#697d8a;font-weight:500;white-space:nowrap}.report-history td,.report-history th{padding:14px 18px;border-bottom:1px solid #edf1f4}.report-history__name{max-width:260px;overflow-wrap:anywhere}.report-history td small{display:block;max-width:260px;overflow-wrap:anywhere}.report-history tr:last-child td{border-bottom:0}@media(max-width:1100px){.report-workspace{grid-template-columns:240px minmax(0,1fr);gap:16px}.student-report{padding:16px}.report-sidebar{padding:18px}}@media(max-width:800px){.report-workspace{grid-template-columns:1fr}.report-document__head{flex-wrap:wrap}.student-report{padding:12px}.report-history td,.report-history th{padding:12px}}
</style>
