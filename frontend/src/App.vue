<template>
  <LoginScreen />

  <div id="screen-app" class="app hidden">
    <header class="topbar">
      <div class="brand">
        <img class="brand-logo" src="/assets/jyd-logo.png" alt="竞业达" />
        <span class="brand-text">力学实验数字化平台</span>
      </div>
      <nav id="nav-teacher" class="topnav hidden" aria-label="教师导航">
        <button type="button" class="nav-item active" data-p="t-assign">下发任务</button>
        <button type="button" class="nav-item" data-p="t-grade">课堂记录与评阅</button>
        <button type="button" class="nav-item" data-p="t-students">学生名单</button>
      </nav>
      <nav id="nav-admin" class="topnav hidden" aria-label="管理导航">
        <button type="button" class="nav-item active" data-p="a-teachers">教师账号</button>
      </nav>
      <nav id="nav-student" class="topnav flow-nav hidden" aria-label="学生流程">
        <button type="button" class="nav-item active" data-p="s-home"><span class="fn-num">1</span>任务中心</button>
        <span class="fn-line" data-line="1"></span>
        <button type="button" class="nav-item" data-p="s-lab"><span class="fn-num">2</span>资料采集</button>
        <span class="fn-line" data-line="2"></span>
        <button type="button" class="nav-item" data-p="s-report"><span class="fn-num">3</span>实验报告</button>
      </nav>
      <div class="meta">
        <span class="who" id="top-who">—</span>
        <StudentAccountMenu />
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
import StudentAccountMenu from './StudentAccountMenu.vue'
import LoginScreen from './LoginScreen.vue'

onMounted(() => {
  bootApp()
})
</script>
