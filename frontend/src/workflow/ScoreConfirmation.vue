<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ run: Object, teacher: Boolean, busy: Boolean })
const emit = defineEmits(['confirm', 'retake', 'submit'])
const requestingRetake = ref(false), reason = ref('')
const complete = computed(() => props.run.trialItems
  ? props.run.trialItems.length > 0 && props.run.trialItems.every(t => t.dataReady && t.photoReady)
  : !!props.run.data && !!props.run.photos?.length)
const deductions = computed(() => (props.run.deductions || []).filter(d => !d.voided))
const deducted = computed(() => deductions.value.reduce((total, item) => total + Number(item.points || 0), 0))
function submitRetake() {
  if (!reason.value.trim()) return
  emit('retake', reason.value.trim())
  requestingRetake.value = false
}
</script>
<template>
  <section class="score-confirmation">
    <header><h3>{{ run.classOpen ? '当前操作分' : '操作成绩' }} <strong>{{ run.operationScore }}<small>分</small></strong></h3><p>基础分 100，扣 {{ deducted }} 分</p></header>
    <table v-if="deductions.length"><thead><tr><th>扣分项</th><th>扣分</th><th>原因</th></tr></thead><tbody><tr v-for="item in deductions" :key="item.id"><td>{{ item.label }}</td><td>{{ item.points }} 分</td><td>{{ item.reason || '—' }}</td></tr></tbody></table>
    <p v-else class="score-note">暂无扣分记录。</p>
    <p v-if="!complete" class="score-note">完成全部子实验后，由教师确认扣分。</p>
    <p v-else-if="run.classOpen" class="score-note">等待教师确认扣分，确认后可选择提交记录或申请重做。</p>
    <template v-else-if="!teacher">
      <p v-if="run.retakeRequest?.status === 'pending'" class="score-note">重做申请已提交，等待教师安排。{{ run.retakeRequest.reason }}</p>
      <p v-else-if="run.retakeRequest?.status === 'arranged'" class="score-note">教师已安排重做，请在实验记录中选择新的实验。</p>
      <p v-else-if="run.myScoreConfirmed && run.archived" class="score-note">已接受操作成绩，操作记录已提交。</p>
      <template v-else>
        <p v-if="run.retakeRequest?.status === 'rejected'" class="score-note">教师未安排重做：{{ run.retakeRequest.reason }}</p>
        <div class="wf-actions"><button class="btn btn-primary" :disabled="busy" @click="run.archived ? emit('confirm', { action: 'confirm' }) : emit('submit')">{{ run.archived ? '接受操作成绩' : '接受成绩并提交记录' }}</button><button class="btn btn-ghost" :disabled="busy" @click="requestingRetake = !requestingRetake">申请重做</button></div>
        <form v-if="requestingRetake" class="score-retake" @submit.prevent="submitRetake"><label>重做原因<textarea v-model="reason" required maxlength="1000" rows="3" /></label><div class="wf-actions"><button class="btn btn-primary" :disabled="busy || !reason.trim()">提交重做申请</button><button type="button" class="btn btn-ghost" @click="requestingRetake = false">取消</button></div></form>
      </template>
    </template>
  </section>
</template>
<style scoped>
.score-confirmation{padding:0 0 24px;border-bottom:1px solid #dce6e9;margin:0 0 28px}.score-confirmation header{display:flex;justify-content:space-between;align-items:baseline;gap:16px}.score-confirmation h3{margin:0;font-size:16px}.score-confirmation h3 strong{margin-left:16px;color:#245b65;font-size:30px;font-variant-numeric:tabular-nums}.score-confirmation h3 small{margin-left:6px;font-size:14px;font-weight:400}.score-confirmation header p,.score-note{color:#52666f;font-size:14px;line-height:1.7}.score-confirmation label{display:grid;gap:8px;margin:16px 0}.score-confirmation table{width:100%;border-collapse:collapse;margin:16px 0;font-size:14px}.score-confirmation th,.score-confirmation td{padding:10px 8px;border-bottom:1px solid #e5edef;text-align:left;vertical-align:top;background:transparent}.score-confirmation th:nth-child(2),.score-confirmation td:nth-child(2){text-align:right;white-space:nowrap;padding-right:20px}.score-retake{margin-top:16px}.score-retake textarea{width:100%;box-sizing:border-box}.score-retake .wf-actions{margin-bottom:0}@media(max-width:600px){.score-confirmation header{align-items:flex-start;flex-direction:column;gap:4px}.score-confirmation header p{margin:4px 0}.score-confirmation th,.score-confirmation td{padding:10px 4px}}
</style>
<style scoped>
.score-confirmation .btn{background-image:none;box-shadow:none}
.score-confirmation .btn-primary{background:#245b65;color:#fff;border-color:#245b65}
</style>
