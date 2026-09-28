<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { call as workflowCall, form, fileBlob, downloadFile } from './api.js'
import AdvancedPanel from './AdvancedPanel.vue'
import CurveChart from './CurveChart.vue'
import StressStrainChart from './StressStrainChart.vue'
import { isTensileTrial } from './stress-strain.js'
import OperationRecordDoc from './OperationRecordDoc.vue'
import StudentReport from './StudentReport.vue'
import ReportPreview from './ReportPreview.vue'
import ReportReview from './ReportReview.vue'
const props = defineProps({ page: String, teacher: Boolean, sid: String, taskId: String })
const activeTrial = ref('STEEL_TENS')
function call(path, method = 'GET', body) {
  const scoped = /^\/runs\/[^/?]+(?:\/|$)/.test(path)
  return workflowCall(scoped ? `${path}${path.includes('?') ? '&' : '?'}trial=${encodeURIComponent(activeTrial.value)}` : path, method, body)
}
async function pickTrial(id) {
  activeTrial.value = id
  if (isLabPage.value) tab.value = 'data'
  await select(run.value.id)
}
const runs = ref([]), run = ref(null), busy = ref(false), error = ref(''), notice = ref(''), labStatus = ref(null)
const labEmptyMessage = computed(() => {
  if (props.teacher || runs.value.length) return ''
  const r = labStatus.value?.reason
  if (r === 'no_group') return '您的账号尚未分配实验小组，请联系教师将您加入小组并重新下发任务。'
  if (r === 'no_task') return '教师尚未下发实验任务，或您所在小组未包含在该次下发中。请先在「任务中心」查看，或联系教师重新下发。'
  if (r === 'no_run') return '任务已关联，但本组实验记录未生成。请刷新；若仍不行，请确认您在该次下发的小组内，或请教师重新下发一次任务。'
  if (r === 'no_student') return '未找到您的学籍信息，请确认学号登录是否正确。'
  if (r === 'api_error') return `无法加载实验记录：${labStatus.value?.detail || '请退出后重新登录'}`
  if (labStatus.value?.hasRun) return '实验记录已就绪，正在重新加载…'
  return '暂无实验任务，请先在「任务中心」查看或联系教师。'
})
const tab = ref(props.teacher ? 'deduct' : props.page === 's-report' ? 'report' : 'data')
const operationConfirmed = ref(false)
const teacherSteps = [
  { id: 'deduct', title: '现场扣分' },
  { id: 'record', title: '实验记录' },
  { id: 'report', title: '报告批阅与查重' },
  { id: 'retake', title: '重做安排' },
]
const selectedReportId = ref(null)
const reportWorkspaceOpen = ref(false), reportMode = ref('review'), reportQuery = ref(''), reportFilter = ref('all')
const reportStatus = student => !student.latest ? '未提交' : student.latest.returned ? '已退回' : student.latest.finalScore != null ? '已批改' : '待批改'
const filteredReportStudents = computed(() => groupReportStudents.value.filter(student => {
  const query = reportQuery.value.trim().toLowerCase()
  return `${student.name} ${student.sid}`.toLowerCase().includes(query) && (reportFilter.value === 'all' || reportStatus(student) === reportFilter.value)
}))
function enterReport(student, mode = 'review') {
  if (!student.latest || busy.value) return
  selectedReportId.value = student.latest.id
  reportMode.value = mode
  reportWorkspaceOpen.value = true
}
watch(() => run.value?.id, () => { reportWorkspaceOpen.value = false; reportQuery.value = ''; reportFilter.value = 'all' })

