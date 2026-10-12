<script setup>
import { computed, reactive, ref, watch } from 'vue'
const props = defineProps({ report: Object, busy: Boolean, aiLoading: Boolean, mode: { type: String, default: 'review' } })
const emit = defineEmits(['ai', 'grade', 'return', 'similarity', 'update:mode'])
const mode = computed({ get: () => props.mode, set: value => emit('update:mode', value) })
const editing = ref(false), dirty = ref(false)
const reviewPane = ref('ai')
const form = reactive({ score: '', comment: '' })
const returnReason = ref('')
const aiContent = computed(() => {
  const text = String(props.report.ai?.comment || '').trim()
  if (!text) return { sections: [], summary: '' }
  const sections = [], remainder = []
  for (const line of text.split(/\r?\n/).map(value => value.trim()).filter(Boolean)) {
    const match = line.match(/^(.+?)[（(](\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)[）)]\s*[：:]\s*(.*)$/)
    if (match) sections.push({ title: match[1].trim(), score: match[2], maximum: match[3], text: match[4].trim() })
    else remainder.push(line)
  }
  if (!sections.length) return { sections: [{ title: '综合评语', score: '', maximum: '', text }], summary: '' }
  return { sections, summary: remainder.join('\n') }
})
function reset() {
  form.score = props.report.finalScore ?? props.report.ai?.score ?? ''
  form.comment = props.report.finalComment ?? props.report.ai?.comment ?? ''
  editing.value = props.report.finalScore == null
  dirty.value = false; returnReason.value = ''
}
watch(() => props.report.id, () => {
  reset()
  reviewPane.value = props.report.finalScore != null ? 'grade' : 'ai'
}, { immediate: true })
watch(() => props.report.ai, () => { if (!dirty.value && props.report.finalScore == null) reset() })
watch(() => [props.report.finalScore, props.report.finalComment, props.report.gradedAt], reset)
function adopt() { form.score = props.report.ai?.score ?? ''; form.comment = props.report.ai?.comment ?? ''; dirty.value = true; reviewPane.value = 'grade' }
function confirm() {
  if (form.score === '' || !Number.isFinite(Number(form.score)) || Number(form.score) < 0 || Number(form.score) > 100 || !form.comment.trim()) return
  emit('grade', { score: Number(form.score), comment: form.comment.trim() })
}
</script>
<template>
  <section class="report-review">
    <nav class="review-switch" role="tablist" aria-label="报告处理"><button type="button" role="tab" :aria-selected="mode==='review'" :class="{active:mode==='review'}" @click="mode='review'">报告批阅</button><button type="button" role="tab" :aria-selected="mode==='similarity'" :class="{active:mode==='similarity'}" @click="mode='similarity'">报告查重</button></nav>
    <template v-if="mode==='review'">
      <nav class="review-stage-switch" role="tablist" aria-label="批阅步骤">
        <button type="button" role="tab" :aria-selected="reviewPane==='ai'" :class="{active:reviewPane==='ai'}" @click="reviewPane='ai'"><span>AI 建议</span><b v-if="report.ai">{{ report.ai.score ?? '—' }}</b></button>
        <button type="button" role="tab" :aria-selected="reviewPane==='grade'" :class="{active:reviewPane==='grade'}" @click="reviewPane='grade'"><span>教师评定</span><b>{{ report.finalScore != null ? '已确认' : '待确认' }}</b></button>
      </nav>
      <div v-if="reviewPane==='ai'" class="review-content review-content--fixed" role="tabpanel">
      <section class="review-block review-ai" :aria-busy="aiLoading"><header><h3>AI 批阅结果</h3><button type="button" class="btn btn-primary" :disabled="busy || report.parseStatus!=='ready'" @click="emit('ai')">{{ aiLoading ? '批阅中…' : report.ai ? '重新 AI 批阅' : '开始 AI 批阅' }}</button></header>
        <div v-if="aiLoading" class="review-skeleton" role="status" aria-label="正在生成 AI 批阅结果"><div class="review-skeleton__score"></div><div v-for="i in 4" :key="i" class="review-skeleton__card"><i></i><span></span><span></span><span></span></div></div>
        <template v-else-if="report.ai"><div class="review-ai__total"><span>综合评分</span><strong>{{ report.ai.score ?? '—' }}</strong><small>分</small></div><div class="review-result-scroll"><article v-for="(section,index) in aiContent.sections" :key="`${section.title}-${index}`" class="review-dimension"><header><h4>{{ section.title }}</h4><b v-if="section.score">{{ section.score }} / {{ section.maximum }}</b></header><p>{{ section.text }}</p></article><p v-if="aiContent.summary" class="review-summary">{{ aiContent.summary }}</p></div></template>
        <p v-else class="wf-hint">{{ report.parseStatus==='ready' ? '尚未批阅' : '文档未完整解析，请教师对照原文件批阅。' }}</p>
      </section>
      </div>
      <div v-else class="review-content review-content--scroll" role="tabpanel">
      <section class="review-block"><header><h3>{{ report.finalScore != null && !editing ? '最终成绩' : '教师复核' }}</h3><span>{{ report.finalScore != null ? '已确认' : report.ai ? '待教师复核' : '待批阅' }}</span></header>
        <template v-if="report.finalScore!=null && !editing"><p class="review-score">{{ report.finalScore }} <small>分</small></p><p class="review-comment">{{ report.finalComment }}</p><button type="button" class="btn btn-ghost" :disabled="busy" @click="editing=true">修改评定</button></template>
        <form v-else @submit.prevent="confirm"><fieldset :disabled="busy"><div class="wf-actions"><button v-if="report.ai" type="button" class="wf-text-button" @click="adopt">采用 AI 评分与评语</button></div><label>最终评分（0–100 分）<input v-model="form.score" type="number" min="0" max="100" step="any" required @input="dirty=true"></label><label>最终评语<textarea v-model="form.comment" rows="6" required placeholder="填写批阅意见" @input="dirty=true"></textarea></label><div class="wf-actions"><button type="submit" class="btn btn-primary">确认最终成绩</button><button v-if="report.finalScore!=null" type="button" class="btn btn-ghost" @click="reset">取消修改</button></div></fieldset></form>
      </section>
      <details class="review-block"><summary>退回学生修改</summary><fieldset :disabled="busy"><label>退回理由<textarea v-model="returnReason" rows="3" /></label><button type="button" class="btn btn-ghost" :disabled="!returnReason.trim()" @click="emit('return', {comment:returnReason.trim()})">退回修改</button></fieldset></details>
      </div>
    </template>
    <div v-else class="review-content review-content--scroll" role="tabpanel"><section class="review-block"><header><h3>报告查重</h3><button type="button" class="btn btn-primary" :disabled="busy" @click="emit('similarity')">{{ busy ? '检查中…' : report.similarity ? '重新查重' : '开始查重' }}</button></header>
      <template v-if="report.similarity">
        <div class="review-checks">
          <div class="review-check" :class="{warn: report.similarity.dataConsistency && !report.similarity.dataConsistency.sameGroupData}"><span>数据归属</span><b>{{ !report.similarity.dataConsistency ? '待检查' : report.similarity.dataConsistency.sameGroupData ? '本组实验数据' : '需要复核' }}</b></div>
          <div class="review-check" :class="{warn: report.similarity.writingAssessment && !report.similarity.writingAssessment.reasonable}"><span>撰写检查</span><b>{{ !report.similarity.writingAssessment ? '待检查' : report.similarity.writingAssessment.reasonable ? '结构合理' : '需要复核' }}</b></div>
          <div class="review-check"><span>文本相似度</span><b>{{ report.similarity.available ? `${report.similarity.maxPercent}%` : '暂无对比报告' }}</b></div>
        </div>
        <p v-if="report.similarity.decision" class="review-decision">{{ report.similarity.decision }}</p>
        <div v-if="report.similarity.dataConsistency?.evidence?.length" class="wf-message error"><p v-for="(e,i) in report.similarity.dataConsistency.evidence" :key="i">{{ e.groupName }} · {{ e.note }}</p></div>
        <ul v-if="report.similarity.writingAssessment?.findings?.length" class="review-findings"><li v-for="(item,i) in report.similarity.writingAssessment.findings" :key="i">{{ item }}</li></ul>
      </template>
      <p v-else class="wf-hint">尚未查重</p>
    </section></div>
  </section>
