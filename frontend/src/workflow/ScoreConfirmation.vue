<script setup>
import { ref } from 'vue'
defineProps({ run: Object, teacher: Boolean, busy: Boolean })
const emit = defineEmits(['confirm', 'retake'])
const requestingRetake = ref(false), reason = ref('')
function submitRetake() {
  if (!reason.value.trim()) return
  emit('retake', reason.value.trim())
  requestingRetake.value = false
}
</script>
<template>
  <section class="score-confirmation">
    <header><h3>操作成绩 <strong v-if="teacher || !run.classOpen">{{ run.operationScore }} / 100</strong></h3><span>{{ run.classOpen ? (teacher ? '待发布' : '待教师评分') : '已发布' }}</span></header>
    <template v-if="teacher"><div class="score-decision" :class="{ 'score-decision--done': !run.classOpen }"><strong>{{ run.classOpen ? '扣分尚未确认' : '最终操作成绩已发布' }}</strong><span v-if="!run.classOpen && run.scorePublishedAt">{{ new Date(run.scorePublishedAt).toLocaleString('zh-CN', { hour12: false }) }}</span></div></template>
    <template v-else-if="!run.classOpen">
      <div v-if="run.retakeRequest?.status === 'pending'" class="score-decision score-decision--pending"><strong>重做申请待处理</strong><span>{{ run.retakeRequest.reason }}</span></div>
      <div v-else-if="run.retakeRequest?.status === 'arranged'" class="score-decision score-decision--done"><strong>教师已安排重做</strong></div>
      <div v-else-if="run.myScoreConfirmed" class="score-decision score-decision--done"><strong>本人成绩已确认</strong><span>{{ run.myConfirmation?.at ? new Date(run.myConfirmation.at).toLocaleString('zh-CN', { hour12: false }) : '' }}</span></div>
      <template v-else>
        <div class="wf-actions"><button class="btn btn-primary" :disabled="busy" @click="emit('confirm', { action: 'confirm' })">确认成绩</button><button class="btn btn-ghost" :disabled="busy" @click="requestingRetake = !requestingRetake">申请重做</button></div>
        <form v-if="requestingRetake" class="score-retake" @submit.prevent="submitRetake"><label>重做原因<textarea v-model="reason" required maxlength="1000" rows="3" /></label><div class="wf-actions"><button class="btn btn-primary" :disabled="busy || !reason.trim()">提交重做申请</button><button type="button" class="btn btn-ghost" @click="requestingRetake = false">取消</button></div></form>
      </template>
    </template>
  </section>
</template>
<style scoped>
.score-confirmation{padding:22px;border:1px solid #dce6e9;background:#fff;margin:20px 0;border-radius:8px}.score-confirmation header{display:flex;justify-content:space-between;align-items:center;gap:16px}.score-confirmation h3{margin:0;font-size:16px}.score-confirmation h3 strong{margin-left:16px;color:#087c88;font-size:24px}.score-confirmation label{display:grid;gap:8px;margin:16px 0}
.score-decision{display:flex;align-items:center;gap:14px;margin-top:18px;padding:14px 16px;border-radius:7px;background:#f5f8f9}.score-decision strong{margin:0;font-size:15px}.score-decision--pending{background:#fff6e8;color:#7d5519}.score-decision--done{background:#e9f6ee;color:#24623c}.score-retake{margin-top:14px;padding:16px;background:#f8fafb;border-radius:8px}.score-retake .wf-actions{margin-bottom:0}@media(max-width:600px){.score-confirmation header,.score-decision{align-items:flex-start;flex-direction:column}.score-confirmation h3 strong{display:block;margin:8px 0 0}}
</style>
