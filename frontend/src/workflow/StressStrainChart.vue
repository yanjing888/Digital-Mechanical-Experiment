<script setup>
import { computed } from 'vue'
import CurveChart from './CurveChart.vue'
import { stressStrainPoints } from './stress-strain.js'
const props = defineProps({ points: { type: Array, default: () => [] }, geometry: Object })
const converted = computed(() => {
  const geometry = props.geometry || {}
  return stressStrainPoints(props.points, Number(geometry.area), Number(geometry.gaugeLength))
})
</script>
<template>
  <section v-if="converted.length" class="wf-stress-strain">
    <CurveChart :points="converted" title="应力—应变曲线（设备位移估算）" x-label="估算应变 ε (%)" y-label="应力 σ (MPa)" />
  </section>
</template>
<style scoped>
.wf-stress-strain{margin-top:24px;padding-top:24px;border-top:1px solid #dce6e9}
</style>
