<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { call } from './api.js'
const props = defineProps({ run: Object, runs: Array, teacher: Boolean, tab: String, teacherMode: String, sid: String })
const emit = defineEmits(['updated', 'selected'])
const busy = ref(false), error = ref(''), notice = ref('')
const retake = reactive({ name: '', reason: '', members: [] })
const reason = ref('')
const eligible = computed(() => [...new Map(props.runs.filter(r => r.taskId === props.run.taskId).flatMap(r => r.members).map(m => [m.sid, m])).values()])
const stamp = t => t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '—'
watch(() => props.run.id, () => {
  Object.assign(retake, { name: props.run.groupName + '临时组', reason: '', members: props.run.members.map(m => m.sid) })
  reason.value = ''; error.value = ''; notice.value = ''
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
</script>
<template>
  <section class="wf-panel advanced-panel">
    <p v-if="error" role="alert" class="wf-message error">{{ error }}</p><p v-if="notice" role="status" class="wf-message success">{{ notice }}</p><p v-if="busy" role="status">正在处理…</p>
    <template v-if="teacher && teacherMode === 'retake'">
      <p v-if="run.adoptedFor.length">已采用成绩：{{ run.members.filter(m => run.adoptedFor.includes(m.sid)).map(m => m.name).join('、') }}</p>
      <p v-if="run.retakeRequest">{{ run.retakeRequest.status === 'pending' ? '待处理' : '已安排' }} · {{ run.retakeRequest.reason }}</p>
      <fieldset :disabled="busy">
        <label>临时组名称<input v-model="retake.name"></label>
        <div class="wf-checks"><label v-for="m in eligible" :key="m.sid"><input v-model="retake.members" type="checkbox" :value="m.sid"> {{ m.name }}（{{ m.sid }}）</label></div>
        <label>重做安排依据<textarea v-model="retake.reason" rows="2"></textarea></label>
        <button class="btn btn-primary" @click="send('retake', retake, true)">建立新的实验记录</button>
        <hr>
        <label>采用本次成绩的依据<textarea v-model="reason" rows="2"></textarea></label>
        <button class="btn btn-ghost" :disabled="!run.archived" @click="send('adopt', { reason })">采用本次成绩</button>
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
.advanced-panel{margin-top:0;border-top:0;border-radius:0 0 14px 14px}.ai-comment{white-space:pre-wrap}.advanced-panel label{display:block}.report-card--assist{margin-top:16px;padding:18px}.wf-empty--inline{padding:16px;margin:8px 0;text-align:left;background:#f5f8f9;border-radius:8px;color:#70828a}
</style>