const selectedReport = computed(() => (run.value?.reports || []).find(r => r.id === selectedReportId.value))
const groupReportStudents = computed(() => {
  const members = new Map((run.value?.members || []).map(m => [m.sid, m]))
  for (const report of run.value?.reports || []) if (!members.has(report.sid)) members.set(report.sid, { sid: report.sid, name: report.sid })
  return [...members.values()].map(member => {
    const reports = (run.value?.reports || []).filter(r => r.sid === member.sid).sort((a, b) => b.version - a.version)
    return { ...member, reports, latest: reports[0] }
  })
})
const submittedStudents = computed(() => groupReportStudents.value.filter(s => s.latest).length)
const selectedStudent = computed(() => groupReportStudents.value.find(s => s.sid === selectedReport.value?.sid))
const meta = reactive({ specimenId: '', deviceId: '', experimentAt: '', note: '' })
let metadataBaseline = {}
const selectionKey = `lab-run-selection-${props.teacher ? 'teacher' : `${props.sid}-${props.taskId}`}`
const preview = ref(null), dataFile = ref(null), mapping = reactive({ start: 2, force: 1, displacement: 0, unit: 'kN' })
const angle = ref('正面'), images = reactive({}), video = ref(null), camera = ref(false)
let stream, timer, disposed = false
const newId = () => crypto.randomUUID()
const settings = ref({ catalog: [], requiredSections: [] }), showSettings = ref(false)
const deduction = reactive({ items: [], reason: '' }), grade = reactive({ score: 100, comment: '', reason: '' })
const controlReason = ref('')
const reportGrades = reactive({}), reportPreview = ref(''), requestId = ref(crypto.randomUUID())
const myDraft = computed(() => run.value?.drafts?.[props.sid])
const myReports = computed(() => run.value?.reports || [])
const workflowTabs = computed(() => [['capture', '断口采集'], ['data', '实验数据'], ['operation', '操作记录'], ['report', '个人报告']])
const WANCE_DEFAULT_MDB = '2024-04-25-09-24-49.mdb'
const labSteps = [{ id: 'data', title: '数据采集' }, { id: 'capture', title: '试件与断口' }, { id: 'operation', title: '操作记录' }]
const isLabPage = computed(() => !props.teacher && props.page === 's-lab')
const isReportPage = computed(() => !props.teacher && props.page === 's-report')
const studentPageTitle = computed(() => {
  if (props.teacher) return '课堂记录与评阅'
  if (isReportPage.value) return '实验报告'
  if (isLabPage.value) return '现场实验'
  return '实验采集'
})
function labStepDone(id) {
  const r = run.value
  if (!r) return false
  if (id === 'capture') return !!r.photos.length
  if (id === 'data') return !!r.data
  if (id === 'operation') return !!r.archived
  return false
}
function suggestLabStep() {
  for (const s of labSteps) if (!labStepDone(s.id)) return s.id
  return 'operation'
}
function goLabStep(id) {
  if (!props.teacher && isLabPage.value) {
    const idx = labSteps.findIndex(s => s.id === id)
    if (idx > 0 && !labStepDone(labSteps[idx - 1].id)) return
  }
  stopCamera(); tab.value = id
}
const labStepIndex = computed(() => labSteps.findIndex(s => s.id === tab.value))
const nextLabStepTitle = computed(() => labSteps[labStepIndex.value + 1]?.title || '')
async function nextLabStep() {
  if (labStepIndex.value >= 0 && labStepIndex.value < labSteps.length - 1) {
    if (tab.value === 'capture' && labStepDone('capture') && labStepDone('data') && !run.value?.fractureSummary) await runFractureAnalysis()
    tab.value = labSteps[labStepIndex.value + 1].id
  }
}
function prevLabStep() { if (labStepIndex.value > 0) tab.value = labSteps[labStepIndex.value - 1].id }
async function runFractureAnalysis() {
  if (!run.value?.photos?.length) return
  run.value = await call(`/runs/${run.value.id}/analyze`, 'POST')
}
function onPhotoPick(event) {
  const input = event.target
  const file = input.files?.[0]
  action(async () => {
    if (!file) throw new Error('请选择 JPEG 或 PNG 图片')
    await uploadPhoto(file)
    if (input) input.value = ''
    const analyzeError = run.value?.analyzeError
    if (analyzeError) { delete run.value.analyzeError; throw new Error(analyzeError) }
    if (!run.value?.fractureSummary) throw new Error('断口分析未生成，请确认断口服务已启动（8090）')
  }, '已替换断口照片并完成分析')
}
function goStudentNav(page) { document.querySelector(`.nav-item[data-p="${page}"]`)?.click() }
const latestOwn = computed(() => myReports.value.filter(r => r.sid === props.sid).at(-1))
const reportLocked = computed(() => !!latestOwn.value && !latestOwn.value.returned)
const teacherRunGroups = computed(() => {
  const groups = new Map()
  for (const item of runs.value) {
    const name = item.expName || '未命名实验项目'
    if (!groups.has(name)) groups.set(name, [])
    groups.get(name).push(item)
  }
  return [...groups].map(([name, items]) => ({ name, items }))
})
async function loadSettings() { settings.value = await call('/settings') }
async function saveSettings() { settings.value = await call('/settings', 'PUT', { revision: settings.value.revision, catalog: settings.value.catalog, catalogConfirmed: settings.value.catalogConfirmed }) }
async function control(actionName) {
  const defaults = { close: '教师结束课堂记录', open: '教师重新开放课堂', return: '教师退回操作记录', clearGrade: '按扣分明细恢复计分' }
  let reason = (controlReason.value || grade.reason || '').trim()
  if (!reason && defaults[actionName]) reason = defaults[actionName]
  run.value = await call(`/runs/${run.value.id}/control`, 'POST', { action: actionName, revision: run.value.revision, reason, score: grade.score, comment: grade.comment })
  await listRuns()
}
async function openReport(report) {
  if (reportPreview.value) URL.revokeObjectURL(reportPreview.value)
  const blob = await fileBlob(report.fileId)
  if (blob.type === 'application/pdf') reportPreview.value = URL.createObjectURL(blob)
  else await downloadFile(report.fileId, report.fileName)
}
async function uploadReport(file) {
  if (!file) return
  run.value = await call(`/runs/${run.value.id}/report-file`, 'POST', form(file)); requestId.value = crypto.randomUUID()
}
async function review(report, actionName, values) {
  const form = values || reportGrades[report.id] || {}
  run.value = await call(`/runs/${run.value.id}/reports/${report.id}/review`, 'POST', { ...form, action: actionName, revision: run.value.revision })
}
async function analyzeReport(path) {
  run.value = await call(`/runs/${run.value.id}/${path}`, 'POST', { revision: run.value.revision })
}
function onPickRun(event) {
  const id = event.target.value
  if (id && id !== run.value?.id) action(() => select(id))
}
const stamp = v => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—'
const points = computed(() => run.value?.data?.points || [])
const activeDeductions = computed(() => (run.value?.deductions || []).filter(d => !d.voided))
async function action(fn, message = '') {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await fn(); if (!disposed) notice.value = message } catch (e) { if (!disposed) error.value = e.message } finally { busy.value = false }
}
async function bootWorkflow() {
  busy.value = true
  error.value = ''
  try {
    await loadSettings()
    await listRuns()
    if (runs.value.length) {
      const id = runs.value.find(r => r.id === sessionStorage.getItem(selectionKey))?.id || runs.value[0].id
      await select(id)
    }
  } catch (e) {
    if (!disposed) error.value = e.message || '加载失败'
  } finally {
    if (!disposed) busy.value = false
  }
}
async function refreshLab() {
  busy.value = true
  error.value = ''
  try {
    await listRuns()
    if (runs.value.length) await select(runs.value.find(r => r.id === sessionStorage.getItem(selectionKey))?.id || runs.value[0].id)
  } catch (e) {
    if (!disposed) error.value = e.message || '加载失败'
  } finally {
    if (!disposed) busy.value = false
  }
}
async function listRuns() {
  error.value = ''
  const currentTaskRuns = items => props.teacher ? items : items.filter(item => item.taskId === props.taskId)
  try { runs.value = currentTaskRuns(await call('/runs')) } catch (e) {
    runs.value = []
    error.value = e.message || '加载实验记录失败'
  }
  if (!props.teacher && !runs.value.length) {
    try {
      labStatus.value = await call('/lab-status')
      if (labStatus.value?.hasRun) {
        try {
          runs.value = currentTaskRuns(await call('/runs'))
          if (runs.value.length) error.value = ''
        } catch (e) { error.value = e.message || error.value }
      }
    } catch (e) {
      labStatus.value = { reason: 'api_error', detail: e.message || '未登录或登录已过期' }
    }
  }
}
watch(run, value => {
  if (!value) return
  const item = runs.value.find(r => r.id === value.id)
  if (item) Object.assign(item, { status: value.status, photoCount: value.photos.length, dataReady: !!value.data, operationScore: value.operationScore, classOpen: value.classOpen, archived: value.archived })
})
async function photos() {
  const allPhotos = run.value?.trialItems ? run.value.trialItems.flatMap(t => t.photos || []) : run.value?.photos || []
  for (const p of allPhotos) if (!images[p.id]) {
    const blob = await fileBlob(p.id)
    if (!disposed) images[p.id] = URL.createObjectURL(blob)
  }
}
function setMeta() {
  const r = run.value
  metadataBaseline = Object.fromEntries(['specimenId', 'deviceId', 'experimentAt', 'note'].map(k => [k, r[k] || '']))
  Object.assign(meta, { specimenId: r.specimenId || '', deviceId: r.deviceId || '', note: r.note || '', experimentAt: r.experimentAt ? new Date(new Date(r.experimentAt).getTime() - new Date(r.experimentAt).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : '' })
}
async function select(id) {
  stopCamera(); run.value = await call(`/runs/${id}`); setMeta(); preview.value = null; dataFile.value = null
  sessionStorage.setItem(selectionKey, id)
  Object.assign(grade, { score: run.value.operationScore, comment: run.value.finalComment || '', reason: '' })
  deduction.items = []; deduction.reason = ''; controlReason.value = ''
  requestId.value = crypto.randomUUID()
  if (reportPreview.value) URL.revokeObjectURL(reportPreview.value); reportPreview.value = ''
  for (const report of run.value.reports) reportGrades[report.id] = { score: report.finalScore ?? 100, comment: report.finalComment || '' }
  const reports = run.value.reports || []
  if (!selectedReportId.value || !reports.some(r => r.id === selectedReportId.value)) selectedReportId.value = groupReportStudents.value.find(s => s.latest)?.latest.id ?? null
  await photos()
}
async function saveMeta() {
  run.value = await call(`/runs/${run.value.id}`, 'PATCH', { ...meta, experimentAt: meta.experimentAt ? new Date(meta.experimentAt).toISOString() : '', revision: run.value.revision, expectedMetadata: metadataBaseline })
  setMeta()
}
async function uploadPhoto(file) {
  const prevIds = new Set((run.value?.photos || []).map(p => p.id))
  run.value = await call(`/runs/${run.value.id}/photos`, 'POST', form(file, { angle: angle.value }))
  for (const id of prevIds) {
    if (!(run.value.photos || []).some(p => p.id === id)) {
      if (images[id]) { URL.revokeObjectURL(images[id]); delete images[id] }
    }
  }
  await photos()
}
async function startCamera() {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持拍摄，请上传照片；远程摄像头访问需使用HTTPS')
  stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1920 }, height: { ideal: 1080 }, facingMode: { ideal: 'environment' } }, audio: false })
  camera.value = true
  await new Promise(resolve => setTimeout(resolve, 0))
  if (disposed) { stopCamera(); return }
  video.value.srcObject = stream; await video.value.play()
}
function stopCamera() { stream?.getTracks().forEach(t => t.stop()); stream = null; camera.value = false }
async function snap() {
  const canvas = document.createElement('canvas'); canvas.width = video.value.videoWidth; canvas.height = video.value.videoHeight
  if (!canvas.width) throw new Error('摄像头尚未就绪')
  canvas.getContext('2d').drawImage(video.value, 0, 0)
  const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.93))
  if (!blob) throw new Error('拍摄失败，请重试')
  await uploadPhoto(new File([blob], `断口-${Date.now()}.jpg`, { type: 'image/jpeg' }))
  if (!run.value?.fractureSummary) throw new Error('断口分析未生成，请确认断口服务已启动（8090）')
  stopCamera()
}
async function chooseData(file) { if (!file) return; dataFile.value = file; preview.value = await call('/preview', 'POST', form(file)) }
async function syncWanceData() {
  run.value = await call(`/runs/${run.value.id}/data/wance`, 'POST', run.value?.trialItems ? {} : { fileName: WANCE_DEFAULT_MDB })
  setMeta()
}
watch(() => props.teacher, teacher => {
  if (!teacher) return
  if (tab.value === 'opgrade' || !teacherSteps.some(s => s.id === tab.value)) tab.value = 'deduct'
}, { immediate: true })
watch(() => props.page, page => {
  if (props.teacher) return
  if (page === 's-report') tab.value = 'report'
  else if (page === 's-lab') tab.value = suggestLabStep()
}, { immediate: true })
watch(() => run.value?.id, () => { operationConfirmed.value = false })
watch(() => run.value?.archived, archived => {
  if (archived && isLabPage.value) tab.value = 'operation'
})
watch(tab, async value => {
  if (!run.value) return
  if (value === 'operation' || value === 'record') await photos()
  if (props.teacher && (value === 'record' || value === 'deduct')) {
    try {
      const fresh = await call(`/runs/${run.value.id}/classroom`)
      for (const key of ['deductions', 'operationScore', 'calculatedScore', 'classOpen', 'finalScore', 'finalComment', 'archived', 'archivedAt']) {
        if (fresh[key] !== undefined) run.value[key] = fresh[key]
      }
    } catch { /* ignore */ }
  }
  if (props.teacher || !isLabPage.value) return
  if (value !== 'operation') return
  if (labStepDone('data') && labStepDone('capture') && !run.value.fractureSummary && !run.value.archived) await action(runFractureAnalysis)
})
async function submitOperationRecord() {
  run.value = await call(`/runs/${run.value.id}/archive`, 'POST', { revision: run.value.revision })
  await listRuns()
}
onMounted(() => {
  bootWorkflow()
  timer = setInterval(async () => {
    if (busy.value || !run.value || props.teacher) return
    const id = run.value.id
    try {
      const fresh = await call(`/runs/${id}/classroom`)
      if (disposed || busy.value || run.value?.id !== id) return
      // Only refresh classroom facts; do not overwrite in-progress student input or its revision.
      for (const key of ['deductions', 'operationScore', 'calculatedScore', 'classOpen', 'finalScore', 'finalComment']) run.value[key] = fresh[key]
    } catch { /* Background refresh never disrupts in-progress input. */ }
  }, 10000)
})
onUnmounted(() => { disposed = true; clearInterval(timer); stopCamera(); Object.values(images).forEach(URL.revokeObjectURL); if (reportPreview.value) URL.revokeObjectURL(reportPreview.value) })
</script>

