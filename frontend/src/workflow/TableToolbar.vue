<script setup>
import RefreshButton from './RefreshButton.vue'
defineProps({ label: { type: String, default: '筛选表格' }, refreshLabel: String, fieldCount: { type: Number, default: 5 }, filtered: Boolean, loading: Boolean })
const emit = defineEmits(['refresh', 'reset'])
</script>

<template>
  <div class="table-toolbar" :aria-label="label" :style="{ '--filter-columns': Math.max(1, fieldCount - 1) }">
    <fieldset class="table-toolbar__fields" :class="{ 'table-toolbar__fields--short': fieldCount < 4 }" :disabled="loading" :aria-label="label"><slot /></fieldset>
    <div class="table-toolbar__actions"><button v-if="filtered" type="button" class="table-toolbar__reset" :disabled="loading" @click="emit('reset')">重置</button><RefreshButton :label="refreshLabel" :loading="loading" @refresh="emit('refresh')" /></div>
  </div>
</template>

<style scoped>
.table-toolbar { display: flex; align-items: flex-end; gap: 12px; margin: 0 0 20px; min-width: 0; }
.table-toolbar__fields { display: grid; grid-template-columns: minmax(190px, 1.45fr) repeat(var(--filter-columns), minmax(108px, 1fr)); align-items: end; gap: 12px; flex: 1; min-width: 0; margin: 0; padding: 0; border: 0; }
.table-toolbar__fields--short { flex: 0 1 620px; grid-template-columns: minmax(190px, 1.4fr) repeat(var(--filter-columns), minmax(150px, 1fr)); }
.table-toolbar__fields :deep(label) { display: grid; gap: 7px; min-width: 0; color: #637681; font-size: 12px; line-height: 1.5; }
.table-toolbar__fields :deep(input), .table-toolbar__fields :deep(select) { box-sizing: border-box; width: 100%; max-width: 100%; min-width: 0; height: 36px; min-height: 36px; margin: 0; padding: 7px 10px; border: 1px solid #d4dfe5; border-radius: 6px; background: #fff; color: #263d49; font: inherit; font-size: 13px; }
.table-toolbar__fields :deep(input::placeholder) { color: #8998a1; }
.table-toolbar__fields :deep(input:hover:not(:disabled)), .table-toolbar__fields :deep(select:hover:not(:disabled)) { border-color: #96b4bd; }
.table-toolbar__fields :deep(input:focus-visible), .table-toolbar__fields :deep(select:focus-visible) { outline: 2px solid #7daab4; outline-offset: 2px; }
.table-toolbar__actions { display: flex; align-items: center; flex-shrink: 0; gap: 10px; margin-left: auto; }
.table-toolbar__reset { padding: 5px 2px; border: 0; background: transparent; color: #356b78; font: inherit; font-size: 13px; cursor: pointer; white-space: nowrap; }
.table-toolbar__reset:hover { text-decoration: underline; text-underline-offset: 3px; }
.table-toolbar__reset:focus-visible { outline: 2px solid #245b65; outline-offset: 3px; }
@media (max-width: 1180px) { .table-toolbar { flex-wrap: wrap; } .table-toolbar__fields { grid-template-columns: minmax(190px, 1.4fr) repeat(2, minmax(130px, 1fr)); flex-basis: calc(100% - 100px); } .table-toolbar__fields--short { flex-basis: 620px; grid-template-columns: minmax(190px, 1.4fr) repeat(var(--filter-columns), minmax(150px, 1fr)); } }
@media (max-width: 700px) { .table-toolbar { gap: 12px 10px; } .table-toolbar__fields, .table-toolbar__fields--short { flex: 1 1 100%; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; } .table-toolbar__fields :deep(.table-toolbar__search) { grid-column: 1 / -1; } .table-toolbar__actions { margin-top: 0; } }
</style>
