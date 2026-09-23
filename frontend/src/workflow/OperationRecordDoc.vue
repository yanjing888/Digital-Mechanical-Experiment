<script setup>
import { computed } from 'vue'
import CurveChart from './CurveChart.vue'

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
      <p class="wf-op-doc__org">力学实验数字化平台 · 实验操作记录</p>
      <h1 class="wf-op-doc__title">{{ run.expName || '力学实验' }}</h1>
      <dl class="wf-op-doc__meta">
        <div><dt>实验小组</dt><dd>{{ run.groupName }} · 第 {{ run.attempt }} 次</dd></div>
        <div><dt>组员</dt><dd>{{ (run.members || []).map(m => m.name).join('、') || '—' }}</dd></div>
        <div v-if="run.timeText"><dt>实验时间</dt><dd>{{ run.timeText }}</dd></div>
        <div><dt>记录状态</dt><dd>{{ run.archived ? `已提交 · ${stamp(run.archivedAt)}` : '待确认提交' }}</dd></div>
      </dl>
    </header>

    <section class="wf-op-doc__section">
      <h2><span class="wf-op-doc__num">一</span>试验设备数据</h2>
      <template v-if="run.data">
        <table class="wf-op-doc__kv">
          <tbody>
            <tr><th>最大力</th><td>{{ run.data.maxF?.toFixed?.(3) ?? run.data.maxF }} kN</td></tr>
            <tr><th>最大位移</th><td>{{ run.data.maxD?.toFixed?.(3) ?? run.data.maxD }} mm</td></tr>
            <tr><th>数据点数</th><td>{{ run.data.pointCount ?? points.length }}</td></tr>
            <tr v-if="run.data.fileName"><th>数据文件</th><td>{{ run.data.fileName }}<span v-if="run.data.importedAt"> · {{ stamp(run.data.importedAt) }}</span></td></tr>
          </tbody>
        </table>
        <CurveChart :points="points" title="" />
        <p v-if="run.fractureSummary?.curve?.label" class="wf-op-doc__note">曲线形貌线索：{{ run.fractureSummary.curve.label }}</p>
      </template>
      <p v-else class="wf-op-doc__empty">尚未关联设备试验数据。</p>
    </section>

    <section class="wf-op-doc__section">
      <h2><span class="wf-op-doc__num">二</span>断口图像与宏观分析</h2>
      <div v-if="run.photos?.length" class="wf-op-doc__photos">
        <figure v-for="p in run.photos" :key="p.id">
          <img v-if="images[p.id]" :src="images[p.id]" :alt="`${p.angle}断口`">
          <figcaption>{{ p.angle }} · {{ p.width }}×{{ p.height }}<span v-if="p.at"> · {{ stamp(p.at) }}</span></figcaption>
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

    <section class="wf-op-doc__section">
      <h2><span class="wf-op-doc__num">三</span>课堂操作扣分（教师现场记录）</h2>
      <div class="wf-op-doc__scoreline">
        <div class="wf-op-doc__score-main">
          <span class="wf-op-doc__score-num">{{ operationScore }}</span><span class="wf-op-doc__score-unit">分</span>
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
      <p v-else class="wf-op-doc__empty">暂无课堂扣分，操作规范。</p>
      <p v-if="run.finalComment" class="wf-op-doc__note">教师评语：{{ run.finalComment }}</p>
    </section>

    <section class="wf-op-doc__section wf-op-doc__section--tail">
      <h2><span class="wf-op-doc__num">四</span>记录说明</h2>
      <p class="wf-op-doc__prose">本记录由平台自动汇总本次试验的设备曲线、断口图像与宏观分析结果，并同步教师端课堂扣分。提交后记录锁定，作为小组实验报告与个人报告的共用依据；如需修改，请联系教师退回。</p>
    </section>
  </article>
</template>

<style scoped>
.wf-op-doc {
  max-width: 820px;
  margin: 0 auto;
  padding: 36px 40px 44px;
  background: #fff;
  border: 1px solid #d8e2e6;
  box-shadow: 0 2px 24px rgba(31, 48, 57, 0.06);
  border-radius: 4px;
  color: #223945;
  font-size: 14px;
  line-height: 1.75;
}
.wf-op-doc__mast {
  text-align: center;
  padding-bottom: 28px;
  margin-bottom: 28px;
  border-bottom: 2px solid #223945;
}
.wf-op-doc__org {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.12em;
  color: #647980;
}
.wf-op-doc__title {
  margin: 0 0 20px;
  font-size: 22px;
  font-weight: 700;
}
.wf-op-doc__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 24px;
  margin: 0;
  text-align: left;
  font-size: 13px;
}
.wf-op-doc__meta div {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
}
.wf-op-doc__meta dt {
  margin: 0;
  color: #647980;
}
.wf-op-doc__meta dd {
  margin: 0;
  font-weight: 600;
}
.wf-op-doc__section {
  margin-bottom: 32px;
}
.wf-op-doc__section h2 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 700;
  color: #087c88;
}
.wf-op-doc__num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e8f4f5;
  font-size: 13px;
}
.wf-op-doc__kv {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 16px;
  font-size: 13px;
}
.wf-op-doc__kv th,
.wf-op-doc__kv td {
  border: 1px solid #e0e9ec;
  padding: 8px 12px;
  text-align: left;
}
.wf-op-doc__kv th {
  width: 120px;
  background: #f5f8f9;
  color: #52666f;
  font-weight: 600;
}
.wf-op-doc__photos {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.wf-op-doc__photos figure {
  margin: 0;
  border: 1px solid #e0e9ec;
  padding: 8px;
  text-align: center;
}
.wf-op-doc__photos img {
  width: 100%;
  height: 120px;
  object-fit: contain;
  background: #f3f6f7;
}
.wf-op-doc__photos figcaption {
  font-size: 12px;
  color: #647980;
  margin-top: 6px;
}
.wf-op-doc__callout {
  padding: 16px 18px;
  background: #f8fbfb;
  border-left: 3px solid #087c88;
}
.wf-op-doc__verdict {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 700;
  color: #087c88;
}
.wf-op-doc__list {
  margin: 10px 0 0;
  padding-left: 1.25em;
  color: #52666f;
}
.wf-op-doc__scoreline {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 16px;
}
.wf-op-doc__score-main {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.wf-op-doc__score-num {
  font-size: 36px;
  font-weight: 700;
  color: #087c88;
  line-height: 1;
}
.wf-op-doc__score-unit {
  font-size: 14px;
  color: #647980;
}
.wf-op-doc__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.wf-op-doc__table th,
.wf-op-doc__table td {
  border: 1px solid #dce6e9;
  padding: 10px;
  text-align: left;
}
.wf-op-doc__table th {
  background: #f0f6f7;
}
.wf-op-doc__empty {
  margin: 0;
  padding: 14px;
  background: #f5f8f9;
  border-radius: 6px;
  color: #70828a;
}
.wf-op-doc__note {
  margin: 10px 0 0;
  color: #647980;
  font-size: 13px;
}
.wf-op-doc__prose {
  margin: 0;
  text-indent: 2em;
  color: #52666f;
}
.wf-op-doc__section--tail {
  margin-bottom: 0;
  padding-top: 8px;
  border-top: 1px dashed #dce6e9;
}
@media (max-width: 640px) {
  .wf-op-doc {
    padding: 24px 18px;
  }
  .wf-op-doc__meta {
    grid-template-columns: 1fr;
  }
}
</style>
