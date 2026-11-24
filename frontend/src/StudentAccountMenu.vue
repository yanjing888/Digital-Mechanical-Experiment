<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { api } from './api.js'
const student = ref(null), open = ref(false), menu = ref(null), dialog = ref(null), busy = ref(false), error = ref(''), success = ref('')
const fields = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
function reset() { Object.assign(fields, { currentPassword: '', newPassword: '', confirmPassword: '' }); error.value = ''; success.value = '' }
function accountChanged(event) { student.value = event.detail?.role === 'student' ? event.detail.student : null; open.value = false; dialog.value?.close(); reset() }
function outside(event) { if (!menu.value?.contains(event.target)) open.value = false }
function escape(event) { if (event.key === 'Escape') open.value = false }
function showPassword() { open.value = false; reset(); dialog.value.showModal() }
function closePassword() { if (!busy.value) { dialog.value.close(); reset() } }
async function save() {
  if (busy.value) return
  error.value = ''; success.value = ''
  if (fields.newPassword !== fields.confirmPassword) { error.value = '两次输入的新密码不一致'; return }
  busy.value = true
  try {
    await api.changePassword({ ...fields })
    reset(); success.value = '密码已修改。当前页面保持登录，其他已有登录已失效。'
  } catch (err) { error.value = err.message || '修改失败，请重试' }
  finally { busy.value = false }
}
function logout() { open.value = false; document.getElementById('btn-logout')?.click() }
onMounted(() => { window.addEventListener('lab-account-changed', accountChanged); document.addEventListener('click', outside); document.addEventListener('keydown', escape) })
onUnmounted(() => { window.removeEventListener('lab-account-changed', accountChanged); document.removeEventListener('click', outside); document.removeEventListener('keydown', escape) })
</script>
<template>
  <div v-show="student" ref="menu" class="student-account">
    <button class="student-account__trigger" type="button" :aria-expanded="open" aria-controls="student-account-dropdown" @click="open = !open">
      <span class="student-account__avatar" aria-hidden="true">{{ student?.name?.slice(0, 1) || '学' }}</span>
      <span class="student-account__identity"><strong>{{ student?.name }}</strong><small>{{ student?.sid }}</small></span>
      <span aria-hidden="true">▾</span>
    </button>
    <div v-if="open" id="student-account-dropdown" class="student-account__dropdown">
      <p>{{ student?.groupName || '个人账号' }}</p>
      <button type="button" @click="showPassword">修改密码</button>
      <button type="button" @click="logout">退出登录</button>
    </div>
    <dialog ref="dialog" class="student-password" aria-labelledby="student-password-title" @cancel="event => { if (busy) event.preventDefault(); else reset() }" @close="reset">
      <form @submit.prevent="save">
        <header><h2 id="student-password-title">修改密码</h2><button type="button" aria-label="关闭修改密码" :disabled="busy" @click="closePassword">×</button></header>
        <p class="student-password__account">{{ student?.name }} · {{ student?.sid }}</p>
        <template v-if="!success">
          <label for="student-current-password">原密码</label><input id="student-current-password" v-model="fields.currentPassword" type="password" autocomplete="current-password" required :disabled="busy" autofocus>
          <label for="student-new-password">新密码</label><input id="student-new-password" v-model="fields.newPassword" type="password" autocomplete="new-password" minlength="6" maxlength="128" required aria-describedby="student-password-hint" :disabled="busy">
          <small id="student-password-hint">6 至 128 位，不能与原密码相同。</small>
          <label for="student-confirm-password">确认新密码</label><input id="student-confirm-password" v-model="fields.confirmPassword" type="password" autocomplete="new-password" minlength="6" maxlength="128" required :disabled="busy">
          <p v-if="error" class="student-password__error" role="alert">{{ error }}</p>
        </template>
        <p v-if="success" class="student-password__success" role="status">{{ success }}</p>
        <footer><button type="button" class="btn btn-ghost" :disabled="busy" @click="closePassword">{{ success ? '完成' : '取消' }}</button><button v-if="!success" type="submit" class="btn btn-primary" :disabled="busy">{{ busy ? '正在保存…' : '保存新密码' }}</button></footer>
      </form>
    </dialog>
  </div>
</template>
<style scoped>
.student-account{position:relative;color:#223945}.student-account__trigger{display:flex;align-items:center;gap:10px;border:0;border-radius:7px;background:transparent;padding:6px 8px;color:inherit;font:inherit;cursor:pointer;text-align:left}
.student-account__trigger:hover{background:#edf5f6}.student-account__avatar{display:grid;place-items:center;width:36px;height:36px;border-radius:50%;background:#245b65;color:white;font-weight:700;font-size:16px}
.student-account__identity{display:grid;gap:2px}.student-account__identity strong{font-size:14px}.student-account__identity small{font-size:12px;color:#647980}
.student-account__dropdown{position:absolute;right:0;top:calc(100% + 8px);width:210px;max-width:85vw;padding:8px;background:white;border:1px solid #dce6e9;border-radius:10px;box-shadow:0 8px 24px #14263020;z-index:100}
.student-account__dropdown p{padding:8px 10px;margin:0;color:#647980;font-size:12px;overflow-wrap:anywhere}.student-account__dropdown button{display:block;width:100%;min-height:42px;padding:10px;border:0;border-radius:6px;background:transparent;text-align:left;color:#223945;cursor:pointer;font:inherit;font-size:14px}.student-account__dropdown button:hover{background:#edf5f6}
.student-password{width:min(420px,calc(100vw - 32px));max-height:90vh;overflow:auto;box-sizing:border-box;padding:24px;border:0;border-radius:12px;color:#223945;background:white}.student-password::backdrop{background:#14263080}
.student-password header{display:flex;align-items:center;justify-content:space-between;gap:12px}.student-password h2{font-size:20px;margin:0}.student-password header button{border:0;background:none;font-size:26px;color:#647980;cursor:pointer}.student-password__account{font-size:13px;color:#647980;margin:8px 0 20px}
.student-password label{display:block;margin:14px 0 7px;font-size:14px}.student-password input{width:100%;box-sizing:border-box;border:1px solid #ccdadd;border-radius:6px;padding:10px;font:inherit}.student-password small{display:block;color:#647980;font-size:12px;margin-top:6px}.student-password footer{display:flex;justify-content:flex-end;gap:10px;margin-top:24px}.student-password .btn{background-image:none;box-shadow:none}.student-password .btn-primary{background:#245b65;border-color:#245b65}.student-password button:disabled{opacity:.55;cursor:wait}.student-password__error{color:#a33728;font-size:13px}.student-password__success{font-size:14px;line-height:1.7;color:#24623c}.student-account button:focus-visible,.student-password input:focus-visible{outline:2px solid #087c88;outline-offset:2px}
</style>
