<template>
  <div id="screen-login" class="login-screen">
    <div class="login-wrap">
      <div class="login-hero">
        <div class="login-hero-bg"></div>
        <div class="login-hero-logo-wrap">
          <img class="login-hero-logo-img" src="/assets/jyd-logo-white.png" alt="竞业达" />
        </div>
      </div>
      <div class="login-form-side">
        <div class="login-card">
          <div class="login-card-head">
            <h3>欢迎回来 👋</h3>
            <p>请使用教师账号或学号登录系统</p>
          </div>
          <div class="login-field">
            <label class="field-label" for="login-account">账号</label>
            <input id="login-account" class="inp" autocomplete="username" placeholder="教师账号或学号" />
          </div>
          <div class="login-field">
            <label class="field-label" for="login-password">密码</label>
            <input id="login-password" class="inp" type="password" autocomplete="current-password" placeholder="请输入密码" />
          </div>
          <div class="login-options">
            <label class="login-remember">
              <input type="checkbox" id="login-remember" />
              <span>记住账号</span>
            </label>
            <button type="button" class="linkish" id="btn-forgot">忘记密码？</button>
          </div>
          <button type="button" class="btn btn-primary btn-login" id="btn-login">登录</button>
          <div class="login-foot-note">
            登录即表示同意 <a href="#">服务条款</a> 和 <a href="#">隐私政策</a>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div id="screen-app" class="app hidden">
    <header class="topbar">
      <div class="brand">
        <img class="brand-logo" src="/assets/jyd-logo.png" alt="竞业达" />
        <span class="brand-text">力学实验数字化平台</span>
      </div>
      <nav id="nav-teacher" class="topnav hidden" aria-label="教师导航">
        <button type="button" class="nav-item active" data-p="t-assign">下发任务</button>
        <button type="button" class="nav-item" data-p="t-grade">课堂记录与评阅</button>
      </nav>
      <nav id="nav-student" class="topnav flow-nav hidden" aria-label="学生流程">
        <button type="button" class="nav-item active" data-p="s-home"><span class="fn-num">1</span>任务中心</button>
        <span class="fn-line" data-line="1"></span>
        <button type="button" class="nav-item" data-p="s-lab"><span class="fn-num">2</span>现场实验</button>
        <span class="fn-line" data-line="2"></span>
        <button type="button" class="nav-item" data-p="s-report"><span class="fn-num">3</span>实验报告</button>
      </nav>
      <div class="meta">
        <span class="who" id="top-who">—</span>
        <button type="button" class="linkish" id="btn-logout">退出</button>
      </div>
    </header>
    <main class="main"><div class="stage" id="pages"></div></main>
    <footer class="app-foot hidden" id="app-foot">
      <span>力学实验数字化平台</span>
      <span class="app-foot-sep">·</span>
      <span id="foot-role">—</span>
    </footer>
  </div>

  <div id="modal-mask" class="modal-mask hidden">
    <div class="modal" id="modal-box">
      <h3 id="modal-title">提示</h3>
      <div id="modal-body"></div>
      <div class="modal-actions" id="modal-actions"></div>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { bootApp } from './legacy/app.js'

onMounted(() => {
  bootApp()
})
</script>
