<script setup>
import { ref } from 'vue'

const passwordVisible = ref(false)
const helpOpen = ref(false)
</script>

<template>
  <div id="screen-login" class="auth-login">
    <div class="auth-layout">
      <section class="auth-visual" aria-label="力学实验数字化平台">
        <img class="auth-visual__photo" src="/assets/login-hero-left-v2.png" alt="材料拉伸试验机" fetchpriority="high">
        <div class="auth-visual__brand">
          <img src="/assets/jyd-logo-white.png" alt="竞业达">
        </div>
        <div class="auth-visual__intro">
          <h2>力学实验数字化平台</h2>
          <p>汇集实验数据，留存每一次观察。</p>
        </div>
        <p class="auth-visual__caption">资料采集<span aria-hidden="true"> / </span>课堂记录<span aria-hidden="true"> / </span>实验报告</p>
      </section>

      <section class="auth-entry" aria-labelledby="login-title">
        <div class="auth-entry__content">
          <header class="auth-heading">
            <h1 id="login-title">欢迎登录</h1>
            <p>使用账号或学号，进入教学工作空间。</p>
          </header>

          <form class="auth-form" @submit.prevent>
            <div class="auth-field">
              <label for="login-account">账号<span>学生请填写完整学号</span></label>
              <input id="login-account" class="auth-input" autocomplete="username" placeholder="请输入账号或学号" autocapitalize="none" spellcheck="false">
            </div>
            <div class="auth-field">
              <label for="login-password">密码</label>
              <div class="auth-password">
                <input id="login-password" class="auth-input" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" aria-describedby="student-login-hint">
                <button type="button" class="auth-password__toggle" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" :title="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="passwordVisible" d="m3 3 18 18"/></svg>
                </button>
              </div>
              <p id="student-login-hint" class="auth-field__hint">学生初始密码为完整学号，修改后请使用新密码。</p>
            </div>

            <div class="auth-options">
              <label class="auth-remember"><input id="login-remember" type="checkbox">记住账号</label>
              <button id="btn-forgot" type="button" class="auth-help-trigger" :aria-expanded="helpOpen" aria-controls="login-help" @click="helpOpen = !helpOpen">忘记密码？</button>
            </div>
            <p v-if="helpOpen" id="login-help" class="auth-help">学生可联系任课教师恢复初始密码；教师请联系管理员。</p>
            <p id="login-feedback" class="auth-feedback" role="alert" hidden></p>
            <button id="btn-login" type="submit" class="auth-submit">登录</button>
          </form>
        </div>
        <footer class="auth-footer">力学实验数字化平台<span aria-hidden="true"> · </span>竞业达</footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.auth-login {
  --auth-ink: #233c47;
  --auth-muted: #62757f;
  --auth-accent: #256c83;
  --auth-line: #dbe4e8;
  --auth-paper: #fff;
  min-height: 100vh;
  min-height: 100dvh;
  background: #07333e;
  color: var(--auth-ink);
}
/* The photograph fills the viewport; the form overlays its open right-hand side. */
.auth-layout { position: relative; display: flex; align-items: center; min-height: 100vh; min-height: 100dvh; padding: 110px clamp(48px, 6vw, 140px) 72px; isolation: isolate; }
.auth-visual { position: absolute; inset: 0; overflow: hidden; background: #07333e; color: #fff; z-index: -1; }
.auth-visual__photo { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center; }
.auth-visual::after { content: ''; position: absolute; inset: 0; background: rgba(3, 20, 26, .16); pointer-events: none; }
.auth-visual__brand { position: absolute; z-index: 1; top: 44px; left: clamp(40px, 4vw, 96px); }
.auth-visual__brand img { display: block; width: 172px; height: auto; }
.auth-visual__intro { position: absolute; z-index: 1; top: 130px; left: clamp(40px, 4vw, 96px); max-width: 42%; }
.auth-visual__intro h2 { margin: 0; font-size: clamp(24px, 2.3vw, 34px); font-weight: 600; line-height: 1.4; letter-spacing: .01em; }
.auth-visual__intro p { margin: 10px 0 0; font-size: 14px; line-height: 1.7; color: #e0edef; }
.auth-visual__caption { position: absolute; z-index: 1; bottom: 30px; left: clamp(40px, 4vw, 96px); margin: 0; font-size: 12px; color: #e0edef; }
.auth-visual__caption span { padding: 0 12px; opacity: .65; }
.auth-entry { position: relative; display: flex; flex-direction: column; align-items: center; min-width: 0; width: 440px; max-width: 100%; margin-left: auto; }
.auth-entry__content { width: 100%; padding: 36px; border: 1px solid #e3e9ec; border-radius: 10px; background: var(--auth-paper); }
.auth-heading { margin-bottom: 30px; }
.auth-heading h1 { margin: 0; font-size: 30px; line-height: 1.4; font-weight: 600; letter-spacing: -.02em; }
.auth-heading p { margin: 10px 0 0; font-size: 14px; line-height: 1.7; color: var(--auth-muted); }
.auth-field + .auth-field { margin-top: 24px; }
.auth-field > label { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 9px; font-size: 14px; font-weight: 500; }
.auth-field > label span { font-size: 12px; font-weight: 400; color: var(--auth-muted); }
.auth-input { display: block; width: 100%; min-height: 48px; padding: 12px 14px; border: 1px solid var(--auth-line); border-radius: 6px; background: var(--auth-paper); color: var(--auth-ink); font: inherit; font-size: 15px; transition: border-color .15s; }
.auth-input::placeholder { color: #798992; opacity: 1; }
.auth-input:hover { border-color: #9dafb9; }
.auth-input:focus { outline: 2px solid #a6c5ce; outline-offset: 2px; border-color: var(--auth-accent); }
.auth-input[aria-invalid="true"] { border-color: #ba5148; }
.auth-password { position: relative; }
.auth-password .auth-input { padding-right: 48px; }
.auth-password__toggle { position: absolute; right: 2px; top: 2px; display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 4px; background: transparent; color: var(--auth-muted); cursor: pointer; }
.auth-password__toggle svg { width: 19px; height: 19px; }
.auth-password__toggle:hover { color: var(--auth-accent); background: #f2f6f7; }
.auth-field__hint { margin: 10px 0 0; color: var(--auth-muted); font-size: 12px; line-height: 1.8; }
.auth-options { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin: 18px 0 22px; font-size: 13px; }
.auth-remember { display: inline-flex; align-items: center; gap: 8px; min-height: 32px; color: #526873; cursor: pointer; }
.auth-remember input { width: 15px; height: 15px; margin: 0; accent-color: var(--auth-accent); }
.auth-help-trigger { min-height: 32px; padding: 0; border: 0; background: transparent; color: var(--auth-accent); font: inherit; cursor: pointer; }
.auth-help-trigger:hover { text-decoration: underline; text-underline-offset: 4px; }
.auth-help { margin: -8px 0 20px; color: var(--auth-muted); font-size: 13px; line-height: 1.8; }
.auth-feedback { margin: 0 0 14px; font-size: 13px; line-height: 1.7; color: #a43e35; }
.auth-submit { display: flex; align-items: center; justify-content: center; width: 100%; min-height: 48px; border: 1px solid var(--auth-accent); border-radius: 6px; background: var(--auth-accent); color: #fff; font: inherit; font-size: 15px; font-weight: 500; cursor: pointer; transition: background-color .15s; }
.auth-submit:hover:not(:disabled) { background: #1d5c71; border-color: #1d5c71; }
.auth-submit:active:not(:disabled) { background: #16495d; }
.auth-submit:disabled { cursor: wait; background: #567f8c; border-color: #567f8c; }
.auth-login button:focus-visible, .auth-remember input:focus-visible { outline: 2px solid var(--auth-accent); outline-offset: 4px; }
.auth-footer { margin-top: 22px; font-size: 12px; line-height: 1.8; color: #e7f0f2; text-align: center; }
@media (min-width: 1800px) { .auth-entry { width: 480px; } .auth-entry__content { padding: 44px; } }
@media (max-width: 1100px) and (min-width: 761px) {
  .auth-layout { padding: 190px 32px 64px; }
  .auth-visual__photo { object-position: 46% center; }
  .auth-visual__brand, .auth-visual__intro, .auth-visual__caption { left: 32px; }
  .auth-visual__intro { top: 112px; max-width: 55%; }
  .auth-visual__intro h2 { font-size: 23px; }
  .auth-entry { width: 380px; }
  .auth-entry__content { padding: 30px 26px; }
  .auth-visual__brand img { width: 152px; }
  .auth-visual__caption span { padding: 0 6px; }
}
@media (max-width: 760px) {
  .auth-layout { display: flex; align-items: center; padding: 186px 16px 28px; }
  .auth-visual__photo { object-position: 30% center; }
  .auth-visual::after { background: rgba(3, 20, 26, .24); }
  .auth-visual__brand { top: 28px; left: 24px; }
  .auth-visual__brand img { width: 130px; }
  .auth-visual__intro { top: 86px; left: 24px; right: 24px; max-width: none; }
  .auth-visual__intro h2 { font-size: 23px; }
  .auth-visual__intro p { margin-top: 6px; font-size: 13px; }
  .auth-visual__caption { display: none; }
  .auth-entry { width: 400px; margin: auto; }
  .auth-entry__content { padding: 28px 20px; border-radius: 8px; }
  .auth-heading { margin-bottom: 24px; }
  .auth-heading h1 { font-size: 26px; }
  .auth-input { font-size: 16px; }
  .auth-options { margin-top: 14px; }
  .auth-help-trigger, .auth-remember { min-height: 44px; }
  .auth-field > label span, .auth-field__hint { font-size: 13px; }
}
@media (prefers-reduced-motion: reduce) { .auth-input, .auth-submit { transition: none; } }
</style>
