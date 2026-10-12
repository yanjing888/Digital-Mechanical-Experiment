<script setup>
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  message: { type: String, default: '' },
  type: { type: String, default: 'info' },
})
const emit = defineEmits(['close'])
let timer
watch(() => props.message, message => {
  clearTimeout(timer)
  if (message) timer = setTimeout(() => emit('close'), props.type === 'error' ? 6000 : 3200)
}, { immediate: true })
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <Teleport to="body">
    <Transition name="toast-slide">
      <div v-if="message" class="workflow-toast" :class="`workflow-toast--${type}`" :role="type === 'error' ? 'alert' : 'status'" :aria-live="type === 'error' ? 'assertive' : 'polite'">
        <span class="workflow-toast__mark" aria-hidden="true">{{ type === 'error' ? '!' : type === 'success' ? '✓' : 'i' }}</span>
        <span class="workflow-toast__message">{{ message }}</span>
        <button type="button" aria-label="关闭通知" @click="emit('close')">×</button>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.workflow-toast{position:fixed;z-index:6000;top:20px;left:50%;transform:translateX(-50%);display:grid;grid-template-columns:24px minmax(0,1fr) 28px;align-items:center;gap:12px;width:min(640px,calc(100vw - 32px));box-sizing:border-box;padding:14px 14px 14px 16px;border:1px solid #cbd8dc;border-radius:8px;background:#fff;color:#294551;box-shadow:0 12px 32px #17313d2b;font-size:14px;line-height:1.55}
.workflow-toast__mark{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:#e7f2f3;color:#087c88;font-weight:700}
.workflow-toast__message{white-space:pre-wrap;overflow-wrap:anywhere}
.workflow-toast button{width:28px;height:28px;border:0;border-radius:5px;background:transparent;color:#657980;font-size:22px;line-height:1;cursor:pointer}
.workflow-toast button:hover{background:#eef3f4;color:#294551}
.workflow-toast--success{border-color:#b9d9c8}.workflow-toast--success .workflow-toast__mark{background:#e4f3eb;color:#247457}
.workflow-toast--error{border-color:#e0b2aa}.workflow-toast--error .workflow-toast__mark{background:#fbe8e5;color:#a33e31}
.toast-slide-enter-active,.toast-slide-leave-active{transition:opacity .18s ease,transform .18s ease}
.toast-slide-enter-from,.toast-slide-leave-to{opacity:0;transform:translate(-50%,-12px)}
@media(max-width:600px){.workflow-toast{top:10px;width:calc(100vw - 20px)}}
</style>
