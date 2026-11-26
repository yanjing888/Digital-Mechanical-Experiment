<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { call, fileBlob } from './api.js'
import OperationRecordDoc from './OperationRecordDoc.vue'
import TablePagination from './TablePagination.vue'
import TablePageSize from './TablePageSize.vue'
import { usePagination } from './pagination.js'
import TableToolbar from './TableToolbar.vue'

const props = defineProps({ runs: { type: Array, default: () => [] }, students: { type: Array, default: () => [] }, loading: Boolean })
const emit = defineEmits(['refresh'])
const experiment = ref(''), selectedMajor = ref(''), selectedClass = ref(''), status = ref(''), query = ref('')
const active = ref(null), detail = ref(null), loadingDetail = ref(false), error = ref('')
const images = reactive({})
let generation = 0
const experiments = computed(() => [...new Map(props.runs.map(row => [row.expId, row.expName])).entries()])
const studentProfiles = computed(() => new Map(props.students.map(student => [student.sid, student])))
const profileText = value => { const text = String(value || '').trim(); return text === '—' ? '' : text }
function profilesFor(row) {
  return (row.members || []).map(member => {
    const current = studentProfiles.value.get(member.sid) || {}
    return { major: profileText(member.major) || profileText(current.major), cls: profileText(member.cls) || profileText(current.cls) }
  })
}
const majorName = profile => profile.major || '未填写专业'
const rowMajors = row => [...new Set(profilesFor(row).map(profile => profile.major).filter(Boolean))]
const rowClasses = row => [...new Set(profilesFor(row).map(profile => profile.cls).filter(Boolean))]
const experimentRows = computed(() => props.runs.filter(row => !experiment.value || row.expId === experiment.value))
const majors = computed(() => [...new Set(experimentRows.value.flatMap(row => {
  const profiles = profilesFor(row)
  return profiles.length ? profiles.map(majorName) : ['未填写专业']
}))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const classes = computed(() => [...new Set(experimentRows.value.flatMap(row => profilesFor(row)
  .filter(profile => !selectedMajor.value || majorName(profile) === selectedMajor.value)
  .map(profile => profile.cls).filter(Boolean)))].sort((a, b) => a.localeCompare(b, 'zh-CN', { numeric: true })))
watch(experiment, () => { selectedMajor.value = ''; selectedClass.value = '' })
watch(selectedMajor, () => { selectedClass.value = '' })
function matchesProfile(row) {
  if (!selectedMajor.value && !selectedClass.value) return true
  const profiles = profilesFor(row)
  return (profiles.length ? profiles : [{ major: '', cls: '' }]).some(profile =>
    (!selectedMajor.value || majorName(profile) === selectedMajor.value) && (!selectedClass.value || profile.cls === selectedClass.value))
}
function recordStatus(row) {
  if (row.retakeRequest?.status === 'pending') return '重做待处理'
  if (row.retakeRequest?.status === 'arranged') return '已安排重做'
  if (!row.collectionComplete) return '采集中'
  if (row.classOpen && row.teacherScoreConfirmationRequired !== false) return '待确认成绩'
  return row.archived ? '已提交' : '待学生提交'
}
const statuses = ['采集中', '待确认成绩', '待学生提交', '已提交', '重做待处理', '已安排重做']
const statusStyles = { '采集中': 'collecting', '待确认成绩': 'grading', '待学生提交': 'waiting', '已提交': 'submitted', '重做待处理': 'retake', '已安排重做': 'arranged' }
const filtered = computed(() => {
  const search = query.value.trim().toLowerCase()
  return props.runs.filter(row => (!experiment.value || row.expId === experiment.value)
    && matchesProfile(row)
    && (!status.value || recordStatus(row) === status.value)
    && (!search || [row.expName, row.groupName, ...rowMajors(row), ...rowClasses(row), ...(row.members || []).flatMap(m => [m.name, m.sid])].join(' ').toLowerCase().includes(search)))
})
const { page, pageSize, pagedRows } = usePagination(filtered, [experiment, selectedMajor, selectedClass, status, query])
const hasFilters = computed(() => !!(experiment.value || selectedMajor.value || selectedClass.value || status.value || query.value.trim()))
function resetFilters() { experiment.value = ''; selectedMajor.value = ''; selectedClass.value = ''; status.value = ''; query.value = '' }
const stamp = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
function clearImages() { Object.keys(images).forEach(id => { URL.revokeObjectURL(images[id]); delete images[id] }) }
function back() { generation++; active.value = null; detail.value = null; loadingDetail.value = false; error.value = ''; clearImages() }
async function open(row) {
  const current = ++generation
  active.value = row; detail.value = null; error.value = ''; loadingDetail.value = true
  clearImages()
  try {
    const result = await call(`/runs/${encodeURIComponent(row.id)}`)
    const photos = result.trialItems ? result.trialItems.flatMap(item => item.photos || []) : result.photos || []
    const blobs = await Promise.all([...new Set(photos.map(photo => photo.id))].map(async id => [id, await fileBlob(id)]))
    if (current !== generation) return
    blobs.forEach(([id, blob]) => { images[id] = URL.createObjectURL(blob) })
    detail.value = result
  } catch (err) {
    if (current === generation) { clearImages(); error.value = err.message || '无法加载实验记录，请重试' }
  } finally { if (current === generation) loadingDetail.value = false }
}
onUnmounted(() => { generation++; clearImages() })
</script>

<template>
  <section class="wf-panel experiment-records" :aria-label="active ? '实验记录详情' : '实验记录'">
    <template v-if="!active">
      <TableToolbar label="筛选实验记录" refresh-label="刷新实验记录" :loading="loading" :filtered="hasFilters" @refresh="emit('refresh')" @reset="resetFilters">
        <label class="table-toolbar__search">搜索<input v-model="query" type="search" aria-label="搜索实验记录" placeholder="姓名、学号或班级"></label>
        <label>实验项目<select v-model="experiment" aria-label="筛选实验项目"><option value="">全部项目</option><option v-for="[id, name] in experiments" :key="id" :value="id">{{ name }}</option></select></label>
        <label>专业<select v-model="selectedMajor" aria-label="筛选专业"><option value="">全部专业</option><option v-for="name in majors" :key="name">{{ name }}</option></select></label>
        <label>班级<select v-model="selectedClass" aria-label="筛选班级"><option value="">全部班级</option><option v-for="name in classes" :key="name">{{ name }}</option></select></label>
        <label>记录状态<select v-model="status" aria-label="筛选记录状态"><option value="">全部状态</option><option v-for="name in statuses" :key="name">{{ name }}</option></select></label>
      </TableToolbar>
      <div class="records-table-wrap" tabindex="0" aria-label="实验记录表格，可横向滚动">
        <table aria-label="实验记录列表">
          <thead><tr><th scope="col">实验项目</th><th scope="col">实验小组</th><th scope="col">组员</th><th scope="col">专业</th><th scope="col">班级</th><th scope="col">课次时间</th><th scope="col">子实验</th><th scope="col" class="records-number">操作分</th><th scope="col">记录状态</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedRows" :key="row.id">
              <td>{{ row.expName }}</td><td>{{ row.groupName }}<small>第 {{ row.attempt }} 次</small></td>
              <td class="records-members">{{ (row.members || []).map(member => member.name).join('、') || '—' }}<small>{{ (row.members || []).map(member => member.sid).join('、') }}</small></td>
              <td><template v-if="rowMajors(row).length"><span v-for="major in rowMajors(row)" :key="major" class="records-profile-value">{{ major }}</span></template><span v-else>—</span></td>
              <td>{{ rowClasses(row).join('、') || '—' }}</td>
              <td>{{ row.timeText || stamp(row.createdAt) }}</td><td>{{ row.completedTrials || 0 }} / {{ row.trialTotal || 1 }}</td>
              <td class="records-number">{{ row.operationScore ?? '—' }}</td><td class="records-state-cell"><span class="records-status" :class="`records-status--${statusStyles[recordStatus(row)]}`">{{ recordStatus(row) }}</span></td>
              <td><button type="button" class="btn records-detail" :aria-label="`查看${row.groupName}第${row.attempt}次实验记录详情`" @click="open(row)">查看详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="!filtered.length" class="records-empty" role="status">{{ loading ? '正在加载实验记录…' : runs.length ? '没有符合条件的实验记录' : '暂无实验记录' }}</p>
      <TablePagination v-model:page="page" class="records-pagination" :total="filtered.length" :page-size="pageSize" :disabled="loading" label="实验记录分页"><template #page-size><TablePageSize v-model="pageSize" compact label="实验记录每页条数" :disabled="loading" /></template></TablePagination>
    </template>
    <template v-else>
      <header class="records-detail-heading"><button type="button" class="btn btn-ghost" @click="back">返回列表</button></header>
      <p v-if="loadingDetail" class="records-empty">正在加载实验记录…</p>
      <div v-else-if="error" class="records-error" role="alert"><p>{{ error }}</p><button type="button" class="btn btn-ghost" @click="open(active)">重新加载</button></div>
      <OperationRecordDoc v-else-if="detail" :run="detail" :points="detail.data?.points || []" :images="images" :deductions="(detail.deductions || []).filter(item => !item.voided)" />
    </template>
  </section>
</template>

<style scoped>
.experiment-records{padding:24px 28px}
.records-detail-heading{display:flex;align-items:center;justify-content:flex-start;gap:16px;margin-bottom:24px}
.records-detail-heading h2{margin:0;font-size:18px}
.records-pagination { margin-top: 16px; padding-inline: 0; padding-bottom: 0; }
.records-table-wrap{width:100%;overflow-x:auto;border:1px solid #c9d6df;border-radius:6px}
.records-table-wrap table{width:100%;min-width:1240px;border-collapse:collapse;font-size:14px}
.records-profile-value{display:block}
.records-table-wrap th,.records-table-wrap td{text-align:left;vertical-align:top;padding:13px 12px;border:1px solid #d5dfe6;background:#fff;line-height:1.6}
.records-table-wrap th{background:#edf2f6;color:#2c4351;font-weight:600;white-space:nowrap}
.records-table-wrap tr:hover td{background:#f5f9fc}
.records-table-wrap .records-number{text-align:right;font-variant-numeric:tabular-nums}
.records-table-wrap th:last-child,.records-table-wrap td:last-child{position:sticky;right:0;background:#fff;border-left:1px solid #c9d6df;text-align:center}
.records-table-wrap th:last-child{background:#edf2f6}
.records-members{min-width:165px}
.records-table-wrap small{display:block;color:#637780;font-size:12px}.records-members small{overflow-wrap:anywhere}
.records-detail{display:inline-flex;align-items:center;justify-content:center;min-height:34px;box-sizing:border-box;padding:6px 12px;border:1px solid #9cb4bf;border-radius:6px;background:#fff;color:#245b65;font:inherit;font-size:13px;font-weight:500;white-space:nowrap;cursor:pointer;text-decoration:none;line-height:1.5}
.records-detail:hover{background:#eaf2f5;border-color:#245b65}
.records-detail:focus-visible{outline:2px solid #245b65;outline-offset:4px}
.records-state-cell{white-space:nowrap}
.records-status{display:inline-block;border-radius:4px;padding:4px 8px;font-size:12px;font-weight:600;line-height:1.6}
.records-status--collecting{color:#24578a;background:#eaf2ff}
.records-status--grading{color:#80510b;background:#fff3d9}
.records-status--waiting{color:#635087;background:#f1ecfb}
.records-status--submitted{color:#25623f;background:#e8f4ec}
.records-status--retake{color:#984520;background:#fff0e6}
.records-status--arranged{color:#526170;background:#edf0f4}
.records-empty{text-align:center;padding:32px 16px;color:#637780}
.records-error{color:#9a3e2c}
.experiment-records .btn{background-image:none;box-shadow:none}
.records-detail-heading+.wf-op-doc{max-width:none}
@media(max-width:520px){.experiment-records{padding:20px 16px}.records-detail-heading{flex-wrap:wrap}.records-table-wrap th,.records-table-wrap td{padding:12px 10px}}
</style>
