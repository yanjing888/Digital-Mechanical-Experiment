<script setup>
import { computed } from 'vue'
import { curveChartLayout } from './curve.js'

const props = defineProps({
  points: { type: Array, default: () => [] },
  title: { type: String, default: '力—位移曲线' },
  xLabel: { type: String, default: '位移 (mm)' },
  yLabel: { type: String, default: '力 (kN)' },
})

const chart = computed(() => curveChartLayout(props.points || []))
</script>

<template>
  <div class="wf-curve-chart">
    <h4 v-if="title">{{ title }}</h4>
    <p v-if="chart.empty" class="wf-empty wf-empty--inline">暂无曲线数据</p>
    <svg v-else viewBox="0 0 750 280" role="img" :aria-label="title || `${yLabel}—${xLabel}曲线`">
      <line :x1="chart.axis.x1" :y1="chart.axis.y1" :x2="chart.axis.x2" :y2="chart.axis.y2" stroke="#aab9bf" stroke-width="1" />
      <line :x1="chart.axis.x0" :y1="chart.axis.y0" :x2="chart.axis.x0" :y2="chart.axis.y2" stroke="#aab9bf" stroke-width="1" />
      <text
        v-for="(t, i) in chart.xTicks"
        :key="'x' + i"
        :x="t.x"
        :y="t.y"
        text-anchor="middle"
        class="wf-curve-chart__tick"
      >{{ t.label }}</text>
      <text
        v-for="(t, i) in chart.yTicks"
        :key="'y' + i"
        :x="t.x"
        :y="t.y + 4"
        :text-anchor="t.anchor"
        class="wf-curve-chart__tick"
      >{{ t.label }}</text>
      <text :x="chart.xLabel.x" :y="chart.xLabel.y" text-anchor="middle" class="wf-curve-chart__axis-label">{{ xLabel }}</text>
      <text :x="chart.yLabel.x" :y="chart.yLabel.y" text-anchor="middle" class="wf-curve-chart__axis-label" transform="rotate(-90 14 133)">{{ yLabel }}</text>
      <polyline :points="chart.line" fill="none" stroke="#087c88" stroke-width="2" />
    </svg>
  </div>
</template>

<style scoped>
.wf-curve-chart h4 { margin: 0 0 10px; font-size: 15px; }
.wf-curve-chart svg { width: 100%; max-height: 260px; display: block; }
.wf-curve-chart__tick { fill: #647980; font-size: 11px; font-family: ui-sans-serif, system-ui, sans-serif; }
.wf-curve-chart__axis-label { fill: #52666f; font-size: 12px; font-weight: 600; font-family: ui-sans-serif, system-ui, sans-serif; }
</style>