<template>
  <section class="workflow" :class="{ 'workflow--student': !teacher, 'workflow--teacher': teacher }">
    <header v-if="!teacher" class="workflow-heading workflow-heading--student"><h1>{{ studentPageTitle }}</h1></header>
    <div v-else class="wf-teacher-toolbar"><button type="button" class="btn btn-ghost wf-config-trigger" :class="{ 'is-attention': !settings.catalogConfirmed }" @click="showSettings = true"><span class="wf-config-trigger__icon" aria-hidden="true">⚙</span><span>扣分项配置</span><small>{{ settings.catalogConfirmed ? `${settings.catalog.length} 项已启用` : '待确认' }}</small></button></div>
    <p v-if="error" role="alert" class="wf-message error">{{ error }}</p><p v-if="notice" role="status" class="wf-message success">{{ notice }}</p>
    <div v-if="teacher && showSettings" class="wf-config-mask" @click.self="showSettings = false">
      <aside class="wf-config-drawer" role="dialog" aria-modal="true" aria-labelledby="deduction-config-title">
        <header class="wf-config-drawer__head"><div><p class="eyebrow">课堂操作记录</p><h2 id="deduction-config-title">扣分项配置</h2><p>设置现场记录时可选的扣分项与对应分值。</p></div><button type="button" class="btn btn-ghost wf-config-close" aria-label="关闭扣分项配置" @click="showSettings = false">×</button></header>
        <fieldset :disabled="busy"><div class="wf-deduction-config"><div class="wf-deduction-config__labels"><span>扣分项</span><span>扣分值</span><span>操作</span></div><div v-for="(item,i) in settings.catalog" :key="item.id" class="wf-deduction-config__row"><input v-model="item.label" :aria-label="`第 ${i + 1} 个扣分项名称`" placeholder="例如：实验运行时尝试打开保护罩"><label><input v-model.number="item.points" :aria-label="`第 ${i + 1} 个扣分项分值`" type="number" min="0" max="100"><span>分</span></label><button type="button" class="btn btn-ghost" :aria-label="`移除${item.label || '该扣分项'}`" @click="settings.catalog.splice(i,1)">移除</button></div></div><button type="button" class="btn btn-ghost" @click="settings.catalog.push({ id: newId(), label: '', points: 0 })">+ 增加扣分项</button><label class="wf-config-confirm"><input v-model="settings.catalogConfirmed" type="checkbox"> 我已确认扣分项目和分值，可用于课堂记录</label><div class="wf-config-actions"><button class="btn btn-primary" @click="action(saveSettings, '扣分项已保存')">保存扣分项</button><button type="button" class="btn btn-ghost" @click="showSettings = false">取消</button></div></fieldset>
      </aside>
    </div>
    <div class="workflow-layout workflow-layout--solo" :class="{ 'workflow-layout--teacher': teacher }">
      <div v-if="!teacher && !runs.length" class="wf-empty wf-empty--page">
        <p>{{ labEmptyMessage }}</p>
        <p v-if="labStatus?.sid || sid" class="wf-hint">当前登录学号：{{ labStatus?.sid || sid }}</p>
        <button type="button" class="btn btn-primary" :disabled="busy" @click="refreshLab">重新加载</button>
      </div>
      <p v-if="teacher && !runs.length" class="wf-empty wf-empty--page">暂无实验记录</p>
      <main v-if="run" class="wf-main" :class="{ 'wf-main--teacher': teacher, 'wf-main--student': !teacher }">
        <header v-if="teacher" class="wf-teacher-bar">
          <div class="wf-teacher-bar__main">
            <p class="eyebrow">课堂记录与评阅</p>
            <h2>{{ run.expName }} · {{ run.groupName }} · 第 {{ run.attempt }} 次</h2>
            <p class="wf-teacher-members">{{ run.members.map(m => m.name).join('、') }}</p>
            <div class="wf-teacher-tags">
              <span class="tag" :class="{ ok: run.classOpen }">{{ run.classOpen ? '课堂进行中' : '课堂已结束' }}</span>
              <span class="tag tag-accent">操作分 {{ run.operationScore ?? run.calculatedScore }} / 100</span>
              <span class="tag" :class="{ ok: run.archived }">{{ run.archived ? '操作记录已提交' : '操作记录未提交' }}</span>
            </div>
          </div>
          <label v-if="runs.length > 1" class="wf-run-picker wf-teacher-bar__pick"><span>选择实验小组（{{ runs.length }} 条记录）</span><select class="wf-run-select" :value="run.id" :disabled="busy" aria-label="选择实验小组" @change="onPickRun"><optgroup v-for="group in teacherRunGroups" :key="group.name" :label="group.name"><option v-for="item in group.items" :key="item.id" :value="item.id">{{ item.groupName }} · 第 {{ item.attempt }} 次</option></optgroup></select></label>
          <nav class="wf-teacher-tabs" aria-label="评阅功能">
            <button v-for="s in teacherSteps" :key="s.id" type="button" class="wf-teacher-tab" :class="{ active: tab === s.id }" @click="tab = s.id">{{ s.title }}</button>
          </nav>
        </header>
        <div v-else class="wf-student-head">
          <div class="wf-student-head-main">
            <select v-if="runs.length > 1" class="wf-run-select" :value="run.id" :disabled="busy" aria-label="选择实验记录" @change="onPickRun"><option v-for="r in runs" :key="r.id" :value="r.id">{{ r.expName }} · {{ r.groupName }} · 第{{ r.attempt }}次</option></select>
            <h2 v-else>{{ run.expName }} · {{ run.groupName }}</h2>
            <span class="wf-teacher-meta">第 {{ run.attempt }} 次 · {{ run.members.map(m => m.name).join('、') }}</span>
          </div>
        </div>
        <nav v-if="run.trialItems && isLabPage" class="wf-trial-nav" aria-label="试验子项">
          <button v-for="item in run.trialItems" :key="item.id" type="button" :class="{ active: run.trialId === item.id }" :disabled="busy" @click="action(() => pickTrial(item.id))"><strong>{{ item.name }}</strong><small>{{ item.dataReady && item.photoReady ? '采集完成' : item.dataReady || item.photoReady ? '采集中' : '待采集' }}</small></button>
        </nav>
        <nav v-if="isLabPage" class="wf-stepper" aria-label="现场实验步骤">
          <button v-for="(s, i) in labSteps" :key="s.id" type="button" class="wf-step" :class="{ active: tab === s.id, done: labStepDone(s.id), locked: i > 0 && !labStepDone(labSteps[i - 1].id) && tab !== s.id }" @click="goLabStep(s.id)"><span class="wf-step-num">{{ i + 1 }}</span><span class="wf-step-title">{{ s.title }}</span></button>
        </nav>
        <section v-if="isLabPage && tab === 'data'" class="wf-panel wf-panel--student">
          <div class="wf-group-reports-head"><h3>{{ run.trialName ? `${run.trialName} · 数据采集` : '数据采集' }}</h3><div class="wf-actions"><span v-if="run.data" class="tag">已采集</span><button v-if="!run.archived" type="button" class="btn btn-primary btn-sm" :disabled="busy" @click="action(syncWanceData)">{{ busy ? '正在采集…' : run.data ? '重新采集' : '开始采集' }}</button></div></div>

          <p v-if="busy && !run.data" class="wf-empty wf-empty--inline">正在读取试验数据…</p>
          <div v-else-if="run.data" class="wf-data wf-data--linked">
            <div class="wf-metrics wf-metrics--compact"><div><small>最大力</small><strong>{{ run.data.maxF.toFixed(3) }} kN</strong></div><div><small>最大位移</small><strong>{{ run.data.maxD.toFixed(3) }} mm</strong></div><div><small>数据点</small><strong>{{ run.data.pointCount }}</strong></div></div>
            <CurveChart :points="points" />
            <StressStrainChart v-if="isTensileTrial(run.trialId || run.expId)" :points="points" />
          </div>
          <p v-else class="wf-empty wf-empty--inline">{{ run.archived ? '暂无试验数据' : '点击“开始采集”读取当前试验数据并生成曲线。' }}</p>
          <div v-if="isLabPage" class="wf-step-nav"><button type="button" class="btn btn-primary" :disabled="busy || !labStepDone('data')" @click="nextLabStep">下一步：{{ nextLabStepTitle }}</button></div>
        </section>
        <section v-if="isLabPage && tab === 'capture'" class="wf-panel wf-panel--student">
          <h3>{{ run.trialName ? `${run.trialName} · 试件照片` : '断口照片' }}</h3>
          <p v-if="run.trialItems" class="wf-hint">拉伸记录断口形貌；压缩记录试件变形或破坏形貌。</p>
          <fieldset v-if="run.trialItems" :disabled="busy || run.archived"><div class="wf-form"><label>试件编号<input v-model="meta.specimenId"></label><label>设备编号<input v-model="meta.deviceId"></label><label>试验时间<input v-model="meta.experimentAt" type="datetime-local"></label></div><button type="button" class="btn btn-ghost" @click="action(saveMeta, '试件信息已保存')">保存本项试件信息</button></fieldset>
          <p class="wf-hint">每次上传会<strong>替换</strong>当前断口照片并重新分析，不会叠加多张。</p>
          <fieldset :disabled="busy || run.archived"><div class="wf-actions"><label>角度 <select v-model="angle"><option>正面</option><option>侧面</option><option>斜面</option><option>补充</option></select></label><button class="btn btn-primary" @click="action(startCamera)">打开摄像头</button><label class="btn btn-ghost">上传照片<input type="file" accept="image/jpeg,image/png" @change="onPhotoPick"></label></div></fieldset>
          <div v-if="camera" class="camera"><video ref="video" autoplay playsinline muted></video><div class="wf-actions"><button class="btn btn-primary" :disabled="busy" @click="action(snap, '照片已保存')">拍摄并保存</button><button class="btn btn-ghost" @click="stopCamera">关闭摄像头</button></div></div>
          <div class="photo-grid"><figure v-for="p in run.photos" :key="p.id"><img :src="images[p.id]" :alt="`${p.angle}断口照片`"><figcaption>{{ p.angle }} · {{ p.width }} × {{ p.height }}<small>{{ p.warning }}</small></figcaption><div class="wf-actions"><button @click="action(() => downloadFile(p.id, p.name))">下载原图</button><button v-if="!run.archived" :disabled="busy" @click="action(async () => { run = await call(`/runs/${run.id}/photos/${p.id}`, 'DELETE') }, '已移除，原文件仍保留')">不采用此图</button></div></figure></div>          <p v-if="!run.photos.length" class="wf-empty wf-empty--inline">请至少保存一张断口照片</p>
          <p v-if="busy && run.photos.length && !run.fractureSummary" class="wf-empty wf-empty--inline">正在分析断口形貌…</p>
          <div v-if="run.fractureSummary" class="wf-fracture-summary">
            <h3>断口分析结论</h3>
            <p class="wf-fracture-summary__verdict">{{ run.fractureSummary.label }}</p>
            <p v-if="run.fractureSummary.studentHint" class="wf-hint">{{ run.fractureSummary.studentHint }}</p>
            <ul v-if="run.fractureSummary.evidence?.length" class="wf-fracture-evidence"><li v-for="(line, i) in run.fractureSummary.evidence" :key="i">{{ line }}</li></ul>
            <button v-if="!run.archived" type="button" class="btn btn-ghost" :disabled="busy" @click="action(runFractureAnalysis, '已更新')">重新分析</button>
          </div>
          <div v-if="isLabPage" class="wf-step-nav"><button type="button" class="btn btn-ghost" @click="prevLabStep">上一步</button><button type="button" class="btn btn-primary" :disabled="!labStepDone('capture')" @click="nextLabStep">下一步：{{ nextLabStepTitle }}</button></div>
        </section>
        <section v-if="teacher && tab === 'deduct'" class="wf-panel wf-panel--teacher-op wf-panel--deduct">
          <div class="wf-classroom-bar">
            <p class="wf-hint wf-classroom-bar__hint">扣分将同步到学生「操作记录」；结束课堂后不可新增扣分，可随时重新开放。</p>
            <div class="wf-actions wf-classroom-bar__actions">
              <button type="button" class="btn btn-ghost" :disabled="busy" @click="action(() => control(run.classOpen ? 'close' : 'open'), '已更新')">{{ run.classOpen ? '结束课堂记录' : '重新开放课堂' }}</button>
              <button v-if="run.archived" type="button" class="btn btn-ghost" :disabled="busy" @click="action(() => control('return'), '已退回，学生可修改后重新提交')">退回操作记录</button>
            </div>
          </div>
          <div class="wf-deduct-layout">
            <div class="wf-deduct-form">
              <fieldset :disabled="busy"><p v-if="!run.classOpen" class="wf-message error">课堂记录已结束，不能新增扣分；请点击上方「重新开放课堂」后继续记录。</p><div v-if="!settings.catalogConfirmed" class="wf-message error wf-deduction-setup"><div><strong>请先确认扣分项和分值</strong><span>确认后，才能将现场观察记录为正式扣分。</span></div><button type="button" class="btn btn-ghost" @click="showSettings = true">去配置</button></div><div v-if="settings.catalogConfirmed" class="wf-deduction-heading"><div><h3>记录本次扣分</h3><p>勾选现场发生的项目，并补充具体情况。</p></div><button type="button" class="wf-text-button" @click="showSettings = true">编辑扣分项</button></div><div class="wf-checks"><label v-for="item in settings.catalog" :key="item.id"><input v-model="deduction.items" type="checkbox" :value="item.id" :disabled="!run.classOpen"> {{ item.label }}（{{ item.points }}分）</label></div><label>扣分依据<textarea v-model="deduction.reason" rows="3" placeholder="例如：第 2 组在设备运行中尝试打开保护罩，已现场提醒。"></textarea></label><label>撤销扣分时的说明<textarea v-model="controlReason" rows="2" placeholder="仅撤销明细时使用"></textarea></label><button class="btn btn-primary" :disabled="!run.classOpen || !settings.catalogConfirmed" @click="action(async () => { run = await call(`/runs/${run.id}/deductions`, 'POST', { ...deduction, revision: run.revision }); deduction.items = []; deduction.reason = ''; await listRuns() }, '保存本次扣分')">保存本次扣分</button></fieldset>
            </div>
            <section class="wf-deduction-records" aria-label="本次课堂扣分记录"><h3>已记录扣分</h3><p v-if="!run.deductions.length" class="wf-empty wf-empty--inline">暂无扣分记录</p><div v-else class="wf-table-wrap wf-table-wrap--tall"><table><thead><tr><th>项目</th><th>分值</th><th>依据</th><th></th></tr></thead><tbody><tr v-for="d in run.deductions" :key="d.id" :class="{ voided: d.voided }"><td>{{ d.label }}<small>{{ stamp(d.at) }}</small></td><td>{{ d.voided ? '已撤销' : `-${d.points}` }}</td><td>{{ d.reason || '—' }}</td><td><button v-if="!d.voided" type="button" class="btn btn-ghost" :disabled="busy" @click="action(async () => { run = await call(`/runs/${run.id}/deductions/${d.id}/void`, 'POST', { revision: run.revision, reason: controlReason }) }, '已撤销')">撤销</button></td></tr></tbody></table></div></section>
          </div>
        </section>
        <section v-if="teacher && tab === 'record'" class="wf-panel wf-panel--teacher-op wf-panel--op-doc">
          <p v-if="!run.archived" class="wf-empty wf-empty--inline">该组尚未提交实验操作记录。学生提交后，此处显示与课堂扣分同步的完整记录。</p>
          <OperationRecordDoc v-else :run="run" :points="points" :images="images" :deductions="activeDeductions" :read-only="true" />
        </section>
        <section v-if="isLabPage && tab === 'operation'" class="wf-panel wf-panel--student wf-panel--op-doc-wrap">
          <OperationRecordDoc :run="run" :points="points" :images="images" :deductions="activeDeductions" :read-only="!!run.archived" />
          <div v-if="!run.archived" class="wf-op-doc__actions">
            <p v-if="run.classOpen" class="wf-hint">教师仍在记录课堂扣分，本页约每 10 秒自动刷新扣分；确认无误后再提交。</p>
            <label class="wf-op-doc__confirm"><input v-model="operationConfirmed" type="checkbox"> 我已核对试验数据、断口图像与课堂扣分，确认提交本组实验操作记录。</label>
            <div class="wf-actions">
              <button type="button" class="btn btn-primary" :disabled="busy || !operationConfirmed || (run.trialItems ? !run.trialItems.every(t => t.dataReady && t.photoReady) : !labStepDone('capture') || !labStepDone('data') || !run.fractureSummary)" @click="action(submitOperationRecord, '实验操作记录已提交')">提交操作记录</button>
            </div>
          </div>
          <p v-else class="wf-message success">已于 {{ stamp(run.archivedAt) }} 提交，记录已锁定。教师可在「实验记录」中查看。</p>
          <div class="wf-step-nav"><button type="button" class="btn btn-ghost" @click="prevLabStep">上一步</button><button v-if="run.archived" type="button" class="btn btn-primary" @click="goStudentNav('s-report')">前往实验报告</button></div>
          <details v-if="!run.archived" class="wf-deductions-fold"><summary>申请重做</summary>
            <fieldset :disabled="busy"><label>重做原因<textarea v-model="controlReason" rows="2"></textarea></label><button type="button" class="btn btn-ghost" @click="action(async () => { run = await call(`/runs/${run.id}/retake-request`, 'POST', { reason: controlReason, revision: run.revision }) }, '申请已提交')">提交申请</button></fieldset>
          </details>
        </section>
        <section v-if="teacher && tab === 'report'" class="wf-panel wf-panel--teacher-op">

          <header class="wf-group-reports-head"><div><h3>{{ reportWorkspaceOpen ? '实验报告批改' : '学生报告列表' }}</h3><p>{{ run.groupName }} · 已提交 {{ submittedStudents }} / {{ groupReportStudents.length }} 人</p></div><div class="wf-actions"><button v-if="reportWorkspaceOpen" type="button" class="btn btn-ghost" :disabled="busy" @click="reportWorkspaceOpen=false">返回学生列表</button><button type="button" class="btn btn-ghost" :disabled="busy" @click="action(() => select(run.id))">刷新报告</button></div></header>
          <div v-if="!reportWorkspaceOpen" class="report-list-tools"><label>搜索学生<input v-model="reportQuery" placeholder="输入姓名或学号" type="search"></label><label>报告状态<select v-model="reportFilter"><option value="all">全部状态</option><option>待批改</option><option>已批改</option><option>已退回</option><option>未提交</option></select></label><span>共 {{ filteredReportStudents.length }} 位学生</span></div>
          <div v-if="!reportWorkspaceOpen" class="wf-table-wrap report-student-table"><table class="wf-group-reports-table"><thead><tr><th>学生姓名</th><th>学号</th><th>报告状态</th><th>最新版本</th><th>提交时间</th><th>最终成绩</th><th>相似度</th><th>操作</th></tr></thead><tbody><tr v-for="student in filteredReportStudents" :key="student.sid"><td><strong>{{ student.name }}</strong></td><td>{{ student.sid }}</td><td><span class="report-status" :class="{'is-done':student.latest?.finalScore!=null}">{{ reportStatus(student) }}</span></td><td>{{ student.latest ? `第 ${student.latest.version} 版` : '—' }}</td><td>{{ stamp(student.latest?.submittedAt) }}</td><td>{{ student.latest?.finalScore != null ? `${student.latest.finalScore} 分` : '—' }}</td><td>{{ student.latest?.similarity ? `${student.latest.similarity.maxPercent}%` : '—' }}</td><td><div class="report-row-actions"><button type="button" class="wf-text-button" :disabled="busy || !student.latest" @click="enterReport(student, 'review')">批改</button><button type="button" class="wf-text-button" :disabled="busy || !student.latest" @click="enterReport(student, 'similarity')">查重</button></div></td></tr><tr v-if="!filteredReportStudents.length"><td colspan="8" class="wf-empty">暂无匹配的学生</td></tr></tbody></table></div>
          <div v-if="reportWorkspaceOpen && selectedReport" class="report-grading-layout">
            <aside class="report-student-rail"><h4>学生列表</h4><input v-model="reportQuery" type="search" placeholder="搜索姓名 / 学号" aria-label="搜索批改学生"><select v-model="reportFilter" aria-label="筛选批改状态"><option value="all">全部状态</option><option>待批改</option><option>已批改</option><option>已退回</option><option>未提交</option></select><div class="report-student-scroll"><button v-for="student in filteredReportStudents" :key="student.sid" type="button" class="report-student-item" :class="{active:student.sid===selectedStudent?.sid}" :disabled="busy || !student.latest" @click="enterReport(student, reportMode)"><span><strong>{{ student.name }}</strong><small>{{ reportStatus(student) }}</small></span><small>{{ student.sid }}</small><small>{{ student.latest ? stamp(student.latest.submittedAt) : '尚未提交报告' }}</small><span>成绩 {{ student.latest?.finalScore ?? '—' }}</span></button><p v-if="!filteredReportStudents.length" class="wf-hint">暂无匹配学生</p></div></aside>
            <article class="report-document-pane"><header class="report-document-head"><div><h3>{{ selectedStudent?.name }} · 实验报告</h3><p>{{ selectedReport.fileName }}</p></div><div class="wf-actions"><label>版本 <select v-model="selectedReportId" :disabled="busy" aria-label="选择学生报告版本"><option v-for="report in selectedStudent?.reports || []" :key="report.id" :value="report.id">第 {{ report.version }} 版</option></select></label><button class="btn btn-ghost" :disabled="busy" @click="action(() => downloadFile(selectedReport.fileId, selectedReport.fileName))">下载原文件</button></div></header>
              <p v-if="selectedReport.returned" class="wf-message error">已退回：{{ selectedReport.returnReason }}</p><p v-if="selectedReport.templateCheck?.checked && selectedReport.templateCheck.missingSections.length" class="wf-message error">缺章节：{{ selectedReport.templateCheck.missingSections.join('、') }}</p><ReportPreview :file="selectedReport" />
            </article>
            <aside class="report-assessment-pane"><ReportReview :report="selectedReport" :busy="busy" :mode="reportMode" @update:mode="reportMode=$event"
                @ai="action(() => analyzeReport(`ai/${selectedReport.id}`))"
                @similarity="action(() => analyzeReport('similarity'))"
                @grade="values => action(() => review(selectedReport, 'grade', values), '已保存最终成绩')"
                @return="values => action(() => review(selectedReport, 'return', values), '报告已退回')" /></aside>
          </div>

        </section>
        <StudentReport v-if="isReportPage" :run="run" :draft="myDraft" :reports="myReports.filter(r => r.sid === sid)" :locked="reportLocked" :busy="busy" :template="settings.template"
          @upload="file => action(() => uploadReport(file))"
          @download="file => action(() => downloadFile(file.fileId, file.fileName))"
          @submit="action(async () => { run = await call(`/runs/${run.id}/submit`, 'POST', { requestId }); requestId = newId() }, '提交成功')" />
        <AdvancedPanel v-if="teacher && tab === 'retake'" :run="run" :runs="runs" :teacher="teacher" :tab="tab" :teacher-mode="tab" :sid="sid" @updated="run = $event" @selected="action(async () => { await listRuns(); await select($event.id) }, '临时组重做记录已建立')" />
      </main>
    </div>
  </section>
