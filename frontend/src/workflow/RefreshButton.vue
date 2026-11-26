<script setup>
defineProps({ label: { type: String, default: '刷新数据' }, loading: Boolean })
const emit = defineEmits(['refresh'])
</script>

<template>
  <button type="button" class="table-refresh" :class="{ 'is-loading': loading }" :disabled="loading" :aria-label="label" :title="loading ? '正在刷新…' : label" :aria-busy="loading" @click="emit('refresh')"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.2 12a7.2 7.2 0 1 0-1.8 4.8M20 12l-2.6-5"/></svg></button>
</template>

<style scoped>
.table-refresh { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 36px; width: 36px; height: 36px; padding: 7px; border: 1px solid #d8e2e7; border-radius: 6px; background: #fff; color: #607681; cursor: pointer; box-sizing: border-box; }
.table-refresh svg { display: block; width: 20px; height: 20px; }
.table-refresh:hover:not(:disabled) { color: #245b65; border-color: #96b4bd; background: #f2f7f8; }
.table-refresh:focus-visible { outline: 2px solid #245b65; outline-offset: 3px; }
.table-refresh:disabled { cursor: default; opacity: .55; }
.table-refresh.is-loading svg { animation: refresh-turn .9s linear infinite; }
@keyframes refresh-turn { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .table-refresh.is-loading svg { animation: none; } }
</style>
