<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { call, fileBlob } from './api.js'
import OperationRecordDoc from './OperationRecordDoc.vue'

const props = defineProps({ runs: { type: Array, default: () => [] }, loading: Boolean })
const emit = defineEmits(['refresh'])
const experiment = ref(''), group = ref(''), status = ref(''), query = ref('')
const active = ref(null), detail = ref(null), loadingDetail = ref(false), error = ref('')
const images = reactive({})
let generation = 0
const experiments = computed(() => [...new Map(props.runs.map(row => [row.expId, row.expName])).entries()])
const groups = computed(() => [...new Set(props.runs.filter(row => !experiment.value || row.expId === experiment.value).map(row => row.groupName))].sort((a, b) => String(a).localeCompare(String(b), 'zh-CN', { numeric: true })))
function recordStatus(row) {
  if (row.retakeRequest?.status === 'pending') return '重做待处理'
  if (row.retakeRequest?.status === 'arranged') return '已安排重做'
  if (!row.collectionComplete) return '采集中'
  if (row.classOpen) return '待确认成绩'
  return row.archived ? '已提交' : '待学生提交'
}
const statuses = ['采集中', '待确认成绩', '待学生提交', '已提交', '重做待处理', '已安排重做']
const filtered = computed(() => {
  const search = query.value.trim().toLowerCase()
  return props.runs.filter(row => (!experiment.value || row.expId === experiment.value)
    && (!group.value || row.groupName === group.value)
    && (!status.value || recordStatus(row) === status.value)
    && (!search || [row.expName, row.groupName, ...(row.members || []).flatMap(m => [m.name, m.sid, m.cls])].join(' ').toLowerCase().includes(search)))
})
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
  <section class="wf-panel experiment-records">
    <template v-if="!active">
      <header class="records-heading"><h2>实验记录</h2><button type="button" class="btn btn-ghost" :disabled="loading" @click="emit('refresh')">{{ loading ? '正在加载…' : '刷新' }}</button></header>
      <div class="records-filters">
        <label>实验项目<select v-model="experiment" @change="group = ''"><option value="">全部项目</option><option v-for="[id, name] in experiments" :key="id" :value="id">{{ name }}</option></select></label>
        <label>实验小组<select v-model="group"><option value="">全部小组</option><option v-for="name in groups" :key="name" :value="name">{{ name }}</option></select></label>
        <label>记录状态<select v-model="status"><option value="">全部状态</option><option v-for="name in statuses" :key="name">{{ name }}</option></select></label>
        <label class="records-search">搜索<input v-model="query" type="search" placeholder="姓名、学号或班级"></label>
      </div>
      <p class="records-count">共 {{ filtered.length }} 条记录</p>
      <div class="records-table-wrap" tabindex="0" aria-label="实验记录表格，可横向滚动">
        <table aria-label="实验记录列表">
          <thead><tr><th scope="col">实验项目</th><th scope="col">实验小组</th><th scope="col">组员</th><th scope="col">课次时间</th><th scope="col">子实验</th><th scope="col" class="records-number">操作分</th><th scope="col">记录状态</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in filtered" :key="row.id">
              <td>{{ row.expName }}</td><td>{{ row.groupName }}<small>第 {{ row.attempt }} 次</small></td>
              <td class="records-members">{{ (row.members || []).map(member => member.name).join('、') || '—' }}<small>{{ (row.members || []).map(member => member.sid).join('、') }}</small></td>
              <td>{{ row.timeText || stamp(row.createdAt) }}</td><td>{{ row.completedTrials || 0 }} / {{ row.trialTotal || 1 }}</td>
              <td class="records-number">{{ row.operationScore ?? '—' }}</td><td>{{ recordStatus(row) }}</td>
              <td><button type="button" class="records-detail" :aria-label="`查看${row.groupName}第${row.attempt}次实验记录详情`" @click="open(row)">查看详情</button></td>
            </tr>
            <tr v-if="!filtered.length"><td colspan="8" class="records-empty">{{ loading ? '正在加载实验记录…' : runs.length ? '没有符合条件的实验记录' : '暂无实验记录' }}</td></tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else>
      <header class="records-detail-heading"><button type="button" class="btn btn-ghost" @click="back">返回列表</button><h2>实验记录详情</h2></header>
      <p v-if="loadingDetail" class="records-empty">正在加载实验记录…</p>
      <div v-else-if="error" class="records-error" role="alert"><p>{{ error }}</p><button type="button" class="btn btn-ghost" @click="open(active)">重新加载</button></div>
      <OperationRecordDoc v-else-if="detail" :run="detail" :points="detail.data?.points || []" :images="images" :deductions="(detail.deductions || []).filter(item => !item.voided)" />
    </template>
  </section>
</template>

<style scoped>
.experiment-records{padding:24px 28px}.records-heading,.records-detail-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:24px}.records-heading h2,.records-detail-heading h2{margin:0;font-size:21px}.records-detail-heading{justify-content:flex-start}.records-detail-heading h2{font-size:18px}.records-filters{display:grid;grid-template-columns:1.25fr 1fr 1fr 1.4fr;gap:16px}.records-filters label{display:grid;gap:7px;font-size:13px;color:#52666f}.records-filters input,.records-filters select{width:100%;min-width:0;box-sizing:border-box;height:40px;font:inherit;color:#243b45;background:#fff;border:1px solid #cddce1;border-radius:6px;padding:8px 10px}.records-count{margin:22px 0 12px;color:#52666f;font-size:13px}.records-table-wrap{width:100%;overflow-x:auto}.records-table-wrap table{width:100%;min-width:960px;border-collapse:collapse;font-size:14px}.records-table-wrap th,.records-table-wrap td{text-align:left;vertical-align:top;padding:15px 12px;border:0;border-bottom:1px solid #e1e9ed;background:transparent;line-height:1.6}.records-table-wrap th{font-weight:500;color:#52666f;white-space:nowrap}.records-table-wrap td:first-child{min-width:128px}.records-table-wrap th:first-child,.records-table-wrap td:first-child{padding-left:0}.records-table-wrap tr:hover td{background:#fafcfc}.records-table-wrap .records-number{text-align:right;font-variant-numeric:tabular-nums}.records-members{min-width:130px}.records-members span{display:block;margin-bottom:6px}.records-members span:last-child{margin-bottom:0}.records-table-wrap small{display:block;color:#637780;font-size:12px}.records-detail{border:0;background:transparent;color:#245b65;font:inherit;padding:0;white-space:nowrap;cursor:pointer;text-decoration:underline;text-underline-offset:4px}.records-detail:focus-visible{outline:2px solid #245b65;outline-offset:4px}.records-empty{text-align:center!important;padding:40px 16px!important;color:#637780}.records-error{color:#9a3e2c}.experiment-records .btn{background-image:none;box-shadow:none}.records-detail-heading+.wf-op-doc{max-width:none}@media(max-width:900px){.records-filters{grid-template-columns:1fr 1fr}}@media(max-width:520px){.experiment-records{padding:20px 16px}.records-heading{margin-bottom:20px}.records-filters{gap:12px}.records-search{grid-column:1/-1}.records-detail-heading{flex-wrap:wrap}.records-table-wrap th,.records-table-wrap td{padding:12px 10px}}
</style>
<style scoped>
.records-members small{overflow-wrap:anywhere}
@media(max-width:900px){.records-table-wrap th:last-child,.records-table-wrap td:last-child{position:sticky;right:0;background:#fff;border-left:1px solid #e1e9ed}}
</style>