</template>

<style>
.report-list-tools{display:flex;align-items:end;gap:16px;flex-wrap:wrap;padding:16px;background:#f5f8f9;border-radius:8px}.report-list-tools label{display:grid;gap:7px;font-size:12px;color:#647980}.report-list-tools input{width:260px}.report-list-tools>span{margin-left:auto;font-size:13px;color:#647980}.report-student-table{max-height:600px!important}.report-student-table table{min-width:850px}.report-student-table th{position:sticky;top:0}.report-row-actions{display:flex;gap:16px;white-space:nowrap}.report-status{font-size:12px;color:#9a631c;background:#fff6e8;border-radius:5px;padding:5px 8px;white-space:nowrap}.report-status.is-done{color:#087c88;background:#e8f4f5}.report-grading-layout{display:grid;grid-template-columns:210px minmax(320px,1fr) 320px;gap:18px;align-items:start}.report-student-rail{background:#f8fafb;border:1px solid #e1e9ed;border-radius:8px;padding:14px;min-width:0}.report-student-rail h4{margin:0 0 14px}.report-student-rail>input,.report-student-rail>select{width:100%;margin-bottom:10px;font-size:12px}.report-student-scroll{max-height:760px;overflow:auto}.report-student-item{display:grid;width:100%;text-align:left;gap:9px;background:#fff;border:1px solid #dce6e9;border-radius:7px;padding:14px;margin-bottom:10px;font:inherit;font-size:12px;color:#52666f;cursor:pointer}.report-student-item>span:first-child{display:flex;justify-content:space-between;gap:8px}.report-student-item strong{color:#223945;font-size:14px}.report-student-item.active{border-color:#087c88;background:#e8f4f5}.report-document-pane{min-width:0;border:1px solid #dce6e9;border-radius:8px;overflow:hidden}.report-document-head{padding:16px;background:#f8fafb}.report-document-head h3{font-size:16px;margin:0}.report-document-head p{font-size:12px;overflow-wrap:anywhere;margin:8px 0}.report-assessment-pane{min-width:0;max-height:900px;overflow:auto}.report-assessment-pane .report-review{margin:0}.report-assessment-pane .review-block{padding:16px;margin:12px 0}.report-assessment-pane .review-block header{flex-wrap:wrap;gap:8px}.report-assessment-pane .review-block h3{font-size:16px}.report-assessment-pane .review-flow{font-size:12px}.report-assessment-pane .review-switch{gap:16px}.report-assessment-pane .review-switch button{font-size:14px}.report-assessment-pane .review-comment{font-size:13px}@media(min-width:1600px){.report-grading-layout{grid-template-columns:240px minmax(400px,1fr) 360px}}@media(max-width:1150px){.report-grading-layout{grid-template-columns:180px minmax(0,1fr)}.report-assessment-pane{grid-column:2;max-height:none}.report-student-rail{grid-row:1/3}}@media(max-width:700px){.report-grading-layout{grid-template-columns:1fr}.report-student-rail{grid-row:auto}.report-student-scroll{max-height:220px}.report-assessment-pane{grid-column:auto}.report-list-tools>span{margin-left:0}}

.workflow{color:#223945;max-width:none;width:100%;margin:0}.workflow--teacher{padding:0 clamp(12px,2vw,28px)}.workflow--student{max-width:none;width:100%;margin:0;padding:0 clamp(12px,2vw,28px)}.workflow-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;gap:16px}.workflow-heading--teacher{margin-bottom:16px}.workflow-heading--teacher h1{margin:0;font-size:26px}.wf-toolbar{display:flex;gap:8px;flex-shrink:0}.workflow h1{font-size:28px;margin:4px 0 8px}.workflow h2{font-size:23px;margin:0 0 10px}.workflow h3{font-size:18px;margin:0 0 14px}.workflow p{line-height:1.7}.workflow small,.wf-hint{color:#647980;line-height:1.7}.wf-hint{margin:0 0 18px;font-size:14px}.wf-mono{font-family:ui-monospace,Consolas,monospace;font-size:12px;word-break:break-all}.wf-form--single{grid-template-columns:1fr}.wf-fallback-import{margin-top:20px}.wf-data--linked{margin-top:16px}.wf-fracture-summary{margin-top:24px;padding:18px;background:#f1f8f8;border-radius:12px;border:1px solid #d7eef0}.wf-fracture-summary--inline{margin:16px 0;display:grid;gap:8px}.wf-fracture-evidence{margin:12px 0 0;padding-left:1.25em;color:#52666f;font-size:14px;line-height:1.75}.wf-fracture-evidence li{margin:6px 0}.wf-fracture-summary__verdict{font-size:22px;font-weight:700;color:#087c88;margin:0 0 8px}.wf-data--compact{margin-top:12px}.wf-data--compact h4{margin:0 0 10px;font-size:15px}.wf-data--compact svg{max-height:180px}.eyebrow{color:#087c88;font-weight:700;letter-spacing:2px}.workflow-layout{display:grid;grid-template-columns:265px minmax(0,1fr);gap:24px}.wf-panel--op-doc-wrap{background:transparent;border:0;padding:20px 0 26px;box-shadow:none}.wf-op-doc__actions{margin-top:24px;padding:20px 22px;background:#fff;border:1px solid #e1e9ed;border-radius:12px}.wf-op-doc__confirm{display:flex;align-items:flex-start;gap:10px;margin:12px 0 16px;font-size:14px;line-height:1.6;cursor:pointer}.wf-op-doc__confirm input{margin-top:4px}.wf-panel--op-doc{background:#f5f7f8;border:0;padding:24px}.workflow-heading--student h1{margin:0;font-size:26px}.workflow-layout--solo{grid-template-columns:1fr;max-width:none;margin:0;width:100%}.wf-main--student .wf-student-head{background:#fff;padding:20px 22px;border:1px solid #e1e9ed;border-radius:14px 14px 0 0;border-bottom:0;display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px}.wf-main--student .wf-student-head h2{margin:0;font-size:20px}.wf-student-head-main{display:grid;gap:6px;min-width:0;flex:1}.wf-main--student .wf-panel--student{border-radius:0 0 14px 14px;border-top:0;margin-top:0}.wf-stepper{display:flex;background:#fff;border:1px solid #e1e9ed;border-top:0;padding:12px 16px;gap:8px}.wf-step{flex:1;display:flex;align-items:center;justify-content:center;gap:8px;padding:12px 8px;border:1px solid #e1e9ed;border-radius:10px;background:#f8fafb;cursor:pointer;font:inherit;color:#52666f}.wf-step.active{border-color:#087c88;background:#e8f4f5;color:#087c88;font-weight:600}.wf-step.done .wf-step-num{background:#087c88;color:#fff}.wf-step-num{width:26px;height:26px;border-radius:50%;background:#dce6e9;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:700;flex-shrink:0}.wf-step-title{font-size:13px}.wf-step-nav{display:flex;justify-content:flex-end;gap:12px;margin-top:24px;padding-top:20px;border-top:1px solid #e5edef}.wf-checklist{list-style:none;padding:0;margin:0 0 20px;display:grid;gap:10px}.wf-checklist li{padding:10px 14px;border-radius:8px;background:#f5f8f9;color:#70828a}.wf-checklist li.ok{background:#e9f6ee;color:#24623c}.wf-checklist li.ok::before{content:'✓ '}.wf-checklist--inline{grid-template-columns:repeat(auto-fit,minmax(120px,1fr))}.wf-panel--op-record{max-width:100%}.wf-op-record__head{margin-bottom:24px;padding-bottom:18px;border-bottom:1px solid #e5edef;display:grid;gap:10px}.wf-op-record__meta{color:#647980;font-size:14px}.wf-op-record__block{margin:0 0 28px;padding:20px;border:1px solid #e8eef0;border-radius:12px;background:#fbfcfd}.wf-op-record__block h4{margin:0 0 14px;font-size:16px;color:#087c88}.wf-op-record__body{display:grid;gap:14px}.wf-op-record__note{margin:0;font-size:13px;color:#647980;line-height:1.65}.wf-op-record__facts{list-style:none;margin:0 0 16px;padding:0;display:grid;gap:10px}.wf-op-record__facts li{display:flex;justify-content:space-between;gap:16px;padding:10px 12px;background:#fff;border-radius:8px;border:1px solid #e8eef0;font-size:14px}.wf-op-record__facts span{color:#647980}.wf-op-record__archive{margin-top:8px;padding-top:20px;border-top:1px solid #e5edef}.photo-grid--record figure img{height:140px}.wf-deductions-fold{margin-top:16px}.wf-sidebar{display:flex;flex-direction:column;gap:10px}.run-card{text-align:left;border:1px solid #dce6e9;background:white;border-radius:12px;padding:16px;display:grid;gap:8px;cursor:pointer;color:inherit}.run-card.selected{border-color:#078592;box-shadow:0 0 0 2px #d7eef0;background:#f2fafb}.run-card span,.run-card small{font-size:12px}.run-card b{color:#087c88}.wf-main{min-width:0}.wf-main--teacher .wf-panel{margin-top:0}.wf-teacher-head{background:#fff;padding:20px 22px;border:1px solid #e1e9ed;border-radius:14px 14px 0 0;border-bottom:0;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-start;gap:12px}.wf-teacher-head h2{margin:0;font-size:20px}.wf-teacher-head-main{display:grid;gap:6px;min-width:0;flex:1}.wf-run-select{font:inherit;font-size:16px;font-weight:600;border:1px solid #ccdadd;border-radius:8px;padding:10px 12px;max-width:100%;color:#223945;background:#fff}.wf-teacher-meta{font-size:14px;color:#52666f}.wf-teacher-tags{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.tag.ok{background:#e9f6ee;color:#24623c}.tag-accent{background:#e8f4f5;color:#087c88;font-weight:700}.wf-tabs--teacher{padding:0 4px 0;background:#fff;border:1px solid #e1e9ed;border-top:0;border-bottom:0}.wf-main--teacher .wf-panel{border-radius:0 0 14px 14px;border-top:0}.wf-op-layout{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(0,.95fr);gap:28px;align-items:start}.wf-panel--teacher-op{padding-top:22px}.wf-metrics--compact{margin:0 0 20px}.wf-form--compact{margin:12px 0}.wf-empty--page{margin-top:40px}.wf-empty--inline{padding:16px;margin:8px 0;text-align:left;background:#f5f8f9;border-radius:8px;color:#70828a}.wf-teacher-comment{margin-top:12px;padding:12px;background:#f5f8f9;border-radius:8px;font-size:14px}.wf-run-head{background:#fff;padding:24px;border:1px solid #e1e9ed;border-radius:14px;display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.wf-run-head small{word-break:break-all}.wf-tabs{display:flex;gap:4px;padding:16px 0;overflow:auto}.wf-tabs button{white-space:nowrap;background:transparent;border:0;padding:12px 20px;border-radius:8px;color:#52666f;font-weight:600;cursor:pointer}.wf-tabs button.active{background:#087c88;color:white}.wf-panel{background:#fff;border:1px solid #e1e9ed;padding:26px;border-radius:14px}.workflow fieldset{border:0;margin:0;padding:0;min-width:0}.workflow input,.workflow select,.workflow textarea{border:1px solid #ccdadd;background:#fff;border-radius:7px;padding:10px;color:#223945;font:inherit;max-width:100%}.workflow textarea{width:100%;resize:vertical}.workflow input:focus,.workflow select:focus,.workflow textarea:focus{outline:2px solid #90cdd2;outline-offset:1px}.wf-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:18px 0}.wf-form label{display:grid;gap:8px;font-size:13px}.wf-actions{display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin:14px 0}.wf-actions label.btn input{max-width:180px;font-size:12px;border:0;padding:0 0 0 8px}.workflow hr{border:0;border-top:1px solid #e5edef;margin:28px 0}.photo-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:16px}.photo-grid figure{margin:0;border:1px solid #e0e9ec;border-radius:12px;overflow:hidden;padding:12px}.photo-grid img{width:100%;height:180px;object-fit:contain;background:#f3f6f7;border-radius:6px}.photo-grid figcaption{font-size:13px;margin:8px 0}.photo-grid small{display:block}.photo-grid button{border:0;background:transparent;color:#087c88;cursor:pointer}.camera video{width:100%;max-height:450px;background:#172832;border-radius:12px}.wf-message{padding:12px 16px;border-radius:8px;white-space:pre-wrap}.wf-message.error{background:#fff0ee;color:#a33728}.wf-message.success{background:#e9f6ee;color:#24623c}.wf-empty{padding:30px;background:#f5f8f9;border-radius:10px;text-align:center;color:#70828a}.wf-table-wrap{overflow:auto;max-height:300px;margin:16px 0}.workflow table{width:100%;border-collapse:collapse;font-size:13px}.workflow th,.workflow td{border:1px solid #dce6e9;padding:10px;text-align:left;white-space:pre-wrap;word-break:break-word}.workflow th{background:#f0f6f7}.wf-data{margin-top:28px}.wf-data svg{width:100%;font-size:12px}.wf-metrics{display:flex;gap:18px;margin:20px 0;flex-wrap:wrap}.wf-metrics>div{display:grid;gap:8px;min-width:150px;padding:18px;background:#f1f8f8;border-radius:10px}.wf-metrics strong{font-size:25px;color:#087c88}.workflow button:disabled{opacity:.55;cursor:not-allowed}.workflow input[type=file]{max-width:100%}@media(max-width:900px){.workflow-layout{grid-template-columns:1fr}.wf-op-layout{grid-template-columns:1fr}.wf-sidebar{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.wf-sidebar h3{grid-column:1/-1}.wf-run-head{flex-direction:column}.wf-teacher-head{flex-direction:column}}@media(max-width:520px){.wf-form{grid-template-columns:1fr}.wf-panel{padding:18px}.workflow-heading{align-items:flex-start;gap:12px}.workflow h1{font-size:23px}.wf-sidebar{grid-template-columns:1fr}.wf-tabs button{padding:10px 14px}}
</style>
<style>
.wf-stepper--teacher{padding:12px 16px;flex-wrap:wrap;border:1px solid #e1e9ed;border-top:0;background:#fff}.wf-stepper--teacher .wf-step{flex:1 1 auto;min-width:88px;font-size:13px;padding:10px 12px}.wf-report-pick{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px}.wf-report-chip{border:1px solid #dce6e9;background:#f8fafb;border-radius:999px;padding:8px 16px;cursor:pointer;font:inherit;color:#52666f}.wf-report-chip.active{background:#087c88;border-color:#087c88;color:#fff}.report-card--solo{margin:0}.wf-table-wrap--tall{max-height:none}.wf-checks{display:grid;gap:12px;margin:20px 0}.workflow td small{display:block;margin-top:6px}.workflow textarea{margin:8px 0 14px}.workflow .voided{color:#89969d}.report-card{border:1px solid #dce6e9;padding:22px;border-radius:12px;margin:18px 0}.report-card small{word-break:break-all}.extracted{white-space:pre-wrap;line-height:1.8;max-height:320px;overflow:auto;background:#f6f8f9;padding:16px}.pdf-preview{width:100%;height:650px;border:1px solid #ccdadd;border-radius:8px}.workflow summary{cursor:pointer;margin:12px 0;color:#087c88}.workflow fieldset:disabled{opacity:.7}
.wf-teacher-toolbar{margin:0 0 16px;display:flex;justify-content:flex-end;align-items:center;gap:8px}
.wf-teacher-bar{background:#fff;border:1px solid #e1e9ed;border-radius:14px;padding:18px 22px 0;margin-bottom:16px;display:grid;grid-template-columns:minmax(0,1fr) minmax(200px,300px);grid-template-rows:auto auto;gap:12px 20px;align-items:start}.wf-teacher-bar h2{margin:0 0 6px;font-size:20px}.wf-teacher-bar__main{grid-column:1;min-width:0}.wf-teacher-bar__pick{grid-column:2;grid-row:1;align-self:start}.wf-teacher-tabs{grid-column:1/-1;display:flex;flex-wrap:wrap;gap:8px;padding:14px 0 16px;border-top:1px solid #e5edef;margin-top:4px}.wf-teacher-tab{border:1px solid #dce6e9;background:#f8fafb;border-radius:999px;padding:10px 18px;font:inherit;font-size:14px;color:#52666f;cursor:pointer;transition:background .15s,border-color .15s,color .15s}.wf-teacher-tab:hover{border-color:#90cdd2;color:#087c88}.wf-teacher-tab.active{background:#087c88;border-color:#087c88;color:#fff;font-weight:600}.wf-main--teacher>.wf-panel,.wf-main--teacher>.advanced-panel{border-radius:14px;border:1px solid #e1e9ed;margin-top:0}.wf-classroom-bar{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:22px;padding-bottom:18px;border-bottom:1px solid #e5edef}.wf-classroom-bar__hint{margin:0!important;flex:1;min-width:220px}.wf-deduct-layout{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(260px,.95fr);gap:28px;align-items:start}.wf-panel--op-doc .wf-op-doc{max-width:none;margin:0}.wf-config-trigger{display:inline-flex;align-items:center;gap:8px}.wf-config-trigger small{padding:2px 7px;border-radius:99px;background:#e9f6ee;color:#24623c;font-size:12px}.wf-config-trigger.is-attention{border-color:#e2b5a9;color:#a33728}.wf-config-trigger.is-attention small{background:#fff0ee;color:#a33728}.wf-config-trigger__icon{font-size:16px}.wf-config-mask{position:fixed;inset:0;z-index:30;background:rgba(31,48,57,.24);display:flex;justify-content:flex-end}.wf-config-drawer{width:min(580px,100vw);height:100%;box-sizing:border-box;overflow-y:auto;background:#fff;box-shadow:-12px 0 32px rgba(31,48,57,.16);padding:28px}.wf-config-drawer__head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding-bottom:20px;border-bottom:1px solid #e5edef;margin-bottom:22px}.wf-config-drawer__head .eyebrow{font-size:12px;margin:0 0 4px}.wf-config-drawer__head h2{font-size:24px;margin:0 0 8px}.wf-config-drawer__head p:not(.eyebrow){margin:0;color:#647980;font-size:14px}.wf-config-close{min-width:38px;padding:4px 10px;font-size:26px;line-height:1}.wf-deduction-config{display:grid;gap:10px;margin:0 0 14px}.wf-deduction-config__labels,.wf-deduction-config__row{display:grid;grid-template-columns:minmax(0,1fr) 112px 72px;gap:10px;align-items:center}.wf-deduction-config__labels{color:#647980;font-size:12px;padding:0 2px}.wf-deduction-config__row{padding:10px;border:1px solid #e1e9ed;border-radius:10px;background:#fbfcfc}.wf-deduction-config__row input{width:100%;box-sizing:border-box}.wf-deduction-config__row label{position:relative;display:block}.wf-deduction-config__row label input{padding-right:30px}.wf-deduction-config__row label span{position:absolute;right:11px;top:10px;color:#647980;font-size:13px;pointer-events:none}.wf-deduction-config__row .btn{padding:8px 6px;font-size:13px}.wf-config-confirm{display:flex;gap:8px;align-items:flex-start;margin:18px 0 6px;font-size:14px;line-height:1.5}.wf-config-confirm input{margin-top:3px}.wf-config-actions{display:flex;gap:10px;margin-top:20px}.wf-deduction-setup{display:flex;align-items:center;justify-content:space-between;gap:16px}.wf-deduction-setup div{display:grid;gap:2px}.wf-deduction-setup span{font-size:13px}.wf-deduction-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.wf-deduction-heading h3{margin-bottom:4px}.wf-deduction-heading p{margin:0;color:#647980;font-size:13px}.wf-text-button{border:0;background:transparent;padding:2px 0;color:#087c88;cursor:pointer;font:inherit;font-size:13px;white-space:nowrap}@media(max-width:620px){.wf-teacher-toolbar{margin-bottom:12px}.wf-config-drawer{padding:20px 16px}.wf-deduction-config__labels{display:none}.wf-deduction-config__row{grid-template-columns:minmax(0,1fr) 86px 58px;padding:8px;gap:7px}.wf-deduction-config__row .btn{font-size:12px;padding:8px 3px}.wf-deduction-setup{align-items:flex-start;flex-direction:column}.wf-deduction-heading{flex-direction:column;gap:8px}}
.wf-deduction-records h3{margin-bottom:8px}.wf-deduction-records .wf-table-wrap{margin:12px 0 0}
.wf-teacher-head .eyebrow{margin:0;color:#647980;font-size:11px;letter-spacing:1px}.wf-teacher-members{color:#647980;font-size:13px;line-height:1.6}.wf-run-picker{display:grid;gap:6px;width:100%;font-size:12px;color:#52666f}.wf-run-picker .wf-run-select{width:100%;font-size:13px;font-weight:500;padding:9px 10px}
@media(max-width:900px){.wf-teacher-bar{grid-template-columns:1fr}.wf-teacher-bar__pick{grid-column:1;grid-row:auto}.wf-deduct-layout{grid-template-columns:1fr}.wf-deduction-records{margin-top:24px;padding-top:20px;border-top:1px solid #e5edef;border-left:0;padding-left:0}}
</style>

<style>
.wf-group-reports-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:0 0 18px}.wf-group-reports-head h3{margin:0}.wf-group-reports-head p{margin:6px 0 0;color:#647980;font-size:13px}.wf-group-reports-head label{font-size:13px;color:#52666f}.wf-group-reports-head select{max-width:100%;margin-left:8px;padding:8px;border:1px solid #dce6e9;border-radius:6px;background:#fff}.wf-group-reports-table{width:100%}.wf-group-reports-table tr.is-selected{background:#edf7f8}.wf-group-reports-table th{white-space:nowrap}.wf-group-reports-table td{font-size:13px}.report-card--solo>.report-preview{margin:16px 0 24px}@media(max-width:700px){.wf-group-reports-head{align-items:flex-start;flex-direction:column}}
</style>

<style>
.wf-trial-nav{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;padding:18px;background:#f5f8f9}.wf-trial-nav button{display:grid;gap:8px;padding:16px;text-align:left;background:white;border:1px solid #dce6e9;border-radius:8px;color:#405766;cursor:pointer}.wf-trial-nav button.active{border-color:#087c88;background:#eaf7f7;color:#087c88}.wf-trial-nav small{font-size:12px}@media(max-width:650px){.wf-trial-nav{grid-template-columns:1fr 1fr}}
</style>
