<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { call, downloadFile } from './api.js'
import { api } from '../api.js'
import ReportPreview from './ReportPreview.vue'
import ReportReview from './ReportReview.vue'
import ToastNotice from './ToastNotice.vue'
import TablePagination from './TablePagination.vue'
import TablePageSize from './TablePageSize.vue'
import { usePagination } from './pagination.js'
import TableToolbar from './TableToolbar.vue'
import RefreshButton from './RefreshButton.vue'
const props = defineProps({ taskId: String })
const rows = ref([]), query = ref(''), filter = ref('all'), active = ref(null), busy = ref(false), aiLoading = ref(false), error = ref(''), notice = ref(''), mode = ref('review')
const selectedMajor = ref(''), selectedClass = ref(''), selectedAttempt = ref('')
const returnRow = ref(null), returnReason = ref('')
const refreshing = ref(false), actionsPopover = ref(null), menuRow = ref(null)
let loadGeneration = 0
const comparison = ref(null), otherId = ref(''), originals = ref(false), comparisonOpen = ref(false)
const status = r => !r ? '未提交' : r.returned ? '已退回' : r.finalScore != null ? '已批改' : r.reviewStatus === 'reviewing' ? '批改中' : '待批改'
const stamp = v => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—'
const rowClass = row => String(row.cls || '').trim()
const rowMajor = row => String(row.major || '').trim() || '未填写专业'
const majors = computed(() => [...new Set(rows.value.map(rowMajor))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const classes = computed(() => [...new Set(rows.value.filter(row => !selectedMajor.value || rowMajor(row) === selectedMajor.value).map(rowClass).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN', { numeric: true })))
const attempts = computed(() => [...new Set(rows.value.filter(row => (!selectedMajor.value || rowMajor(row) === selectedMajor.value) && (!selectedClass.value || rowClass(row) === selectedClass.value)).map(row => row.attempt || 1))].sort((a, b) => Number(a) - Number(b)))
watch(selectedMajor, () => { selectedClass.value = ''; selectedAttempt.value = '' })
watch(selectedClass, () => { selectedAttempt.value = '' })
const filtered = computed(() => {
  const search = query.value.trim().toLowerCase()
  return rows.value.filter(row => (!selectedMajor.value || rowMajor(row) === selectedMajor.value)
    && (!selectedClass.value || rowClass(row) === selectedClass.value)
    && (!selectedAttempt.value || String(row.attempt || 1) === String(selectedAttempt.value))
    && (filter.value === 'all' || status(row.latest) === filter.value)
    && (!search || [row.name, row.sid, rowMajor(row), rowClass(row), row.groupName].join(' ').toLowerCase().includes(search)))
})
const { page, pageSize, pagedRows } = usePagination(filtered, [query, filter, selectedMajor, selectedClass, selectedAttempt, () => props.taskId])
const hasFilters = computed(() => !!(query.value.trim() || selectedMajor.value || selectedClass.value || selectedAttempt.value) || filter.value !== 'all')
function resetFilters() { query.value = ''; selectedMajor.value = ''; selectedClass.value = ''; selectedAttempt.value = ''; filter.value = 'all' }
function datePart(value) { const d = new Date(value); return value && Number.isFinite(d.getTime()) ? d.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replaceAll('/', '-') : '—' }
function timePart(value) { const d = new Date(value); return value && Number.isFinite(d.getTime()) ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '' }
function closeActions() { actionsPopover.value?.hidePopover(); menuRow.value = null }
function positionActions(event, row) {
  menuRow.value = row
  const rect = event.currentTarget.getBoundingClientRect()
  const height = 14 + 36 * (1 + (row.latest?.finalScore != null ? 1 : 0) + (row.latest && !row.latest.returned ? 1 : 0))
  Object.assign(actionsPopover.value.style, {
    left: Math.max(12, Math.min(rect.right - 168, window.innerWidth - 180)) + 'px',
    top: Math.max(12, rect.bottom + height + 8 <= window.innerHeight ? rect.bottom + 8 : rect.top - height - 8) + 'px',
  })
}
function secondaryAction(action) {
  const row = menuRow.value
  if (!row) return
  closeActions()
  if (action === 'return') { returnRow.value = row; returnReason.value = ''; return }
  perform(() => action === 'original' ? downloadFile(row.latest.fileId, row.latest.fileName) : gradedFile(row))
}
watch(pagedRows, closeActions)
onMounted(() => { window.addEventListener('resize', closeActions); document.addEventListener('scroll', closeActions, true) })
onUnmounted(() => { window.removeEventListener('resize', closeActions); document.removeEventListener('scroll', closeActions, true) })
const selected = computed(() => active.value?.latest)
const candidates = computed(() => rows.value.filter(r => r.latest && r.sid !== active.value?.sid))
async function perform(fn, success = '') { if (busy.value) return; busy.value = true; error.value = ''; notice.value = ''; try { await fn(); notice.value = success } catch (e) { error.value = e.message } finally { busy.value = false } }
function clearToast() { if (error.value) error.value = ''; else notice.value = '' }
async function load() {
  closeActions()
  const generation = ++loadGeneration
  refreshing.value = true
  try {
    const result = await call(`/report-desk?taskId=${encodeURIComponent(props.taskId)}`)
    if (generation !== loadGeneration) return
    rows.value = result
    if (active.value) active.value = rows.value.find(r => r.runId === active.value.runId && r.sid === active.value.sid) || null
  } finally { if (generation === loadGeneration) refreshing.value = false }
}
watch(() => props.taskId, () => { active.value = null; comparisonOpen.value = false; selectedMajor.value = ''; selectedClass.value = ''; selectedAttempt.value = ''; query.value = ''; filter.value = 'all'; load().catch(e => { error.value = e.message }) }, { immediate: true })
async function open(row, nextMode = 'review') {
  if (!row.latest) return
  active.value = row
  comparisonOpen.value = false
  mode.value = nextMode
  if (nextMode === 'review') await call(`/report-desk/${row.latest.id}/begin`, 'POST')
  await load()
}
async function review(action, values) { const run = await call(`/runs/${active.value.runId}`); await call(`/runs/${run.id}/reports/${selected.value.id}/review`, 'POST', { ...values, action, revision: run.revision }); await load() }
async function ai() { const run = await call(`/runs/${active.value.runId}`); await call(`/runs/${run.id}/ai/${selected.value.id}`, 'POST', { revision: run.revision }); await load() }
async function generateAi() { aiLoading.value = true; try { await ai() } finally { aiLoading.value = false } }
async function compare() { comparison.value = null; if (otherId.value) comparison.value = await call(`/report-desk/${selected.value.id}/compare?otherId=${encodeURIComponent(otherId.value)}`) }
async function check() { await call(`/report-desk/${selected.value.id}/similarity`, 'POST'); await load(); otherId.value = [...(selected.value.similarity?.matches || [])].sort((a,b) => (b.percent || 0) - (a.percent || 0))[0]?.reportId || candidates.value[0]?.latest.id || ''; comparisonOpen.value = true; await compare() }
async function returnFromList() { const row = returnRow.value; const run = await call(`/runs/${row.runId}`); await call(`/runs/${row.runId}/reports/${row.latest.id}/review`, 'POST', { action: 'return', comment: returnReason.value, revision: run.revision }); returnRow.value = null; returnReason.value = ''; await load() }
async function gradedFile(row = active.value) { const report = row === active.value ? selected.value : row.latest; const response = await fetch(`/api/workflow/report-desk/${report.id}/graded-file`, { headers: { Authorization: `Bearer ${api.getToken()}` } }); if (!response.ok) throw new Error('批阅记录下载失败'); const url = URL.createObjectURL(await response.blob()); const a = document.createElement('a'); a.href = url; a.download = `${row.name}-批阅记录.docx`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
function fragments(side) { const text = comparison.value?.[`${side}Text`] || ''; const ranges = (comparison.value?.segments || []).map((s,i) => ({ start: s[`${side}Start`], end: s[`${side}End`], index: i })).sort((a,b) => a.start-b.start); const out = []; let cursor = 0; for (const r of ranges) { if (r.start < cursor) continue; out.push({ text: text.slice(cursor, r.start) }); out.push({ text: text.slice(r.start,r.end), index: r.index }); cursor = r.end } out.push({ text: text.slice(cursor) }); return out }
function focusSegment(i) { originals.value = false; setTimeout(() => ['left','right'].forEach(side => document.getElementById(`match-${side}-${i}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })), 0) }
</script>
<template>
  <section class="report-desk" aria-label="报告批阅与查重">
    <ToastNotice :message="error || notice" :type="error ? 'error' : 'success'" @close="clearToast" />
    <div v-if="returnRow" class="desk-modal" role="dialog" aria-modal="true" aria-label="退回报告"><form @submit.prevent="perform(returnFromList, '报告已退回')"><h3>退回 {{ returnRow.name }} 的报告</h3><label>退回理由<textarea v-model="returnReason" required rows="4" autofocus /></label><div class="wf-actions"><button class="btn btn-primary" :disabled="busy || !returnReason.trim()">确认退回</button><button type="button" class="btn btn-ghost" :disabled="busy" @click="returnRow = null">取消</button></div></form></div><header v-if="active" class="desk-head"><div class="wf-actions"><button type="button" class="btn btn-ghost" @click="comparisonOpen ? comparisonOpen = false : active = null">返回{{ comparisonOpen ? '查重' : '列表' }}</button><RefreshButton label="刷新报告数据" :loading="busy || refreshing" @refresh="perform(load)" /></div></header>
    <template v-if="!active">
      <TableToolbar label="筛选报告记录" refresh-label="刷新报告列表" :loading="busy || refreshing" :filtered="hasFilters" @refresh="perform(load)" @reset="resetFilters">
        <label class="table-toolbar__search">搜索<input v-model="query" type="search" placeholder="姓名或学号" aria-label="搜索学生"></label>
        <label>专业<select v-model="selectedMajor" aria-label="筛选专业"><option value="">全部专业</option><option v-for="name in majors" :key="name">{{ name }}</option></select></label>
        <label>班级<select v-model="selectedClass" aria-label="筛选班级"><option value="">全部班级</option><option v-for="name in classes" :key="name">{{ name }}</option></select></label>
        <label>实验次数<select v-model="selectedAttempt" aria-label="筛选实验次数"><option value="">全部次数</option><option v-for="attempt in attempts" :key="attempt" :value="attempt">第 {{ attempt }} 次</option></select></label>
        <label>报告状态<select v-model="filter" aria-label="报告状态"><option value="all">全部状态</option><option>未提交</option><option>待批改</option><option>批改中</option><option>已批改</option><option>已退回</option></select></label>
      </TableToolbar>
      <div class="desk-table" tabindex="0" aria-label="报告记录表格，可横向滚动" @scroll="closeActions">
        <table aria-label="报告批阅记录">
          <colgroup><col v-for="n in 10" :key="n" style="width: 10%"></colgroup>
          <thead><tr><th scope="col">学生</th><th scope="col">专业</th><th scope="col">班级 / 小组</th><th scope="col">实验次数</th><th scope="col">提交时间</th><th scope="col">批阅时间</th><th scope="col">成绩</th><th scope="col">状态</th><th scope="col">相似度</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedRows" :key="row.runId + row.sid">
              <td><b>{{ row.name }}</b><small>{{ row.sid }}</small></td>
              <td>{{ row.major?.trim() || '—' }}</td>
              <td>{{ row.cls || '—' }}<small>{{ row.groupName }}</small></td>
              <td>第 {{ row.attempt }} 次</td>
              <td class="desk-date">{{ datePart(row.latest?.submittedAt) }}<small>{{ timePart(row.latest?.submittedAt) }}</small></td>
              <td class="desk-date">{{ datePart(row.latest?.gradedAt) }}<small>{{ timePart(row.latest?.gradedAt) }}</small></td>
              <td>{{ row.latest?.finalScore ?? '—' }}</td>
              <td><span class="desk-status" :class="{ done: status(row.latest) === '已批改' }">{{ status(row.latest) }}</span></td>
              <td><button v-if="row.latest?.similarity?.available" type="button" class="wf-text-button" :disabled="busy" @click="perform(async () => { await open(row, 'similarity'); await check() })">{{ row.latest.similarity.maxPercent }}%</button><span v-else>—</span></td>
              <td><div class="desk-row-actions">
                <button type="button" class="desk-action desk-action--primary" :disabled="busy || refreshing || !row.latest" :aria-label="'批阅' + row.name + '的报告'" @click="perform(() => open(row, 'review'))">批阅</button>
                <button type="button" class="desk-action" :disabled="busy || refreshing || !row.latest" :aria-label="'查看' + row.name + '的报告查重'" @click="perform(() => open(row, 'similarity'))">查重</button>
                <button type="button" class="desk-action" :disabled="busy || refreshing || !row.latest" popovertarget="report-row-actions" :aria-label="'查看' + row.name + '的更多操作'" @click="positionActions($event, row)">更多</button>
              </div></td>
            </tr>
            <tr v-if="!filtered.length"><td colspan="10" class="desk-list-empty">{{ refreshing ? '正在加载报告记录…' : rows.length ? '没有符合条件的报告记录' : '暂无报告记录' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div id="report-row-actions" ref="actionsPopover" popover class="desk-row-menu" aria-label="报告更多操作"><template v-if="menuRow"><button type="button" autofocus @click="secondaryAction('original')">原始报告</button><button v-if="menuRow.latest?.finalScore != null" type="button" @click="secondaryAction('graded')">批阅记录</button><button v-if="menuRow.latest && !menuRow.latest.returned" type="button" @click="secondaryAction('return')">退回报告</button></template></div>
      <TablePagination v-model:page="page" class="desk-pagination" :total="filtered.length" :page-size="pageSize" :disabled="busy || refreshing" label="报告批阅与查重分页"><template #page-size><TablePageSize v-model="pageSize" compact label="报告批阅每页条数" :disabled="busy || refreshing" /></template></TablePagination>
    </template>
    <template v-else-if="comparisonOpen"><div class="desk-tools"><b>{{ active.name }} · 实验报告</b><label>对比报告<select v-model="otherId" :disabled="busy" @change="perform(compare)"><option value="">选择报告</option><option v-for="r in candidates" :key="r.latest.id" :value="r.latest.id">{{ r.name }} · {{ r.groupName }} · 第 {{ r.attempt }} 次</option></select></label><button class="btn btn-ghost" @click="originals = !originals">{{ originals ? '查看相似片段' : '查看原始报告' }}</button><b v-if="comparison?.available">相似度 {{ comparison.percent }}%</b></div><p v-if="comparison && !comparison.available">{{ comparison.reason }}</p><p v-if="!otherId">暂无可对比报告</p><template v-if="comparison"><div class="desk-segments"><button v-for="(s,i) in comparison.segments" :key="i" @click="focusSegment(i)"><b>{{ s.length }} 字 · 相同片段</b><span>{{ s.text }}</span></button></div><p v-if="comparison.available && !comparison.segments.length">未检出可定位的相同片段</p><div class="desk-compare"><article v-for="side in ['left','right']" :key="side"><h3>{{ side === 'left' ? '当前报告' : '对比报告' }} · {{ comparison[side].sid }}</h3><button class="wf-text-button" @click="perform(() => downloadFile(comparison[side].fileId, comparison[side].fileName))">下载原始文件</button><ReportPreview v-if="originals" :file="comparison[side]" /><div v-else class="desk-text"><template v-for="(part,i) in fragments(side)" :key="i"><mark v-if="part.index != null" :id="`match-${side}-${part.index}`">{{ part.text }}</mark><span v-else>{{ part.text }}</span></template></div></article></div></template></template>
    <div v-else-if="selected" class="desk-grading"><aside class="desk-students"><input v-model="query" type="search" placeholder="姓名 / 学号" aria-label="搜索报告学生"><select v-model="filter" aria-label="筛选批阅状态"><option value="all">全部状态</option><option>待批改</option><option>批改中</option><option>已批改</option><option>已退回</option></select><div class="desk-student-list"><button v-for="r in filtered" :key="r.runId + r.sid" :class="{active: r.runId === active.runId && r.sid === active.sid}" :disabled="busy || !r.latest" @click="perform(() => open(r))"><b>{{ r.name }}</b><span>{{ status(r.latest) }}</span><small>{{ r.sid }} · {{ r.groupName }}</small><small>{{ stamp(r.latest?.submittedAt) }}</small><small>成绩 {{ r.latest?.finalScore ?? '—' }}</small></button></div></aside><article class="desk-document"><header><h3>{{ active.name }} · 实验报告</h3><div class="wf-actions"><button class="wf-text-button" @click="perform(() => downloadFile(selected.fileId,selected.fileName))">原始报告</button><button v-if="selected.finalScore != null" class="wf-text-button" @click="perform(gradedFile)">下载批阅记录</button><button class="wf-text-button" :disabled="busy" @click="perform(check, '查重完成')">查重对比</button></div></header><ReportPreview :file="selected" /></article><aside class="desk-assessment"><ReportReview :report="selected" :busy="busy" :ai-loading="aiLoading" :mode="mode" @update:mode="mode = $event" @ai="perform(generateAi, 'AI 批阅完成')" @similarity="perform(check, '查重完成')" @grade="v => perform(() => review('grade',v), '最终成绩已保存')" @return="v => perform(() => review('return',v), '报告已退回')" /></aside></div>
  </section>
</template>
<style scoped>
.desk-modal{position:fixed;inset:0;background:#14263066;z-index:1000;display:grid;place-items:center}.desk-modal form{padding:28px;border-radius:8px;background:white;width:min(460px,90vw)}.desk-modal label{display:grid;gap:8px;margin:20px 0}.report-desk{background:#fff;border:1px solid #dfe7eb;border-radius:8px;padding:20px;min-width:0}.desk-head,.desk-tools{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:20px}.desk-head h2{font-size:20px;margin:0}.desk-tools{justify-content:flex-start}.desk-tools>span{margin-left:auto}.desk-table{overflow:auto}.desk-status{white-space:nowrap;background:#fff1df;color:#9b6326;border-radius:4px;padding:5px 8px}.desk-status.done{background:#e4f4ed;color:#247457}.desk-grading{display:grid;grid-template-columns:190px minmax(0,1fr) 320px;gap:16px;height:clamp(540px,calc(100dvh - 330px),820px);min-height:0;align-items:stretch}.desk-students{display:flex;min-height:0;flex-direction:column;overflow:hidden}.desk-students>input,.desk-students>select{flex:0 0 auto;width:100%;margin-bottom:12px}.desk-student-list{min-height:0;flex:1;overflow-y:auto;padding-right:5px;scrollbar-gutter:stable}.desk-student-list::-webkit-scrollbar{width:7px}.desk-student-list::-webkit-scrollbar-thumb{border-radius:8px;background:#bdcbd0}.desk-student-list>button{display:block;width:100%;padding:14px;text-align:left;border:1px solid #dfe7eb;background:#fff;margin-bottom:10px;border-radius:6px;color:#304956;cursor:pointer}.desk-student-list>button.active{background:#edf7f7;border-color:#087c88}.desk-students small{display:block;margin-top:8px}.desk-students span{float:right;font-size:12px}.desk-document{display:flex;min-width:0;min-height:0;flex-direction:column;overflow:hidden;border:1px solid #dfe7eb}.desk-document header{flex:0 0 auto;padding:16px;display:flex;gap:12px;flex-wrap:wrap;align-items:center}.desk-document h3{margin:0;font-size:16px}.desk-document :deep(.report-preview){flex:1;min-height:0;height:100%;overflow:hidden}.desk-document :deep(.report-preview iframe){height:100%;min-height:0}.desk-document :deep(.report-preview__empty){height:100%;min-height:0}.desk-assessment{min-width:0;min-height:0;overflow:hidden}.desk-assessment :deep(.report-review){margin:0}.desk-assessment :deep(.review-block){padding:14px}.desk-assessment :deep(.review-block header){flex-wrap:wrap}.desk-assessment :deep(h3){font-size:16px}.desk-assessment :deep(.btn){font-size:12px}.desk-segments{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin:20px 0}.desk-segments button{display:grid;text-align:left;border:1px solid #dfe7eb;border-left:5px solid #efcf66;border-radius:5px;background:white;padding:12px;gap:8px;cursor:pointer}.desk-segments span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.desk-compare{display:grid;grid-template-columns:1fr 1fr;gap:24px}.desk-compare article{min-width:0}.desk-text{white-space:pre-wrap;line-height:2.1;padding:28px;background:#fff;border:1px solid #dfe7eb;height:65vh;overflow:auto;margin-top:16px;overflow-wrap:anywhere}.desk-text mark{background:#fff0a7;scroll-margin:80px}.desk-error{padding:16px;border:1px solid #d89c94;color:#a53a30;margin-bottom:16px}.desk-error button{float:right}@media(min-width:1500px){.desk-grading{grid-template-columns:210px minmax(0,1fr) 350px}}@media(max-width:1200px){.desk-grading{grid-template-columns:170px minmax(0,1fr);height:auto;min-height:620px}.desk-assessment{grid-column:2;height:620px}.desk-document{height:620px}.desk-students{max-height:620px}}@media(max-width:700px){.desk-grading,.desk-compare{grid-template-columns:1fr;min-height:0}.desk-assessment{grid-column:auto;height:auto;overflow:visible}.desk-document{height:560px}.desk-students{max-height:250px}.report-desk{padding:12px}}
</style>
<style scoped>
.desk-head > .wf-actions { margin-left: auto; }
.desk-pagination { margin-top: 16px; padding-inline: 0; padding-bottom: 0; }
.desk-table { overflow: auto; border: 1px solid #d8e3e9; border-radius: 6px; }
.desk-table table { width: 100%; table-layout: fixed; min-width: 1400px; border-collapse: collapse; font-size: 13px; font-variant-numeric: tabular-nums; }
.desk-table th, .desk-table td { box-sizing: border-box; width: 10%; padding: 10px 8px; border: 1px solid #e0e8ed; text-align: center; vertical-align: middle; line-height: 1.5; overflow-wrap: anywhere; }
.desk-table th { height: 42px; background: #eaf2f8; color: #405966; font-weight: 600; white-space: nowrap; }
.desk-table :is(th, td):nth-child(-n+3) { text-align: left; padding-inline: 12px; }
.desk-table td { background: #fff; }
.desk-table td > b { font-weight: 600; }
.desk-table small { display: block; margin-top: 4px; color: #617581; font-size: 12px; }
.desk-table .desk-date { white-space: nowrap; }
.desk-status { display: inline-block; padding: 3px 6px; font-size: 12px; line-height: 1.5; }
.desk-row-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: center; gap: 4px; margin: 0; }
.desk-row-actions .desk-action { box-sizing: border-box; width: 100%; min-width: 0; height: 30px; min-height: 30px; padding: 0 3px; border: 1px solid #cbdce2; border-radius: 4px; background: #fff; color: #356b78; font: inherit; font-size: 12px; font-weight: 500; line-height: 1; white-space: nowrap; cursor: pointer; box-shadow: none; }
.desk-row-actions .desk-action--primary { background: #2c6977; border-color: #2c6977; color: #fff; }
.desk-row-actions .desk-action:hover:not(:disabled) { border-color: #7ca6b2; background: #eef5f7; }
.desk-row-actions .desk-action--primary:hover:not(:disabled) { background: #245766; border-color: #245766; }
.desk-row-actions .desk-action:focus-visible { outline: 2px solid #2c6977; outline-offset: 2px; }
.desk-row-actions .desk-action:disabled { opacity: .45; cursor: default; }
.desk-row-menu { position: fixed; inset: auto; box-sizing: border-box; width: 168px; margin: 0; padding: 6px; border: 1px solid #d6e2e8; border-radius: 6px; background: #fff; color: #263d49; box-shadow: 0 5px 18px #263d491c; }
.desk-row-menu button { display: block; width: 100%; height: 36px; padding: 7px 10px; border: 0; border-radius: 4px; background: #fff; color: inherit; font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.desk-row-menu button:hover { background: #edf4f6; }
.desk-row-menu button:focus-visible { outline: 2px solid #2c6977; outline-offset: -2px; }
.desk-table .desk-list-empty { height: 90px; text-align: center; color: #617581; }
@media (min-width: 1000px) {
  .desk-table :is(th, td):first-child { position: sticky; left: 0; z-index: 1; }
  .desk-table :is(th, td):last-child:not([colspan]) { position: sticky; right: 0; z-index: 1; }
  .desk-table th:first-child, .desk-table th:last-child { z-index: 2; }
  .desk-table :is(th, td):last-child:not([colspan]) { box-shadow: -1px 0 #d8e3e9; }
}
</style>

<style scoped>
.desk-head { justify-content: flex-end; }
</style>