</template>
<style scoped>
.report-review{height:100%;min-height:0;display:flex;flex-direction:column;margin-top:24px}.review-switch{display:flex;flex:0 0 auto;border-bottom:1px solid #dce6e9;gap:24px}.review-switch button{border:0;border-bottom:3px solid transparent;background:none;padding:14px 4px;font:inherit;color:#647980;cursor:pointer}.review-switch button.active{border-color:#087c88;color:#087c88;font-weight:600}.review-stage-switch{display:grid;grid-template-columns:1fr 1fr;gap:6px;flex:0 0 auto;padding:12px 0}.review-stage-switch button{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:9px 10px;border:1px solid #dce6e9;border-radius:6px;background:#f7f9fa;color:#526a74;cursor:pointer;font:inherit;font-size:13px}.review-stage-switch button.active{border-color:#087c88;background:#eaf5f5;color:#087c88;font-weight:600}.review-stage-switch b{font-size:11px;font-weight:500;white-space:nowrap}.review-content{min-height:0;flex:1}.review-content--fixed{display:flex;overflow:hidden}.review-content--scroll{overflow-y:auto;padding-right:5px;scrollbar-gutter:stable}.review-content--scroll::-webkit-scrollbar,.review-result-scroll::-webkit-scrollbar{width:7px}.review-content--scroll::-webkit-scrollbar-thumb,.review-result-scroll::-webkit-scrollbar-thumb{border-radius:8px;background:#bdcbd0}.review-block{padding:22px;border:1px solid #dce6e9;border-radius:8px;margin:0 0 12px;background:#fff}.review-block>header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.review-block>header h3{margin:0}.review-block>header span{font-size:12px;color:#087c88}.review-ai{display:flex;flex:1;min-height:0;flex-direction:column;margin:0}.review-score{font-size:30px;font-weight:700;color:#087c88;margin:10px 0}.review-score small{font-size:13px;font-weight:400}.review-comment{white-space:pre-wrap}.review-ai__total{display:flex;align-items:baseline;gap:6px;padding:4px 2px 14px;color:#647980}.review-ai__total strong{margin-left:auto;font-size:32px;line-height:1;color:#087c88}.review-ai__total small{font-size:12px}.review-result-scroll{display:grid;gap:10px;min-height:0;flex:1;overflow-y:auto;padding:2px 6px 2px 0;scrollbar-gutter:stable}.review-dimension{padding:13px;border:1px solid #dfe7ea;border-radius:7px;background:#f8fafb}.review-dimension header{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:0 0 8px}.review-dimension h4{margin:0;font-size:14px;color:#294551}.review-dimension b{flex-shrink:0;padding:3px 7px;border-radius:4px;background:#e4f2f3;color:#087c88;font-size:12px}.review-dimension p,.review-summary{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.7}.review-summary{padding:12px;color:#52666f;background:#f0f5f6;border-radius:7px}.review-skeleton{display:grid;gap:11px;overflow:hidden}.review-skeleton__score,.review-skeleton__card i,.review-skeleton__card span{display:block;border-radius:5px;background:linear-gradient(90deg,#edf1f2 25%,#f7f9fa 45%,#edf1f2 65%);background-size:220% 100%;animation:review-shimmer 1.25s ease-in-out infinite}.review-skeleton__score{width:42%;height:34px}.review-skeleton__card{display:grid;gap:9px;padding:14px;border:1px solid #e5ebed;border-radius:7px}.review-skeleton__card i{width:58%;height:14px}.review-skeleton__card span{height:10px}.review-skeleton__card span:nth-child(3){width:92%}.review-skeleton__card span:nth-child(4){width:70%}@keyframes review-shimmer{to{background-position:-220% 0}}.review-block label{display:grid;gap:8px;margin:14px 0}.review-block input{max-width:180px}.review-block textarea{width:100%;box-sizing:border-box;resize:vertical}.review-block .wf-hint{margin-bottom:0}.review-block summary{cursor:pointer;font-weight:600;color:#405762}.review-checks{display:grid;gap:10px}.review-check{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px;border:1px solid #dce6e9;border-radius:6px}.review-check span{color:#647980}.review-check b{color:#247457}.review-check.warn b{color:#a85c28}.review-decision{font-weight:600;margin:16px 0 0}.review-findings{margin:14px 0 0;padding-left:20px;color:#6b4b35}@media(max-width:650px){.report-review{height:auto}.review-block{padding:16px}.review-block>header{flex-wrap:wrap}.review-content--fixed{display:block;overflow:visible}.review-result-scroll{max-height:330px}.review-stage-switch{grid-template-columns:1fr}}
</style>
