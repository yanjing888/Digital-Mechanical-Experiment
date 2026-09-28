<script setup>
import { computed } from 'vue'
import CurveChart from './CurveChart.vue'
import { stressStrainPoints } from './stress-strain.js'
const props = defineProps({ points: { type: Array, default: () => [] } })
const converted = computed(() => stressStrainPoints(props.points))
</script>
<template>
  <section class="wf-stress-strain">
    <CurveChart :points="converted" title="应力—应变曲线" x-label="应变 ε (%)" y-label="应力 σ (MPa)" />
    <p>试件直径 d = 10 mm · 原始标距 l₀ = 100 mm · 原始截面积 A₀ ≈ 78.54 mm²</p>
    <p>工程应力 σ = F / A₀；工程应变 ε = ΔL / l₀ × 100%。ΔL 采用设备位移，所得应变为近似值。</p>
  </section>
</template>
<style scoped>
.wf-stress-strain{margin-top:24px;padding-top:24px;border-top:1px solid #dce6e9}.wf-stress-strain p{margin:8px 0 0;color:#647980;font-size:12px;line-height:1.7}
</style>
