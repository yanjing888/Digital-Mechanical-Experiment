<script setup>
import { computed } from 'vue'
import CurveChart from './CurveChart.vue'
import { stressStrainPoints, FIXED_SAMPLE_GEOMETRY } from './stress-strain.js'
const props = defineProps({ points: { type: Array, default: () => [] }, geometry: Object })
const converted = computed(() => {
  const geometry = props.geometry || FIXED_SAMPLE_GEOMETRY
  return stressStrainPoints(props.points, Number(geometry.area), Number(geometry.gaugeLength))
})
</script>
<template>
  <section class="wf-stress-strain">
    <CurveChart v-if="converted.length" :points="converted" title="应力—应变曲线（设备位移估算）" x-label="估算应变 ε (%)" y-label="应力 σ (MPa)" />
    <p v-else>尚未录入原始截面积与标距</p>
  </section>
</template>
<style scoped>
.wf-stress-strain{margin-top:24px;padding-top:24px;border-top:1px solid #dce6e9}.wf-stress-strain p{margin:8px 0 0;color:#647980;font-size:12px;line-height:1.7}
</style>
