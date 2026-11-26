<script setup>
defineProps({ modelValue: { type: Number, default: 10 }, label: { type: String, default: '每页条数' }, disabled: Boolean, compact: Boolean })
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <label class="table-page-size" :class="{ 'table-page-size--compact': compact }"><span>每页</span><select :value="modelValue" :aria-label="label" :disabled="disabled" @change="emit('update:modelValue', Number($event.target.value))"><option v-for="size in [10,20,50]" :key="size" :value="size">{{ size }} 条</option></select></label>
</template>

<style scoped>
.table-page-size { display: inline-flex; align-items: center; flex-shrink: 0; gap: 8px; margin: 0; color: #677580; font-size: 13px; font-weight: 400; white-space: nowrap; }
.table-page-size > select { box-sizing: border-box; width: auto; min-width: 76px; height: 34px; min-height: 34px; padding: 4px 9px; border: 1px solid #d7dee4; border-radius: 6px; background: #fff; color: #263b47; font: inherit; cursor: pointer; }
.table-page-size > select:hover:not(:disabled) { border-color: #9bb4d3; }
.table-page-size > select:focus-visible { outline: 2px solid #305fa4; outline-offset: 2px; }
.table-page-size > select:disabled { opacity: .5; cursor: default; }
.table-page-size--compact { font-size: 12px; gap: 6px; }
.table-page-size--compact > select { min-width: 68px; height: 28px; min-height: 28px; padding: 2px 6px; }
</style>
