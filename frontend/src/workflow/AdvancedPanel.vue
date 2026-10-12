<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { call } from './api.js'
import ToastNotice from './ToastNotice.vue'
const props = defineProps({ run: Object, runs: Array, teacher: Boolean, tab: String, teacherMode: String, sid: String })
const emit = defineEmits(['updated', 'selected'])
const busy = ref(false), error = ref(''), notice = ref('')
const retake = reactive({ name: '', reason: '', members: [] })
const reason = ref('')
const students = ref([]), selectedClass = ref(''), query = ref(''), loadingStudents = ref(false), rosterError = ref('')
const classes = computed(() => [...new Set(students.value.map(s => s.cls))])
const eligible = computed(() => students.value)
const filteredStudents = computed(() => eligible.value.filter(s => `${s.name} ${s.sid}`.toLowerCase().includes(query.value.trim().toLowerCase())))
watch(selectedClass, () => { retake.members = []; query.value = '' })
const stamp = t => t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '—'
watch(() => props.run.id, async id => {
  Object.assign(retake, { name: '课堂重做临时组', reason: '', members: [] })
  reason.value = ''; error.value = ''; notice.value = ''
  students.value = []; selectedClass.value = ''; rosterError.value = ''; loadingStudents.value = true
  try {
    const result = await call(`/runs/${id}/retake-students`)
    if (props.run.id !== id) return
    students.value = result; selectedClass.value = result[0]?.cls || ''
  } catch (e) { if (props.run.id === id) rosterError.value = e.message }
  finally { if (props.run.id === id) loadingStudents.value = false }
}, { immediate: true })
async function send(path, body = {}, selected = false) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  const originalId = props.run.id
  try {
    const result = await call(`/runs/${originalId}/${path}`, 'POST', { ...body, revision: props.run.revision })
    if (props.run.id !== originalId) return
    emit(selected ? 'selected' : 'updated', result)
    notice.value = '已保存'
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
function clearToast() { if (error.value) error.value = ''; else if (rosterError.value) rosterError.value = ''; else notice.value = '' }
</script>
<template>
  <section class="wf-panel advanced-panel">
    <ToastNotice :message="error || rosterError || notice" :type="error || rosterError ? 'error' : 'success'" @close="clearToast" />
    <template v-if="teacher && teacherMode === 'retake'">
      <p v-if="run.retakeRequest">{{ run.retakeRequest.status === 'pending' ? '待处理' : '已安排' }} · {{ run.retakeRequest.reason }}</p>
      <h3>安排学生重做</h3>
      <fieldset :disabled="busy || loadingStudents">
        <label>临时组名称<input v-model="retake.name"></label>
        <p class="wf-hint">共 {{ eligible.length }} 人，已选 {{ retake.members.length }} 人。</p>
        <label>搜索学生<input v-model="query" type="search" placeholder="输入姓名或学号"></label>
        <p v-if="loadingStudents">正在加载班级名单…</p>
        <p v-else-if="!rosterError && !classes.length">当前实验成员尚未填写班级，请先在学生名单中补全班级信息。</p>
        <div class="wf-actions"><button type="button" class="btn btn-ghost" :disabled="!eligible.length" @click="retake.members = eligible.map(s => s.sid)">全选本班</button><button type="button" class="btn btn-ghost" @click="retake.members = []">清空选择</button></div>
        <div class="wf-checks retake-students"><label v-for="m in filteredStudents" :key="m.sid"><input v-model="retake.members" type="checkbox" :value="m.sid"> {{ m.name }}（{{ m.sid }}）</label></div>
        <p v-if="eligible.length && !filteredStudents.length">没有匹配的学生</p>
        <label>重做安排依据<textarea v-model="retake.reason" rows="2"></textarea></label>
        <button class="btn btn-primary" :disabled="!retake.members.length || !retake.name.trim() || !retake.reason.trim()" @click="send('retake', retake, true)">建立新的实验记录</button>
      </fieldset>
    </template>
    <template v-else-if="teacher && teacherMode === 'assist'">
      <div class="wf-actions"><button class="btn btn-primary" :disabled="busy || !run.reports.length" @click="send('similarity')">检查组内报告相似度</button></div>
      <p v-if="!run.reports.length" class="wf-empty wf-empty--inline">暂无报告可分析</p>
      <article v-for="r in run.reports" :key="r.id" class="report-card report-card--assist">
        <h3>{{ run.members.find(m => m.sid === r.sid)?.name || r.sid }} · v{{ r.version }}</h3>
        <button class="btn btn-ghost" :disabled="busy || r.parseStatus !== 'ready'" @click="send(`ai/${r.id}`)">生成 AI 评阅建议</button>
        <p v-if="r.parseStatus !== 'ready'">需人工查看原件</p>
        <div v-if="r.ai"><strong>{{ r.ai.score == null ? '待复核' : `${r.ai.score} 分` }}</strong><p class="ai-comment">{{ r.ai.comment }}</p><small>{{ stamp(r.aiAt) }}</small></div>
        <div v-if="r.foreignDataEvidence?.length" class="wf-message error"><p v-for="e in r.foreignDataEvidence">{{ e.groupName }} · {{ e.note }}</p></div>
        <div v-if="r.similarity"><strong>相似度 {{ r.similarity.maxPercent }}%</strong><p>{{ r.similarity.flagged ? `建议扣 ${r.similarity.suggestedDeduction} 分（需复核）` : '未标记雷同' }}</p></div>
      </article>
    </template>
    <template v-else-if="!teacher && tab === 'operation'">
      <h3>申请重做</h3>
      <fieldset :disabled="busy"><label>重做原因<textarea v-model="reason" rows="2"></textarea></label><button class="btn btn-ghost" @click="send('retake-request', { reason })">提交申请</button></fieldset>
    </template>
  </section>
</template>
<style>
.retake-students{max-height:360px;overflow:auto;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));padding:12px;background:#f5f8f9;border-radius:8px}.advanced-panel fieldset>label{margin-bottom:16px}.advanced-panel fieldset>label>input,.advanced-panel fieldset>label>select{display:block;margin-top:8px}
.advanced-panel{margin-top:0;border-top:0;border-radius:0 0 14px 14px}.ai-comment{white-space:pre-wrap}.advanced-panel label{display:block}.report-card--assist{margin-top:16px;padding:18px}.wf-empty--inline{padding:16px;margin:8px 0;text-align:left;background:#f5f8f9;border-radius:8px;color:#70828a}
</style>
