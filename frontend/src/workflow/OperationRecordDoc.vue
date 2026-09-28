<script setup>
import { computed } from 'vue'
import CurveChart from './CurveChart.vue'
import StressStrainChart from './StressStrainChart.vue'
import { isTensileTrial } from './stress-strain.js'

const props = defineProps({
  run: { type: Object, required: true },
  points: { type: Array, default: () => [] },
  images: { type: Object, default: () => ({}) },
  deductions: { type: Array, default: () => [] },
  readOnly: { type: Boolean, default: true },
})

const stamp = v => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—')

const deductionTotal = computed(() =>
  props.deductions.reduce((sum, d) => sum + (Number(d.points) || 0), 0),
)
const operationScore = computed(() => {
  const r = props.run
  if (r.finalScore != null) return r.finalScore
  if (r.operationScore != null) return r.operationScore
  return Math.max(0, 100 - deductionTotal.value)
})
</script>

<template>
  <article class="wf-op-doc" :class="{ 'wf-op-doc--readonly': readOnly }">
    <header class="wf-op-doc__mast">
      <p class="wf-op-doc__org">力 学 实 验</p>
      <h1 class="wf-op-doc__title">实验操作记录报告</h1>
      <p class="wf-op-doc__subtitle">{{ run.expName || '力学实验' }}</p>
      <dl class="wf-op-doc__meta">
        <div><dt>实验小组</dt><dd>{{ run.groupName }} · 第 {{ run.attempt }} 次</dd></div>
        <div><dt>组员</dt><dd>{{ (run.members || []).map(m => m.name).join('、') || '—' }}</dd></div>
        <div><dt>实验时间</dt><dd>{{ run.experimentAt ? stamp(run.experimentAt) : (run.timeText || '—') }}</dd></div>
        <div v-if="run.specimenId"><dt>试件编号</dt><dd>{{ run.specimenId }}</dd></div>
        <div v-if="run.deviceId"><dt>设备编号</dt><dd>{{ run.deviceId }}</dd></div>
        <div><dt>记录状态</dt><dd>{{ run.archived ? `已提交 · ${stamp(run.archivedAt)}` : '待确认提交' }}</dd></div>
      </dl>
    </header>

    <template v-if="run.trialItems">
      <section v-for="(trial, index) in run.trialItems" :key="trial.id" class="wf-op-doc__section">
        <h2>{{ index + 1 }}. {{ trial.name }}</h2>
        <p class="wf-op-doc__note">试件编号：{{ trial.specimenId || '—' }} · 设备编号：{{ trial.deviceId || '—' }} · 试验时间：{{ stamp(trial.experimentAt) }}</p>
        <template v-if="trial.data"><table class="wf-op-doc__kv"><tbody><tr><th>最大力</th><td>{{ trial.data.maxF?.toFixed?.(3) ?? trial.data.maxF }} kN</td><th>最大位移</th><td>{{ trial.data.maxD?.toFixed?.(3) ?? trial.data.maxD }} mm</td></tr></tbody></table><CurveChart :points="trial.data.points || []" :title="`${trial.name} · 力—位移曲线`" /></template>
        <p v-else class="wf-op-doc__empty">本子项尚未采集设备数据。</p>
        <StressStrainChart v-if="trial.data && isTensileTrial(trial.id)" :points="trial.data.points || []" />
        <div class="wf-op-doc__photos"><figure v-for="photo in trial.photos || []" :key="photo.id"><img v-if="images[photo.id]" :src="images[photo.id]" :alt="`${trial.name}试件形貌`"><figcaption>{{ trial.name }} · {{ photo.angle }}</figcaption></figure></div>
        <p v-if="!trial.photos?.length" class="wf-op-doc__empty">本子项尚未采集试件照片。</p>
        <div v-if="trial.fractureSummary" class="wf-op-doc__callout"><p class="wf-op-doc__verdict">形貌分析：{{ trial.fractureSummary.label }}</p><ul class="wf-op-doc__list"><li v-for="(line, i) in trial.fractureSummary.evidence || []" :key="i">{{ line }}</li></ul></div>
      </section>
    </template>
    <template v-else>
    <section class="wf-op-doc__section">
      <h2><span class="wf-op-doc__num">一、</span>实验数据与曲线</h2>
      <template v-if="run.data">
        <table class="wf-op-doc__kv">
          <caption>表 1　实验主要测量结果</caption>
          <tbody>
            <tr><th>最大力</th><td>{{ run.data.maxF?.toFixed?.(3) ?? run.data.maxF }} kN</td></tr>
            <tr><th>最大位移</th><td>{{ run.data.maxD?.toFixed?.(3) ?? run.data.maxD }} mm</td></tr>
          </tbody>
        </table>
        <figure class="wf-op-doc__curve">
          <CurveChart :points="points" title="" />
          <figcaption>图 1　力—位移曲线</figcaption>
        </figure>
        <StressStrainChart v-if="isTensileTrial(run.expId)" :points="points" />
        <p v-if="run.fractureSummary?.curve?.label" class="wf-op-doc__note">曲线形貌线索：{{ run.fractureSummary.curve.label }}</p>
      </template>
      <p v-else class="wf-op-doc__empty">尚未关联设备试验数据。</p>
    </section>

    <section class="wf-op-doc__section">
      <h2><span class="wf-op-doc__num">二、</span>断口观察与分析</h2>
      <div v-if="run.photos?.length" class="wf-op-doc__photos">
        <figure v-for="(p, index) in run.photos" :key="p.id">
          <img v-if="images[p.id]" :src="images[p.id]" :alt="`${p.angle}断口`">
          <figcaption>图 {{ index + (run.data ? 2 : 1) }}　{{ p.angle || '试件' }}断口形貌</figcaption>
        </figure>
      </div>
      <p v-else class="wf-op-doc__empty">暂无断口照片。</p>
      <div v-if="run.fractureSummary" class="wf-op-doc__callout">
        <p class="wf-op-doc__verdict">综合判别：{{ run.fractureSummary.label }}</p>
        <p v-if="run.fractureSummary.photo?.label" class="wf-op-doc__note">照片：{{ run.fractureSummary.photo.label }}<span v-if="run.fractureSummary.curve?.label"> · 曲线：{{ run.fractureSummary.curve.label }}</span></p>
        <ul v-if="run.fractureSummary.evidence?.length" class="wf-op-doc__list">
          <li v-for="(line, i) in run.fractureSummary.evidence" :key="i">{{ line }}</li>
        </ul>
      </div>
    </section>

    </template>
    <section class="wf-op-doc__section">
      <h2>实验操作评价</h2>
      <div class="wf-op-doc__scoreline">
        <div class="wf-op-doc__score-main">
          <span>操作成绩：</span><span class="wf-op-doc__score-num">{{ operationScore }}</span><span class="wf-op-doc__score-unit">分</span>
        </div>
        <p class="wf-op-doc__note">基础分 100 · 已扣 {{ deductionTotal }} 分<span v-if="run.classOpen"> · 课堂记录进行中，扣分可能继续更新</span></p>
      </div>
      <table v-if="deductions.length" class="wf-op-doc__table">
        <thead><tr><th>扣分项</th><th>分值</th><th>依据</th><th>记录时间</th></tr></thead>
        <tbody>
          <tr v-for="d in deductions" :key="d.id">
            <td>{{ d.label }}</td>
            <td>-{{ d.points }}</td>
            <td>{{ d.reason || '—' }}</td>
            <td>{{ stamp(d.at) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="wf-op-doc__empty">本次实验暂无教师扣分记录。</p>
      <p v-if="run.finalComment" class="wf-op-doc__note">教师评语：{{ run.finalComment }}</p>
    </section>

    <footer class="wf-op-doc__section--tail">
      <p>实验小组：{{ run.groupName || '—' }}</p>
      <p>{{ run.archived ? `提交时间：${stamp(run.archivedAt)}` : '提交状态：待小组核对确认' }}</p>
    </footer>
  </article>
</template>

<style scoped>
.wf-op-doc {
  box-sizing: border-box;
  max-width: 900px;
  margin: 0 auto;
  padding: 48px 56px;
  background: #fff;
  border: 1px solid #d8dcdf;
  box-shadow: 0 4px 20px #24313c08;
  color: #252b30;
  font-family: "Noto Serif SC", "Songti SC", SimSun, serif;
  font-size: 15px;
  line-height: 1.9;
}
.wf-op-doc__mast { text-align: center; margin-bottom: 32px; }
.wf-op-doc__org { margin: 0 0 12px; font-size: 13px; letter-spacing: .3em; color: #555; }
.wf-op-doc .wf-op-doc__title { margin: 0; font-size: 28px; font-weight: 700; letter-spacing: .12em; line-height: 1.5; color: #252b30; }
.wf-op-doc__subtitle { margin: 12px 0 28px; font-size: 18px; }
.wf-op-doc__meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 28px; margin: 0; padding: 20px 0; border-top: 2px solid #333; border-bottom: 1px solid #777; text-align: left; font-size: 14px; }
.wf-op-doc__meta div { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 12px; }
.wf-op-doc__meta dt { color: #555; }
.wf-op-doc__meta dd { margin: 0; overflow-wrap: anywhere; }
.wf-op-doc__section { margin-bottom: 36px; }
.wf-op-doc__section h2 { display: flex; gap: 4px; margin: 0 0 18px; padding-bottom: 8px; border-bottom: 1px solid #c8cdd0; font-size: 18px; font-weight: 700; color: #252b30; }
.wf-op-doc__num { white-space: nowrap; }
.wf-op-doc table { width: 100%; border-collapse: collapse; font-size: 14px; border-top: 1.5px solid #555; border-bottom: 1.5px solid #555; }
.wf-op-doc table caption { padding: 0 0 10px; font-size: 14px; color: #454b50; }
.wf-op-doc table th, .wf-op-doc table td { padding: 10px 14px; border: 0; border-bottom: 1px solid #d4d7d9; text-align: left; overflow-wrap: anywhere; }
.wf-op-doc table th { background: #f7f7f6; color: #333; font-weight: 600; }
.wf-op-doc__kv th { width: 40%; }
.wf-op-doc__curve { margin: 24px 0 18px; }
.wf-op-doc figcaption { text-align: center; margin-top: 10px; font-size: 13px; color: #555; }
.wf-op-doc__photos { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 24px; margin-bottom: 22px; }
.wf-op-doc__photos figure { margin: 0; text-align: center; }
.wf-op-doc__photos img { width: 100%; height: 220px; object-fit: contain; background: #fafafa; }
.wf-op-doc__callout { padding: 4px 0; }
.wf-op-doc__verdict { margin: 0 0 8px; font-size: 16px; font-weight: 700; }
.wf-op-doc__list { margin: 12px 0 0; padding-left: 1.5em; }
.wf-op-doc__scoreline { margin-bottom: 18px; }
.wf-op-doc__score-main { display: flex; align-items: baseline; gap: 6px; }
.wf-op-doc__score-num { font-size: 22px; font-weight: 700; }
.wf-op-doc__score-unit { font-size: 14px; }
.wf-op-doc__empty { margin: 0; padding: 12px 0; color: #666; }
.wf-op-doc__note { margin: 12px 0 0; color: #555; font-size: 14px; }
.wf-op-doc__section--tail { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 24px; padding-top: 18px; border-top: 1px solid #777; color: #555; font-size: 13px; }
.wf-op-doc__section--tail p { margin: 0; }
@media (max-width: 640px) {
  .wf-op-doc { padding: 28px 18px; font-size: 14px; }
  .wf-op-doc .wf-op-doc__title { font-size: 23px; letter-spacing: .05em; }
  .wf-op-doc__meta { grid-template-columns: 1fr; gap: 10px; }
  .wf-op-doc table th, .wf-op-doc table td { padding: 8px 6px; font-size: 12px; }
  .wf-op-doc__photos { grid-template-columns: minmax(0, 1fr); }
}
@media print {
  .wf-op-doc { max-width: none; padding: 0; border: 0; box-shadow: none; color: #000; }
  .wf-op-doc__section h2 { break-after: avoid; }
  .wf-op-doc figure, .wf-op-doc table, .wf-op-doc__mast { break-inside: avoid; }
}
</style>
