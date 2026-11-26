import { computed, ref, watch } from 'vue'

export function usePagination(rows, resetOn = []) {
  const page = ref(1), pageSize = ref(10)
  const pageCount = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
  const pagedRows = computed(() => rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
  watch([pageSize, ...resetOn], () => { page.value = 1 })
  watch(pageCount, count => { page.value = Math.min(page.value, count) }, { flush: 'sync' })
  return { page, pageSize, pagedRows }
}
