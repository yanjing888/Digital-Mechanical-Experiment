<script setup>
import { computed, reactive, ref, watch } from 'vue'
const props = defineProps({ report: Object, busy: Boolean, mode: { type: String, default: 'review' } })
const emit = defineEmits(['ai', 'grade', 'return', 'similarity', 'update:mode'])
const mode = computed({ get: () => props.mode, set: value => emit('update:mode', value) })
const editing = ref(false), dirty = ref(false)
const form = reactive({ score: '', comment: '' })
const returnReason = ref('')
function reset() {
  form.score = props.report.finalScore ?? props.report.ai?.score ?? ''
  form.comment = props.report.finalComment ?? props.report.ai?.comment ?? ''
  editing.value = props.report.finalScore == null
  dirty.value = false; returnReason.value = ''
}
watch(() => props.report.id, reset, { immediate: true })
watch(() => props.report.ai, () => { if (!dirty.value && props.report.finalScore == null) reset() })
watch(() => [props.report.finalScore, props.report.finalComment, props.report.gradedAt], reset)
function adopt() { form.score = props.report.ai?.score ?? ''; form.comment = props.report.ai?.comment ?? ''; dirty.value = true }
function confirm() {
  if (form.score === '' || !Number.isFinite(Number(form.score)) || Number(form.score) < 0 || Number(form.score) > 100 || !form.comment.trim()) return
  emit('grade', { score: Number(form.score), comment: form.comment.trim() })
}
</script>
<template>
  <section class="report-review">
    <nav class="review-switch" aria-label="报告处理"><button type="button" :class="{active:mode==='review'}" @click="mode='review'">报告批阅</button><button type="button" :class="{active:mode==='similarity'}" @click="mode='similarity'">报告查重</button></nav>
    <template v-if="mode==='review'">
      <p class="review-flow">AI 批阅 → 教师复核 → 确认最终成绩</p>
      <section class="review-block"><header><h3>AI 批阅结果</h3><button type="button" class="btn btn-ghost" :disabled="busy || report.parseStatus!=='ready' || report.finalScore!=null" @click="emit('ai')">{{ report.ai ? '重新批阅' : '开始 AI 批阅' }}</button></header><template v-if="report.ai"><p class="review-score">{{ report.ai.score ?? '—' }} <small>分</small></p><p class="review-comment">{{ report.ai.comment }}</p></template><p v-else class="wf-hint">{{ report.parseStatus==='ready' ? '点击“开始 AI 批阅”生成评分与评语，随后由教师复核确认。' : '文档未完整解析，请教师对照原文件批阅。' }}</p></section>
      <section class="review-block"><header><h3>{{ report.finalScore != null && !editing ? '最终成绩' : '教师复核' }}</h3><span>{{ report.finalScore != null ? '已确认' : report.ai ? '待教师复核' : '待批阅' }}</span></header>
        <template v-if="report.finalScore!=null && !editing"><p class="review-score">{{ report.finalScore }} <small>分</small></p><p class="review-comment">{{ report.finalComment }}</p><button type="button" class="btn btn-ghost" :disabled="busy" @click="editing=true">修改评定</button></template>
        <form v-else @submit.prevent="confirm"><fieldset :disabled="busy"><div class="wf-actions"><button v-if="report.ai" type="button" class="wf-text-button" @click="adopt">采用 AI 评分与评语</button></div><label>最终评分（0–100 分）<input v-model="form.score" type="number" min="0" max="100" step="any" required @input="dirty=true"></label><label>最终评语<textarea v-model="form.comment" rows="6" required placeholder="复核 AI 评语，并补充或修改批阅意见" @input="dirty=true"></textarea></label><div class="wf-actions"><button type="submit" class="btn btn-primary">确认最终成绩</button><button v-if="report.finalScore!=null" type="button" class="btn btn-ghost" @click="reset">取消修改</button></div></fieldset></form>
      </section>
      <details class="review-block"><summary>退回学生修改</summary><fieldset :disabled="busy"><label>退回理由<textarea v-model="returnReason" rows="3" /></label><button type="button" class="btn btn-ghost" :disabled="!returnReason.trim()" @click="emit('return', {comment:returnReason.trim()})">退回修改</button></fieldset></details>
    </template>
    <section v-else class="review-block"><header><h3>报告查重</h3><button type="button" class="btn btn-primary" :disabled="busy" @click="emit('similarity')">{{ report.similarity ? '重新查重' : '开始组内查重' }}</button></header><p class="wf-hint">对本组已提交报告进行相似度检查，展示当前报告的查重结果。</p><template v-if="report.similarity"><p class="review-score">{{ report.similarity.maxPercent }}<small>% 最高相似度</small></p><p>{{ report.similarity.flagged ? `建议扣 ${report.similarity.suggestedDeduction} 分，须由教师复核。` : '未标记雷同' }}</p></template><p v-else class="wf-hint">尚未查重。</p><div v-if="report.foreignDataEvidence?.length" class="wf-message error"><p v-for="(e,i) in report.foreignDataEvidence" :key="i">{{ e.groupName }} · {{ e.note }}</p></div><p class="wf-hint">查重结果单独展示，不自动修改最终成绩。</p></section>
  </section>
</template>
<style scoped>
.report-review{margin-top:24px}.review-switch{display:flex;border-bottom:1px solid #dce6e9;gap:24px}.review-switch button{border:0;border-bottom:3px solid transparent;background:none;padding:14px 4px;font:inherit;color:#647980;cursor:pointer}.review-switch button.active{border-color:#087c88;color:#087c88;font-weight:600}.review-flow{font-size:13px;color:#647980;padding:12px 0}.review-block{padding:22px;border:1px solid #dce6e9;border-radius:8px;margin:18px 0;background:#fff}.review-block header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.review-block header h3{margin:0}.review-block header span{font-size:12px;color:#087c88}.review-score{font-size:30px;font-weight:700;color:#087c88;margin:10px 0}.review-score small{font-size:13px;font-weight:400}.review-comment{white-space:pre-wrap}.review-block label{display:grid;gap:8px;margin:14px 0}.review-block input{max-width:180px}.review-block textarea{width:100%;box-sizing:border-box}.review-block .wf-hint{margin-bottom:0}@media(max-width:650px){.review-block{padding:16px}.review-block header{flex-wrap:wrap}}
</style>
