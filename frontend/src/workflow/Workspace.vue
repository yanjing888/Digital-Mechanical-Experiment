<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { call, form, fileBlob, downloadFile } from './api.js'
import AdvancedPanel from './AdvancedPanel.vue'
import { curveGeometry } from './curve.js'
const props = defineProps({ page: String, teacher: Boolean, sid: String })
const runs = ref([]), run = ref(null), busy = ref(false), error = ref(''), notice = ref('')
const tab = ref(props.teacher ? 'deduct' : props.page === 's-report' ? 'report' : 'capture')
const teacherSteps = [
  { id: 'deduct', title: '现场扣分' },
  { id: 'opgrade', title: '操作记录分' },
  { id: 'report', title: '报告评阅' },
  { id: 'assist', title: '查重与 AI' },
  { id: 'retake', title: '重做安排' },
]
const selectedReportId = ref(null)
const selectedReport = computed(() => (run.value?.reports || []).find(r => r.id === selectedReportId.value))
const meta = reactive({ specimenId: '', deviceId: '', experimentAt: '', note: '' })
let metadataBaseline = {}
const selectionKey = `lab-run-selection-${props.teacher ? 'teacher' : props.sid}`
const preview = ref(null), dataFile = ref(null), mapping = reactive({ start: 2, force: 1, displacement: 0, unit: 'kN' })
const angle = ref('正面'), images = reactive({}), video = ref(null), camera = ref(false)
let stream, timer, disposed = false
const newId = () => crypto.randomUUID()
const retakeWindow = reactive({ start: '', end: '' })
const settings = ref({ catalog: [], requiredSections: [] }), showSettings = ref(false), sectionsText = ref('')
const deduction = reactive({ items: [], reason: '' }), grade = reactive({ score: 100, comment: '', reason: '' })
const controlReason = ref(''), observation = reactive({ features: '', judgment: 'uncertain' })
const reportGrades = reactive({}), reportPreview = ref(''), requestId = ref(crypto.randomUUID())
const myDraft = computed(() => run.value?.drafts?.[props.sid])
const myReports = computed(() => run.value?.reports || [])
const workflowTabs = computed(() => [['capture', '断口采集'], ['data', '实验数据'], ['operation', '操作记录'], ['report', '个人报告']])
const labSteps = [{ id: 'capture', title: '试件与断口' }, { id: 'data', title: '实验数据' }, { id: 'operation', title: '归档记录' }]
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
  if (id === 'capture') return !!(r.specimenId && r.deviceId && r.experimentAt && r.photos.length)
  if (id === 'data') return !!r.data
  if (id === 'operation') return !!r.archived
  return false
}
function suggestLabStep() {
  for (const s of labSteps) if (!labStepDone(s.id)) return s.id
  return 'operation'
}
function goLabStep(id) { stopCamera(); tab.value = id }
const labStepIndex = computed(() => labSteps.findIndex(s => s.id === tab.value))
function nextLabStep() { if (labStepIndex.value >= 0 && labStepIndex.value < labSteps.length - 1) tab.value = labSteps[labStepIndex.value + 1].id }
function prevLabStep() { if (labStepIndex.value > 0) tab.value = labSteps[labStepIndex.value - 1].id }
function goStudentNav(page) { document.querySelector(`.nav-item[data-p="${page}"]`)?.click() }
const latestOwn = computed(() => myReports.value.filter(r => r.sid === props.sid).at(-1))
const reportLocked = computed(() => !!latestOwn.value && !latestOwn.value.returned)
const localTime = value => value ? new Date(new Date(value).getTime() - new Date(value).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ''
async function loadSettings() { settings.value = await call('/settings'); sectionsText.value = settings.value.requiredSections.join('\n'); retakeWindow.start = localTime(settings.value.retakeStart); retakeWindow.end = localTime(settings.value.retakeEnd) }
async function saveSettings() { settings.value = await call('/settings', 'PUT', { ...settings.value, requiredSections: sectionsText.value.split('\n').filter(Boolean), retakeStart: retakeWindow.start ? new Date(retakeWindow.start).toISOString() : '', retakeEnd: retakeWindow.end ? new Date(retakeWindow.end).toISOString() : '' }) }
async function control(actionName) {
  run.value = await call(`/runs/${run.value.id}/control`, 'POST', { action: actionName, revision: run.value.revision, reason: controlReason.value || grade.reason, score: grade.score, comment: grade.comment }); await listRuns()
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
async function review(report, actionName) {
  const form = reportGrades[report.id] || {}
  run.value = await call(`/runs/${run.value.id}/reports/${report.id}/review`, 'POST', { ...form, action: actionName, revision: run.value.revision })
}
const stateLabel = r => ({ pending: '待采集', collecting: '采集中', archived: '已归档' }[r?.status] || '待采集')
const runPickerLabel = r => `${r.groupName} · ${r.expName} · ${r.operationScore}分`
function onPickRun(event) {
  const id = event.target.value
  if (id && id !== run.value?.id) action(() => select(id))
}
const stamp = v => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—'
const points = computed(() => run.value?.data?.points || [])
const curve = computed(() => curveGeometry(points.value))
async function action(fn, message = '') {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await fn(); if (!disposed) notice.value = message } catch (e) { if (!disposed) error.value = e.message } finally { busy.value = false }
}
async function listRuns() { runs.value = await call('/runs') }
watch(run, value => {
  if (!value) return
  const item = runs.value.find(r => r.id === value.id)
  if (item) Object.assign(item, { status: value.status, photoCount: value.photos.length, dataReady: !!value.data, operationScore: value.operationScore, classOpen: value.classOpen, archived: value.archived })
})
async function photos() {
  for (const p of run.value?.photos || []) if (!images[p.id]) {
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
  Object.assign(observation, run.value.observations?.[props.sid] || { features: '', judgment: 'uncertain' })
  Object.assign(grade, { score: run.value.operationScore, comment: run.value.finalComment || '', reason: '' })
  deduction.items = []; deduction.reason = ''; controlReason.value = ''
  requestId.value = crypto.randomUUID()
  if (reportPreview.value) URL.revokeObjectURL(reportPreview.value); reportPreview.value = ''
  for (const report of run.value.reports) reportGrades[report.id] = { score: report.finalScore ?? 100, comment: report.finalComment || '' }
  const reports = run.value.reports || []
  if (!selectedReportId.value || !reports.some(r => r.id === selectedReportId.value)) selectedReportId.value = reports[0]?.id ?? null
  await photos()
}
async function aiOperationRef() {
  run.value = await call(`/runs/${run.value.id}/ai/operation`, 'POST', { revision: run.value.revision })
}
async function saveMeta() {
  run.value = await call(`/runs/${run.value.id}`, 'PATCH', { ...meta, experimentAt: meta.experimentAt ? new Date(meta.experimentAt).toISOString() : '', revision: run.value.revision, expectedMetadata: metadataBaseline })
  setMeta()
}
async function uploadPhoto(file) {
  if (!file) return
  run.value = await call(`/runs/${run.value.id}/photos`, 'POST', form(file, { angle: angle.value })); await photos()
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
  await uploadPhoto(new File([blob], `断口-${Date.now()}.jpg`, { type: 'image/jpeg' })); stopCamera()
}
async function chooseData(file) { if (!file) return; dataFile.value = file; preview.value = await call('/preview', 'POST', form(file)) }
watch(() => props.teacher, teacher => {
  if (teacher && ['capture', 'data', 'operation', 'report'].includes(tab.value) && !teacherSteps.some(s => s.id === tab.value)) tab.value = 'deduct'
}, { immediate: true })
watch(() => props.page, page => {
  if (props.teacher) return
  if (page === 's-report') tab.value = 'report'
  else if (page === 's-lab') tab.value = suggestLabStep()
}, { immediate: true })
watch(() => run.value?.archived, archived => {
  if (archived && isLabPage.value) tab.value = 'operation'
})
onMounted(() => action(async () => {
  await loadSettings(); await listRuns(); if (runs.value.length) await select(runs.value.find(r => r.id === sessionStorage.getItem(selectionKey))?.id || runs.value[0].id)
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
}))
onUnmounted(() => { disposed = true; clearInterval(timer); stopCamera(); Object.values(images).forEach(URL.revokeObjectURL); if (reportPreview.value) URL.revokeObjectURL(reportPreview.value) })
</script>

<template>
  <section class="workflow" :class="{ 'workflow--student': !teacher }">
    <header v-if="!teacher" class="workflow-heading workflow-heading--student"><h1>{{ studentPageTitle }}</h1></header>
    <div v-else class="wf-teacher-toolbar"><button type="button" class="btn btn-ghost wf-config-trigger" :class="{ 'is-attention': !settings.catalogConfirmed }" @click="showSettings = true"><span class="wf-config-trigger__icon" aria-hidden="true">⚙</span><span>扣分项配置</span><small>{{ settings.catalogConfirmed ? `${settings.catalog.length} 项已启用` : '待确认' }}</small></button></div>
    <p v-if="error" role="alert" class="wf-message error">{{ error }}</p><p v-if="notice" role="status" class="wf-message success">{{ notice }}</p>
    <div v-if="teacher && showSettings" class="wf-config-mask" @click.self="showSettings = false">
      <aside class="wf-config-drawer" role="dialog" aria-modal="true" aria-labelledby="deduction-config-title">
        <header class="wf-config-drawer__head"><div><p class="eyebrow">课堂操作记录</p><h2 id="deduction-config-title">扣分项配置</h2><p>设置现场记录时可选的扣分项与对应分值。</p></div><button type="button" class="btn btn-ghost wf-config-close" aria-label="关闭扣分项配置" @click="showSettings = false">×</button></header>
        <fieldset :disabled="busy"><div class="wf-deduction-config"><div class="wf-deduction-config__labels"><span>扣分项</span><span>扣分值</span><span>操作</span></div><div v-for="(item,i) in settings.catalog" :key="item.id" class="wf-deduction-config__row"><input v-model="item.label" :aria-label="`第 ${i + 1} 个扣分项名称`" placeholder="例如：实验运行时尝试打开保护罩"><label><input v-model.number="item.points" :aria-label="`第 ${i + 1} 个扣分项分值`" type="number" min="0" max="100"><span>分</span></label><button type="button" class="btn btn-ghost" :aria-label="`移除${item.label || '该扣分项'}`" @click="settings.catalog.splice(i,1)">移除</button></div></div><button type="button" class="btn btn-ghost" @click="settings.catalog.push({ id: newId(), label: '', points: 0 })">+ 增加扣分项</button><label class="wf-config-confirm"><input v-model="settings.catalogConfirmed" type="checkbox"> 我已确认扣分项目和分值，可用于课堂记录</label><div class="wf-config-actions"><button class="btn btn-primary" @click="action(saveSettings, '扣分项已保存')">保存扣分项</button><button type="button" class="btn btn-ghost" @click="showSettings = false">取消</button></div></fieldset>
        <details class="wf-config-advanced"><summary>其他教学设置</summary><div class="wf-form"><label>必需章节名称（每行一项）<textarea v-model="sectionsText" rows="4" placeholder="收到学校正式模板后填写"></textarea></label><label>查重排除的模板文字（每行一段）<textarea v-model="settings.templateExclusions" rows="4"></textarea></label><label>组内相似度阈值（%）<input v-model.number="settings.similarityThreshold" type="number" min="0" max="100"></label><label>后提交雷同报告建议扣分<input v-model.number="settings.similarityPenalty" type="number" min="0" max="100"></label></div><div class="wf-form"><label>补做窗口开始<input v-model="retakeWindow.start" type="datetime-local"></label><label>补做窗口结束<input v-model="retakeWindow.end" type="datetime-local"></label></div><label>上传学校正式报告模板 <input type="file" accept=".doc,.docx,.pdf" @change="action(async () => { if ($event.target.files[0]) settings = await call('/template','POST',form($event.target.files[0])) }, '学校模板已保存')"></label><p v-if="settings.template">当前模板：{{ settings.template.name }}</p></details>
      </aside>
    </div>
    <div class="workflow-layout" :class="{ 'workflow-layout--solo': teacher || !teacher }">
      <p v-if="!teacher && !runs.length" class="wf-empty wf-empty--page">暂无实验任务，请先在「任务中心」查看或联系教师。</p>
      <p v-if="teacher && !runs.length" class="wf-empty wf-empty--page">暂无实验记录</p>
      <main v-if="run" class="wf-main" :class="{ 'wf-main--teacher': teacher, 'wf-main--student': !teacher }">
        <div v-if="teacher" class="wf-teacher-head">
          <div class="wf-teacher-head-main">
            <select v-if="runs.length > 1" class="wf-run-select" :value="run.id" :disabled="busy" aria-label="选择小组" @change="onPickRun"><option v-for="r in runs" :key="r.id" :value="r.id">{{ runPickerLabel(r) }}</option></select>
            <h2 v-else>{{ run.groupName }} · {{ run.expName }}</h2>
            <span class="wf-teacher-meta">第 {{ run.attempt }} 次 · {{ run.members.map(m => m.name).join('、') }}</span>
          </div>
          <div class="wf-teacher-tags"><span class="tag" :class="{ ok: run.classOpen }">{{ run.classOpen ? '课堂开放' : '课堂结束' }}</span><span class="tag">{{ stateLabel(run) }}</span><span class="tag tag-accent">{{ run.operationScore }} 分</span></div>
        </div>
        <div v-else class="wf-student-head">
          <div class="wf-student-head-main">
            <select v-if="runs.length > 1" class="wf-run-select" :value="run.id" :disabled="busy" aria-label="选择实验记录" @change="onPickRun"><option v-for="r in runs" :key="r.id" :value="r.id">{{ r.expName }} · {{ r.groupName }} · 第{{ r.attempt }}次</option></select>
            <h2 v-else>{{ run.expName }} · {{ run.groupName }}</h2>
            <span class="wf-teacher-meta">第 {{ run.attempt }} 次 · {{ run.members.map(m => m.name).join('、') }}</span>
          </div>
          <div class="wf-teacher-tags"><span class="tag" :class="{ ok: run.classOpen }">{{ run.classOpen ? '课堂开放' : '课堂结束' }}</span><span class="tag">{{ stateLabel(run) }}</span><span class="tag tag-accent">{{ run.operationScore }} 分</span></div>
        </div>
        <nav v-if="isLabPage" class="wf-stepper" aria-label="现场实验步骤">
          <button v-for="(s, i) in labSteps" :key="s.id" type="button" class="wf-step" :class="{ active: tab === s.id, done: labStepDone(s.id), locked: i > 0 && !labStepDone(labSteps[i - 1].id) && tab !== s.id }" @click="goLabStep(s.id)"><span class="wf-step-num">{{ i + 1 }}</span><span class="wf-step-title">{{ s.title }}</span></button>
        </nav>
        <nav v-else-if="teacher" class="wf-stepper wf-stepper--teacher" aria-label="评阅功能">
          <button v-for="s in teacherSteps" :key="s.id" type="button" class="wf-step" :class="{ active: tab === s.id }" @click="tab = s.id">{{ s.title }}</button>
        </nav>
        <section v-if="isLabPage && tab === 'capture'" class="wf-panel wf-panel--student">
          <h3>试件信息</h3>
          <fieldset :disabled="busy || run.archived"><div class="wf-form"><label>试件编号<input v-model="meta.specimenId" placeholder="填写试件上的编号" maxlength="120"></label><label>设备编号<input v-model="meta.deviceId" placeholder="例如：拉力机01" maxlength="120"></label><label>实验完成时间<input v-model="meta.experimentAt" type="datetime-local"></label><label>备注<input v-model="meta.note" placeholder="本次实验补充说明" maxlength="2000"></label></div><button class="btn btn-primary" @click="action(saveMeta, '试件信息已保存')">保存试件信息</button></fieldset>
          <hr><h3>断口照片</h3>
          <fieldset :disabled="busy || run.archived"><div class="wf-actions"><label>角度 <select v-model="angle"><option>正面</option><option>侧面</option><option>斜面</option><option>补充</option></select></label><button class="btn btn-primary" @click="action(startCamera)">打开摄像头</button><label class="btn btn-ghost">上传照片<input type="file" accept="image/jpeg,image/png" @change="action(() => uploadPhoto($event.target.files[0]), '原始照片已保存')"></label></div></fieldset>
          <div v-if="camera" class="camera"><video ref="video" autoplay playsinline muted></video><div class="wf-actions"><button class="btn btn-primary" :disabled="busy" @click="action(snap, '照片已保存')">拍摄并保存</button><button class="btn btn-ghost" @click="stopCamera">关闭摄像头</button></div></div>
          <div class="photo-grid"><figure v-for="p in run.photos" :key="p.id"><img :src="images[p.id]" :alt="`${p.angle}断口照片`"><figcaption>{{ p.angle }} · {{ p.width }} × {{ p.height }}<small>{{ p.warning }}</small></figcaption><div class="wf-actions"><button @click="action(() => downloadFile(p.id, p.name))">下载原图</button><button v-if="!run.archived" :disabled="busy" @click="action(async () => { run = await call(`/runs/${run.id}/photos/${p.id}`, 'DELETE') }, '已移除，原文件仍保留')">不采用此图</button></div></figure></div><p v-if="!run.photos.length" class="wf-empty wf-empty--inline">请至少保存一张断口照片</p>
          <div v-if="isLabPage" class="wf-step-nav"><button v-if="labStepIndex > 0" type="button" class="btn btn-ghost" @click="prevLabStep">上一步</button><button type="button" class="btn btn-primary" :disabled="!labStepDone('capture')" @click="nextLabStep">下一步：实验数据</button></div>
        </section>
        <section v-if="isLabPage && tab === 'data'" class="wf-panel wf-panel--student"><h3>导入实验数据</h3><fieldset :disabled="busy || run.archived"><input type="file" accept=".csv,.txt,.tsv,.xls,.xlsx" @change="action(() => chooseData($event.target.files[0]))">
          <template v-if="preview"><p>预览前12行，共 {{ preview.rowCount }} 行。列编号从1开始。</p><div class="wf-table-wrap"><table><thead><tr><th>行</th><th v-for="(_, i) in preview.rows.reduce((a,b) => b.length > a.length ? b : a, [])" :key="i">第{{ i + 1 }}列</th></tr></thead><tbody><tr v-for="(row, i) in preview.rows" :key="i"><th>{{ i + 1 }}</th><td v-for="(v,j) in row" :key="j">{{ v }}</td></tr></tbody></table></div><div class="wf-form"><label>数据起始行<input v-model.number="mapping.start" type="number" min="1"></label><label>力所在列<select v-model.number="mapping.force"><option v-for="(_, i) in preview.rows.reduce((a,b) => b.length > a.length ? b : a, [])" :value="i">第{{ i + 1 }}列</option></select></label><label>位移所在列<select v-model.number="mapping.displacement"><option v-for="(_, i) in preview.rows.reduce((a,b) => b.length > a.length ? b : a, [])" :value="i">第{{ i + 1 }}列</option></select></label><label>原始力单位<select v-model="mapping.unit"><option>kN</option><option>N</option></select></label></div><button class="btn btn-primary" @click="action(async () => { run = await call(`/runs/${run.id}/data`, 'POST', form(dataFile, mapping)); preview = null }, '实验数据及原文件已保存')">确认列映射并导入</button></template></fieldset>
          <div v-if="run.data" class="wf-data"><h3>力—位移曲线</h3><div class="wf-metrics"><div><small>最大力</small><strong>{{ run.data.maxF.toFixed(3) }} kN</strong></div><div><small>最大位移</small><strong>{{ run.data.maxD.toFixed(3) }} mm</strong></div><div><small>有效点数</small><strong>{{ run.data.pointCount }}</strong></div></div><svg viewBox="0 0 750 280" role="img" aria-label="本次导入的力位移曲线"><path d="M45 25 V245 H720" fill="none" stroke="#aab9bf"/><text x="5" y="20">F / kN</text><text x="645" y="274">位移 / mm</text><text x="5" y="40">{{ curve.maxF.toFixed(2) }}</text><text x="5" y="245">{{ curve.minF.toFixed(2) }}</text><text x="45" y="264">{{ curve.minD.toFixed(2) }}</text><text x="710" y="264" text-anchor="end">{{ curve.maxD.toFixed(2) }}</text><polyline :points="curve.line" fill="none" stroke="#087c88" stroke-width="2"/></svg><button class="btn btn-ghost" @click="action(() => downloadFile(run.data.fileId, run.data.fileName))">下载原始文件</button></div><p v-else class="wf-empty wf-empty--inline">请选择原设备导出的数据文件</p>
          <div v-if="isLabPage" class="wf-step-nav"><button type="button" class="btn btn-ghost" @click="prevLabStep">上一步</button><button type="button" class="btn btn-primary" :disabled="!labStepDone('data')" @click="nextLabStep">下一步：归档</button></div>
        </section>
        <section v-if="teacher && tab === 'deduct'" class="wf-panel wf-panel--teacher-op">
          <fieldset :disabled="busy"><div v-if="!settings.catalogConfirmed" class="wf-message error wf-deduction-setup"><div><strong>请先确认扣分项和分值</strong><span>确认后，才能将现场观察记录为正式扣分。</span></div><button type="button" class="btn btn-ghost" @click="showSettings = true">去配置</button></div><div v-if="settings.catalogConfirmed" class="wf-deduction-heading"><div><h3>记录本次扣分</h3><p>勾选现场发生的项目，并补充具体情况。</p></div><button type="button" class="wf-text-button" @click="showSettings = true">编辑扣分项</button></div><div class="wf-checks"><label v-for="item in settings.catalog" :key="item.id"><input v-model="deduction.items" type="checkbox" :value="item.id" :disabled="!run.classOpen"> {{ item.label }}（{{ item.points }}分）</label></div><label>扣分依据<textarea v-model="deduction.reason" rows="3" placeholder="例如：第 2 组在设备运行中尝试打开保护罩，已现场提醒。"></textarea></label><label>撤销扣分时的说明<textarea v-model="controlReason" rows="2" placeholder="仅撤销明细时使用"></textarea></label><button class="btn btn-primary" :disabled="!run.classOpen || !settings.catalogConfirmed" @click="action(async () => { run = await call(`/runs/${run.id}/deductions`, 'POST', { ...deduction, revision: run.revision }); deduction.items = []; deduction.reason = ''; await listRuns() }, '保存本次扣分')">保存本次扣分</button></fieldset>
          <hr><p v-if="!run.deductions.length" class="wf-empty wf-empty--inline">暂无扣分记录</p>
          <div v-else class="wf-table-wrap wf-table-wrap--tall"><table><thead><tr><th>项目</th><th>分值</th><th>依据</th><th></th></tr></thead><tbody><tr v-for="d in run.deductions" :key="d.id" :class="{ voided: d.voided }"><td>{{ d.label }}<small>{{ stamp(d.at) }}</small></td><td>{{ d.voided ? '已撤销' : `-${d.points}` }}</td><td>{{ d.reason || '—' }}</td><td><button v-if="!d.voided" type="button" class="btn btn-ghost" :disabled="busy" @click="action(async () => { run = await call(`/runs/${run.id}/deductions/${d.id}/void`, 'POST', { revision: run.revision, reason: controlReason }) }, '已撤销')">撤销</button></td></tr></tbody></table></div>
        </section>
        <section v-if="teacher && tab === 'opgrade'" class="wf-panel wf-panel--teacher-op">
          <div class="wf-metrics wf-metrics--compact"><div><small>规则分</small><strong>{{ run.calculatedScore }}</strong></div><div><small>{{ run.finalScore == null ? '当前分' : '最终分' }}</small><strong>{{ run.operationScore }}</strong></div></div>
          <fieldset :disabled="busy"><label>课堂操作说明<textarea v-model="controlReason" rows="2"></textarea></label><div class="wf-actions"><button class="btn btn-ghost" @click="action(() => control(run.classOpen ? 'close' : 'open'), '已更新')">{{ run.classOpen ? '结束课堂' : '重新开放课堂' }}</button><button v-if="run.archived" class="btn btn-ghost" @click="action(() => control('return'), '已退回')">退回让学生补充</button></div></fieldset>
          <hr>
          <fieldset :disabled="busy"><div class="wf-form wf-form--compact"><label>操作记录最终分<input v-model.number="grade.score" type="number" min="0" max="100"></label><label>调整依据<input v-model="grade.reason"></label></div><label>评语<textarea v-model="grade.comment" rows="3"></textarea></label><div class="wf-actions"><button class="btn btn-primary" @click="action(() => control('grade'), '已保存')">保存操作记录分</button><button class="btn btn-ghost" @click="action(() => control('clearGrade'), '已恢复')">按扣分明细计分</button><button class="btn btn-ghost" @click="action(aiOperationRef, '已生成')">生成参考评语</button></div><p v-if="run.operationAi" class="wf-teacher-comment">{{ run.operationAi.score }} 分 · {{ run.operationAi.comment }}</p></fieldset>
          <details class="wf-deductions-fold"><summary>操作历史</summary><p v-for="(item,i) in run.audit" :key="i">{{ stamp(item.at) }} · {{ item.action }}</p></details>
        </section>
        <section v-if="isLabPage && tab === 'operation'" class="wf-panel wf-panel--student">
          <h3>归档实验操作记录</h3>
          <ul class="wf-checklist"><li :class="{ ok: run.specimenId && run.deviceId }">试件与设备信息</li><li :class="{ ok: run.photos.length }">断口照片 {{ run.photos.length }} 张</li><li :class="{ ok: run.data }">实验数据</li></ul>
          <div class="wf-metrics wf-metrics--compact"><div><small>操作记录分</small><strong>{{ run.operationScore }}</strong></div></div>
          <button v-if="!run.archived" class="btn btn-primary" :disabled="busy || !labStepDone('capture') || !labStepDone('data')" @click="action(async () => { run = await call(`/runs/${run.id}/archive`, 'POST', { revision: run.revision }); await listRuns() }, '已归档')">确认归档</button>
          <p v-else class="wf-message success">已归档 · {{ stamp(run.archivedAt) }}</p>
          <details v-if="run.deductions.length" class="wf-deductions-fold"><summary>课堂扣分明细（{{ run.deductions.filter(d => !d.voided).length }} 项）</summary><div class="wf-table-wrap"><table><thead><tr><th>项目</th><th>分值</th><th>依据</th></tr></thead><tbody><tr v-for="d in run.deductions" :key="d.id" :class="{ voided: d.voided }"><td>{{ d.label }}</td><td>{{ d.voided ? '已撤销' : `-${d.points}` }}</td><td>{{ d.reason || '—' }}</td></tr></tbody></table></div></details>
          <div class="wf-step-nav"><button type="button" class="btn btn-ghost" @click="prevLabStep">上一步</button><button v-if="run.archived" type="button" class="btn btn-primary" @click="goStudentNav('s-report')">前往实验报告</button></div>
        </section>
        <section v-if="teacher && tab === 'report'" class="wf-panel wf-panel--teacher-op">
          <p v-if="!myReports.length" class="wf-empty wf-empty--inline">暂无学生提交</p>
          <template v-else>
            <div class="wf-report-pick"><button v-for="r in myReports" :key="r.id" type="button" class="wf-report-chip" :class="{ active: selectedReportId === r.id }" @click="selectedReportId = r.id">{{ run.members.find(m => m.sid === r.sid)?.name || r.sid }} · v{{ r.version }}</button></div>
            <article v-if="selectedReport" class="report-card report-card--solo">
              <p>{{ selectedReport.fileName }} · {{ stamp(selectedReport.submittedAt) }}</p>
              <p v-if="selectedReport.returned" class="wf-message error">已退回：{{ selectedReport.returnReason }}</p>
              <p v-if="selectedReport.templateCheck?.checked && selectedReport.templateCheck.missingSections.length" class="wf-message error">缺章节：{{ selectedReport.templateCheck.missingSections.join('、') }}</p>
              <p>观察：{{ selectedReport.observation?.features || '—' }} · {{ ({ ductile:'塑性',brittle:'脆性',uncertain:'待定' })[selectedReport.observation?.judgment] || '—' }}</p>
              <div class="wf-actions"><button class="btn btn-ghost" @click="action(() => openReport(selectedReport))">预览 / 下载</button></div>
              <fieldset :disabled="busy"><div class="wf-form"><label>最终分<input :value="reportGrades[selectedReport.id]?.score ?? selectedReport.finalScore ?? ''" type="number" min="0" max="100" @input="reportGrades[selectedReport.id] = { ...reportGrades[selectedReport.id], score: $event.target.value }"></label><label>评语 / 退回理由<input :value="reportGrades[selectedReport.id]?.comment || ''" @input="reportGrades[selectedReport.id] = { ...reportGrades[selectedReport.id], comment: $event.target.value }"></label></div><div class="wf-actions"><button class="btn btn-primary" @click="action(() => review(selectedReport,'grade'), '已保存')">保存评定</button><button class="btn btn-ghost" @click="action(() => review(selectedReport,'return'), '已退回')">退回修改</button></div></fieldset>
              <p v-if="selectedReport.finalScore != null"><strong>已保存：{{ selectedReport.finalScore }} 分</strong> {{ selectedReport.finalComment }}</p>
            </article>
          </template>
          <div v-if="reportPreview"><div class="wf-actions"><button class="btn btn-ghost" @click="reportPreview = ''">关闭预览</button></div><iframe :src="reportPreview" title="个人报告PDF预览" class="pdf-preview"></iframe></div>
        </section>
        <section v-if="isReportPage" class="wf-panel wf-panel--student">
          <div v-if="settings.template" class="wf-actions"><button class="btn btn-ghost" @click="action(() => downloadFile(settings.template.id, settings.template.name))">下载报告模板</button></div>
          <h3>断口观察</h3><fieldset :disabled="busy || reportLocked"><label>特征描述<textarea v-model="observation.features" rows="4" placeholder="描述本组断口宏观特征"></textarea></label><div class="wf-actions"><select v-model="observation.judgment" aria-label="断裂类型"><option value="ductile">塑性断裂</option><option value="brittle">脆性断裂</option><option value="uncertain">尚不能判断</option></select><button class="btn btn-ghost" @click="action(async () => { run = await call(`/runs/${run.id}/observation`, 'POST', observation) }, '已保存')">保存</button></div></fieldset><hr><h3>上传个人报告</h3><p v-if="!run.archived" class="wf-message error">请先在「现场实验」完成归档。</p><p v-if="reportLocked">已提交并锁定，联系教师退回后可修改。</p><fieldset :disabled="busy || !run.archived || reportLocked"><input type="file" accept=".doc,.docx,.pdf" @change="action(() => uploadReport($event.target.files[0]), '草稿已保存')"><div v-if="myDraft"><p>{{ myDraft.fileName }}</p><p v-if="myDraft.warning" class="wf-message error">{{ myDraft.warning }}</p><button class="btn btn-primary" @click="action(async () => { run = await call(`/runs/${run.id}/submit`, 'POST', { requestId }); requestId = newId() }, '提交成功')">正式提交</button></div></fieldset>
          <hr><h3>提交记录</h3><p v-if="!myReports.length" class="wf-empty wf-empty--inline">暂无提交</p><article v-for="r in myReports" :key="r.id" class="report-card"><h3>第{{ r.version }}版 · {{ stamp(r.submittedAt) }}</h3><p>{{ r.fileName }}</p><div class="wf-actions"><button class="btn btn-ghost" @click="action(() => openReport(r))">预览 / 下载</button></div><p v-if="r.finalScore != null">教师评分：{{ r.finalScore }} · {{ r.finalComment }}</p></article>
          <div v-if="reportPreview"><div class="wf-actions"><button class="btn btn-ghost" @click="reportPreview = ''">关闭预览</button></div><iframe :src="reportPreview" title="个人报告PDF预览" class="pdf-preview"></iframe></div>
        </section>
        <AdvancedPanel v-if="(teacher && (tab === 'assist' || tab === 'retake')) || (isLabPage && tab === 'operation')" :run="run" :runs="runs" :teacher="teacher" :tab="tab" :teacher-mode="teacher ? tab : ''" :sid="sid" @updated="run = $event" @selected="action(async () => { await listRuns(); await select($event.id) }, '临时组重做记录已建立')" />
      </main>
    </div>
  </section>
</template>

<style>
.workflow{color:#223945;max-width:1480px;margin:auto}.workflow-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;gap:16px}.workflow-heading--teacher{margin-bottom:16px}.workflow-heading--teacher h1{margin:0;font-size:26px}.wf-toolbar{display:flex;gap:8px;flex-shrink:0}.workflow h1{font-size:28px;margin:4px 0 8px}.workflow h2{font-size:23px;margin:0 0 10px}.workflow h3{font-size:18px;margin:0 0 14px}.workflow p{line-height:1.7}.workflow small,.wf-hint{color:#647980}.eyebrow{color:#087c88;font-weight:700;letter-spacing:2px}.workflow-layout{display:grid;grid-template-columns:265px minmax(0,1fr);gap:24px}.workflow--student{max-width:960px;margin:0 auto}.workflow-heading--student h1{margin:0;font-size:26px}.workflow-layout--solo{grid-template-columns:1fr;max-width:920px;margin:0 auto}.wf-main--student .wf-student-head{background:#fff;padding:20px 22px;border:1px solid #e1e9ed;border-radius:14px 14px 0 0;border-bottom:0;display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px}.wf-main--student .wf-student-head h2{margin:0;font-size:20px}.wf-student-head-main{display:grid;gap:6px;min-width:0;flex:1}.wf-main--student .wf-panel--student{border-radius:0 0 14px 14px;border-top:0;margin-top:0}.wf-stepper{display:flex;background:#fff;border:1px solid #e1e9ed;border-top:0;padding:12px 16px;gap:8px}.wf-step{flex:1;display:flex;align-items:center;justify-content:center;gap:8px;padding:12px 8px;border:1px solid #e1e9ed;border-radius:10px;background:#f8fafb;cursor:pointer;font:inherit;color:#52666f}.wf-step.active{border-color:#087c88;background:#e8f4f5;color:#087c88;font-weight:600}.wf-step.done .wf-step-num{background:#087c88;color:#fff}.wf-step-num{width:26px;height:26px;border-radius:50%;background:#dce6e9;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:700;flex-shrink:0}.wf-step-title{font-size:13px}.wf-step-nav{display:flex;justify-content:flex-end;gap:12px;margin-top:24px;padding-top:20px;border-top:1px solid #e5edef}.wf-checklist{list-style:none;padding:0;margin:0 0 20px;display:grid;gap:10px}.wf-checklist li{padding:10px 14px;border-radius:8px;background:#f5f8f9;color:#70828a}.wf-checklist li.ok{background:#e9f6ee;color:#24623c}.wf-checklist li.ok::before{content:'✓ '}.wf-deductions-fold{margin-top:16px}.wf-sidebar{display:flex;flex-direction:column;gap:10px}.run-card{text-align:left;border:1px solid #dce6e9;background:white;border-radius:12px;padding:16px;display:grid;gap:8px;cursor:pointer;color:inherit}.run-card.selected{border-color:#078592;box-shadow:0 0 0 2px #d7eef0;background:#f2fafb}.run-card span,.run-card small{font-size:12px}.run-card b{color:#087c88}.wf-main{min-width:0}.wf-main--teacher .wf-panel{margin-top:0}.wf-teacher-head{background:#fff;padding:20px 22px;border:1px solid #e1e9ed;border-radius:14px 14px 0 0;border-bottom:0;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-start;gap:12px}.wf-teacher-head h2{margin:0;font-size:20px}.wf-teacher-head-main{display:grid;gap:6px;min-width:0;flex:1}.wf-run-select{font:inherit;font-size:16px;font-weight:600;border:1px solid #ccdadd;border-radius:8px;padding:10px 12px;max-width:100%;color:#223945;background:#fff}.wf-teacher-meta{font-size:14px;color:#52666f}.wf-teacher-tags{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.tag.ok{background:#e9f6ee;color:#24623c}.tag-accent{background:#e8f4f5;color:#087c88;font-weight:700}.wf-tabs--teacher{padding:0 4px 0;background:#fff;border:1px solid #e1e9ed;border-top:0;border-bottom:0}.wf-main--teacher .wf-panel{border-radius:0 0 14px 14px;border-top:0}.wf-op-layout{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(0,.95fr);gap:28px;align-items:start}.wf-panel--teacher-op{padding-top:22px}.wf-metrics--compact{margin:0 0 20px}.wf-form--compact{margin:12px 0}.wf-empty--page{margin-top:40px}.wf-empty--inline{padding:16px;margin:8px 0;text-align:left;background:#f5f8f9;border-radius:8px;color:#70828a}.wf-teacher-comment{margin-top:12px;padding:12px;background:#f5f8f9;border-radius:8px;font-size:14px}.wf-run-head{background:#fff;padding:24px;border:1px solid #e1e9ed;border-radius:14px;display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.wf-run-head small{word-break:break-all}.wf-tabs{display:flex;gap:4px;padding:16px 0;overflow:auto}.wf-tabs button{white-space:nowrap;background:transparent;border:0;padding:12px 20px;border-radius:8px;color:#52666f;font-weight:600;cursor:pointer}.wf-tabs button.active{background:#087c88;color:white}.wf-panel{background:#fff;border:1px solid #e1e9ed;padding:26px;border-radius:14px}.workflow fieldset{border:0;margin:0;padding:0;min-width:0}.workflow input,.workflow select,.workflow textarea{border:1px solid #ccdadd;background:#fff;border-radius:7px;padding:10px;color:#223945;font:inherit;max-width:100%}.workflow textarea{width:100%;resize:vertical}.workflow input:focus,.workflow select:focus,.workflow textarea:focus{outline:2px solid #90cdd2;outline-offset:1px}.wf-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:18px 0}.wf-form label{display:grid;gap:8px;font-size:13px}.wf-actions{display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin:14px 0}.wf-actions label.btn input{max-width:180px;font-size:12px;border:0;padding:0 0 0 8px}.workflow hr{border:0;border-top:1px solid #e5edef;margin:28px 0}.photo-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:16px}.photo-grid figure{margin:0;border:1px solid #e0e9ec;border-radius:12px;overflow:hidden;padding:12px}.photo-grid img{width:100%;height:180px;object-fit:contain;background:#f3f6f7;border-radius:6px}.photo-grid figcaption{font-size:13px;margin:8px 0}.photo-grid small{display:block}.photo-grid button{border:0;background:transparent;color:#087c88;cursor:pointer}.camera video{width:100%;max-height:450px;background:#172832;border-radius:12px}.wf-message{padding:12px 16px;border-radius:8px;white-space:pre-wrap}.wf-message.error{background:#fff0ee;color:#a33728}.wf-message.success{background:#e9f6ee;color:#24623c}.wf-empty{padding:30px;background:#f5f8f9;border-radius:10px;text-align:center;color:#70828a}.wf-table-wrap{overflow:auto;max-height:300px;margin:16px 0}.workflow table{width:100%;border-collapse:collapse;font-size:13px}.workflow th,.workflow td{border:1px solid #dce6e9;padding:10px;text-align:left;white-space:pre-wrap;word-break:break-word}.workflow th{background:#f0f6f7}.wf-data{margin-top:28px}.wf-data svg{width:100%;font-size:12px}.wf-metrics{display:flex;gap:18px;margin:20px 0;flex-wrap:wrap}.wf-metrics>div{display:grid;gap:8px;min-width:150px;padding:18px;background:#f1f8f8;border-radius:10px}.wf-metrics strong{font-size:25px;color:#087c88}.workflow button:disabled{opacity:.55;cursor:not-allowed}.workflow input[type=file]{max-width:100%}@media(max-width:900px){.workflow-layout{grid-template-columns:1fr}.wf-op-layout{grid-template-columns:1fr}.wf-sidebar{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.wf-sidebar h3{grid-column:1/-1}.wf-run-head{flex-direction:column}.wf-teacher-head{flex-direction:column}}@media(max-width:520px){.wf-form{grid-template-columns:1fr}.wf-panel{padding:18px}.workflow-heading{align-items:flex-start;gap:12px}.workflow h1{font-size:23px}.wf-sidebar{grid-template-columns:1fr}.wf-tabs button{padding:10px 14px}}
</style>
<style>
.wf-stepper--teacher{padding:12px 16px;flex-wrap:wrap;border:1px solid #e1e9ed;border-top:0;background:#fff}.wf-stepper--teacher .wf-step{flex:1 1 auto;min-width:88px;font-size:13px;padding:10px 12px}.wf-report-pick{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px}.wf-report-chip{border:1px solid #dce6e9;background:#f8fafb;border-radius:999px;padding:8px 16px;cursor:pointer;font:inherit;color:#52666f}.wf-report-chip.active{background:#087c88;border-color:#087c88;color:#fff}.report-card--solo{margin:0}.wf-table-wrap--tall{max-height:none}.wf-checks{display:grid;gap:12px;margin:20px 0}.workflow td small{display:block;margin-top:6px}.workflow textarea{margin:8px 0 14px}.workflow .voided{color:#89969d}.report-card{border:1px solid #dce6e9;padding:22px;border-radius:12px;margin:18px 0}.report-card small{word-break:break-all}.extracted{white-space:pre-wrap;line-height:1.8;max-height:320px;overflow:auto;background:#f6f8f9;padding:16px}.pdf-preview{width:100%;height:650px;border:1px solid #ccdadd;border-radius:8px}.workflow summary{cursor:pointer;margin:12px 0;color:#087c88}.workflow fieldset:disabled{opacity:.7}
.wf-teacher-toolbar{max-width:920px;margin:0 auto 16px;display:flex;justify-content:flex-end;align-items:center;gap:8px}.wf-config-trigger{display:inline-flex;align-items:center;gap:8px}.wf-config-trigger small{padding:2px 7px;border-radius:99px;background:#e9f6ee;color:#24623c;font-size:12px}.wf-config-trigger.is-attention{border-color:#e2b5a9;color:#a33728}.wf-config-trigger.is-attention small{background:#fff0ee;color:#a33728}.wf-config-trigger__icon{font-size:16px}.wf-config-mask{position:fixed;inset:0;z-index:30;background:rgba(31,48,57,.24);display:flex;justify-content:flex-end}.wf-config-drawer{width:min(580px,100vw);height:100%;box-sizing:border-box;overflow-y:auto;background:#fff;box-shadow:-12px 0 32px rgba(31,48,57,.16);padding:28px}.wf-config-drawer__head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding-bottom:20px;border-bottom:1px solid #e5edef;margin-bottom:22px}.wf-config-drawer__head .eyebrow{font-size:12px;margin:0 0 4px}.wf-config-drawer__head h2{font-size:24px;margin:0 0 8px}.wf-config-drawer__head p:not(.eyebrow){margin:0;color:#647980;font-size:14px}.wf-config-close{min-width:38px;padding:4px 10px;font-size:26px;line-height:1}.wf-deduction-config{display:grid;gap:10px;margin:0 0 14px}.wf-deduction-config__labels,.wf-deduction-config__row{display:grid;grid-template-columns:minmax(0,1fr) 112px 72px;gap:10px;align-items:center}.wf-deduction-config__labels{color:#647980;font-size:12px;padding:0 2px}.wf-deduction-config__row{padding:10px;border:1px solid #e1e9ed;border-radius:10px;background:#fbfcfc}.wf-deduction-config__row input{width:100%;box-sizing:border-box}.wf-deduction-config__row label{position:relative;display:block}.wf-deduction-config__row label input{padding-right:30px}.wf-deduction-config__row label span{position:absolute;right:11px;top:10px;color:#647980;font-size:13px;pointer-events:none}.wf-deduction-config__row .btn{padding:8px 6px;font-size:13px}.wf-config-confirm{display:flex;gap:8px;align-items:flex-start;margin:18px 0 6px;font-size:14px;line-height:1.5}.wf-config-confirm input{margin-top:3px}.wf-config-actions{display:flex;gap:10px;margin-top:20px}.wf-config-advanced{margin-top:28px;padding-top:20px;border-top:1px solid #e5edef}.wf-config-advanced summary{font-weight:600;color:#52666f}.wf-config-advanced .wf-form{margin-bottom:16px}.wf-deduction-setup{display:flex;align-items:center;justify-content:space-between;gap:16px}.wf-deduction-setup div{display:grid;gap:2px}.wf-deduction-setup span{font-size:13px}.wf-deduction-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.wf-deduction-heading h3{margin-bottom:4px}.wf-deduction-heading p{margin:0;color:#647980;font-size:13px}.wf-text-button{border:0;background:transparent;padding:2px 0;color:#087c88;cursor:pointer;font:inherit;font-size:13px;white-space:nowrap}@media(max-width:620px){.wf-teacher-toolbar{margin-bottom:12px}.wf-config-drawer{padding:20px 16px}.wf-deduction-config__labels{display:none}.wf-deduction-config__row{grid-template-columns:minmax(0,1fr) 86px 58px;padding:8px;gap:7px}.wf-deduction-config__row .btn{font-size:12px;padding:8px 3px}.wf-deduction-setup{align-items:flex-start;flex-direction:column}.wf-deduction-heading{flex-direction:column;gap:8px}}
</style>
