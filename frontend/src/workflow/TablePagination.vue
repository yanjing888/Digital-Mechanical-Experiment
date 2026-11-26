<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  total: { type: Number, required: true },
  page: { type: Number, required: true },
  pageSize: { type: Number, required: true },
  label: { type: String, default: '表格分页' },
  disabled: Boolean,
})
const emit = defineEmits(['update:page'])
const root = ref(null), compact = ref(false)
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const current = computed(() => Math.min(pages.value, Math.max(1, props.page)))
const items = computed(() => {
  const count = pages.value, at = current.value, limit = compact.value ? 5 : 7
  if (count <= limit) return Array.from({ length: count }, (_, i) => i + 1)
  const edge = limit - 2
  if (at <= edge - 1) return [...Array.from({ length: edge }, (_, i) => i + 1), 'end-gap', count]
  if (at >= count - edge + 2) return [1, 'start-gap', ...Array.from({ length: edge }, (_, i) => count - edge + i + 1)]
  const radius = compact.value ? 0 : 1
  return [1, 'start-gap', ...Array.from({ length: radius * 2 + 1 }, (_, i) => at - radius + i), 'end-gap', count]
})
function go(page) {
  if (!props.disabled && page >= 1 && page <= pages.value && page !== current.value) emit('update:page', page)
}
let observer
onMounted(() => {
  observer = new ResizeObserver(([entry]) => { compact.value = entry.contentRect.width < 520 })
  observer.observe(root.value)
})
onUnmounted(() => observer?.disconnect())
</script>

<template>
  <footer ref="root" class="table-pagination" :aria-label="label">
    <p class="table-pagination__summary" role="status">共 {{ total.toLocaleString('zh-CN') }} 条<span aria-hidden="true"> · </span>第 {{ current }} / {{ pages }} 页</p>
    <nav class="table-pagination__pages" :aria-label="label + '，选择页码'">
      <button type="button" class="table-pagination__arrow" aria-label="上一页" :disabled="disabled || current <= 1" @click="go(current - 1)"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m12 5-5 5 5 5" /></svg></button>
      <template v-for="item in items" :key="item">
        <button v-if="typeof item === 'number'" type="button" :aria-label="`第 ${item} 页`" :aria-current="item === current ? 'page' : undefined" :disabled="disabled" @click="go(item)">{{ item }}</button>
        <span v-else class="table-pagination__gap" aria-hidden="true">…</span>
      </template>
      <button type="button" class="table-pagination__arrow" aria-label="下一页" :disabled="disabled || current >= pages" @click="go(current + 1)"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 5 5 5-5 5" /></svg></button>
    </nav>
  </footer>
</template>

<style scoped>
.table-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; box-sizing: border-box; min-width: 0; padding: 10px 20px; border-top: 1px solid #e1e7ec; background: #fff; color: #677580; font-size: 13px; font-variant-numeric: tabular-nums; container-type: inline-size; }
.table-pagination__summary { margin: 0; font-size: 13px; line-height: 1.6; white-space: nowrap; }
.table-pagination__pages { display: flex; align-items: center; gap: 4px; margin-left: auto; }
.table-pagination__pages button { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; box-sizing: border-box; min-width: 28px; height: 28px; padding: 0 5px; border: 1px solid #d7dee4; border-radius: 6px; background: #fff; color: #263b47; font: inherit; font-size: 13px; line-height: 1; cursor: pointer; transition: background-color .15s, border-color .15s; }
.table-pagination__pages button:not(:disabled):hover { background: #f1f5fb; border-color: #9bb4d3; }
.table-pagination__pages button:not(:disabled):active { background: #e6edf7; }
.table-pagination__pages button[aria-current=page] { border-color: #305fa4; background: #305fa4; color: #fff; font-weight: 600; }
.table-pagination__pages button[aria-current=page]:not(:disabled):hover { border-color: #244d89; background: #244d89; }
.table-pagination__pages button:focus-visible { outline: 2px solid #305fa4; outline-offset: 3px; }
.table-pagination__pages button:disabled { opacity: .4; cursor: default; }
.table-pagination__arrow svg { width: 14px; height: 14px; }
.table-pagination__gap { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 28px; color: #8c969e; }
@container (max-width: 520px) { .table-pagination__summary { flex-basis: 100%; } }
@media (pointer: coarse) { .table-pagination__pages button { min-width: 32px; height: 32px; } }
@media (prefers-reduced-motion: reduce) { .table-pagination__pages button { transition: none; } }
</style>
