<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({ snapshot: Object, onRefresh: Function, onNavigate: Function })
const snapshot = ref(props.snapshot), busy = ref(false)
const student = computed(() => snapshot.value?.student || {})
const hasTask = computed(() => !!student.value.taskId && student.value.status !== 'none')
const scheduled = computed(() => student.value.lessonState === 'scheduled')
const submitted = computed(() => ['submitted', 'graded'].includes(student.value.personalReportState) || student.value.status === 'submitted')
const archived = computed(() => student.value.archived || ['lab_done', 'submitted'].includes(student.value.status))
const collectionComplete = computed(() => student.value.collectionComplete || archived.value)
const trialNames = { STEEL_TENS: '低碳钢拉伸', CAST_TENS: '铸铁拉伸', STEEL_COMP: '低碳钢压缩', CAST_COMP: '铸铁压缩' }
const trials = computed(() => (student.value.requiredTrials || []).map(id => trialNames[id] || id))
const note = computed(() => String(student.value.note || '').trim())
const tip = computed(() => String(student.value.tip || '').trim())
const mates = computed(() => snapshot.value?.mates || [])
const journeyLabels = ['任务中心', '资料采集', '实验报告', '提交完成']
const journeyIndex = computed(() => student.value.personalReportState === 'returned' ? 2 : submitted.value ? 3 : archived.value ? 2 : collectionComplete.value || student.value.status === 'in_lab' ? 1 : 0)
const state = computed(() => {
  const s = student.value
  if (scheduled.value) return { label: '待开课', action: s.scheduledStartAt ? '等待到点开放' : '等待教师开课', disabled: true }
  if (s.retakeRequest?.status === 'pending') return { label: '重做待处理', action: '查看操作记录', page: 's-lab' }
  if (s.personalReportState === 'returned') return { label: '报告待修改', message: s.personalComment, action: '修改个人报告', page: 's-report' }
  if (s.personalReportState === 'graded') return { label: '报告已评分', message: s.personalScore != null ? `个人报告成绩 ${s.personalScore} 分` : '', action: '查看批阅结果', page: 's-report' }
  if (submitted.value) return { label: '待教师批阅', action: '查看个人报告', page: 's-report' }
  if (archived.value) return { label: '待提交报告', action: '填写个人报告', page: 's-report' }
  if (collectionComplete.value) return { label: s.classOpen && s.teacherScoreConfirmationRequired !== false ? '待教师确认成绩' : '待提交操作记录', action: '查看操作记录', page: 's-lab' }
  return { label: s.status === 'assigned' ? '待开始采集' : '采集进行中', action: s.status === 'assigned' ? '开始资料采集' : '继续资料采集', page: 's-lab' }
})
const detailsDialog = ref(null), membersDialog = ref(null), detailsTitle = ref(''), detailsText = ref('')
async function openDetails(title, text) { detailsTitle.value = title; detailsText.value = text; await nextTick(); detailsDialog.value.showModal() }
let disposed = false, timer
function refreshWhenVisible() { if (!document.hidden) refresh() }
onMounted(() => {
  timer = setInterval(refreshWhenVisible, 15000)
  window.addEventListener('focus', refreshWhenVisible)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})
onUnmounted(() => {
  disposed = true; clearInterval(timer)
  window.removeEventListener('focus', refreshWhenVisible)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
async function refresh() {
  if (busy.value || disposed || !props.onRefresh) return
  busy.value = true
  try { const fresh = await props.onRefresh(); if (!disposed && fresh) snapshot.value = fresh }
  catch { /* Keep the current task available when a background update fails. */ }
  finally { if (!disposed) busy.value = false }
}
</script>

<template>
  <section class="task-center" aria-label="任务中心">
    <section v-if="!hasTask" class="student-waiting"><h1>暂无实验安排</h1></section>
    <template v-else>
      <section class="stu-hero-card" aria-label="本课次安排">
        <div class="stu-hero-left">
          <div class="stu-exp-badge">实验项目</div>
          <h1 class="stu-exp-name">{{ student.expName || '本课次任务' }}</h1>
          <dl class="stu-exp-meta"><div><dt>时间</dt><dd>{{ student.timeText?.replace('T', ' ') || '待定' }}</dd></div><div><dt>地点</dt><dd>{{ student.place || '待定' }}</dd></div><div><dt>小组</dt><dd>{{ student.groupName || '待安排' }}</dd></div></dl>
          <p v-if="trials.length" class="task-center__trials">{{ trials.join(' · ') }}</p>
        </div>
        <div class="stu-hero-right">
          <div class="stu-status-badge" :class="student.status"><span class="ssb-dot" aria-hidden="true"></span><span class="ssb-text">{{ state.label }}</span></div>
          <button type="button" class="btn btn-primary btn-lg task-center__primary" :disabled="state.disabled" @click="onNavigate(state.page)">{{ state.action }}</button>
          <p v-if="state.message" class="task-center__feedback"><span>{{ state.message }}</span><button v-if="student.personalReportState === 'returned'" type="button" @click="openDetails('退回意见', state.message)">查看意见</button></p>
        </div>
      </section>
      <section class="card dash-card task-center__journey" aria-label="实验进度"><ol class="journey-bar"><li v-for="(label, index) in journeyLabels" :key="label" class="journey-step" :class="{ 'is-done': index < journeyIndex, 'is-on': index === journeyIndex }" :aria-current="index === journeyIndex ? 'step' : undefined"><span class="jn" aria-hidden="true">{{ index + 1 }}</span>{{ label }}</li></ol></section>
      <div class="task-center__panels">
        <section class="card dash-card task-center__members" aria-label="小组成员">
          <header class="dash-card-head"><h2 class="dash-card-title"><span class="title-dot" aria-hidden="true"></span>小组成员 <small>{{ mates.length }} 人</small></h2><button v-if="mates.length > 4" type="button" class="task-center__link" @click="membersDialog.showModal()">查看全部</button></header>
          <ul v-if="mates.length" class="mate-grid"><li v-for="member in mates.slice(0, 4)" :key="member.sid" class="mate-chip"><span class="mate-avatar" aria-hidden="true">{{ member.name?.charAt(0) || '同' }}</span><div class="task-center__member-info"><strong>{{ member.name }}<small v-if="member.sid === student.sid">本人</small></strong><span>{{ member.sid }}</span><span v-if="member.cls">{{ member.cls }}</span></div></li></ul>
          <p v-else class="task-center__muted">暂无小组成员信息</p>
        </section>
        <section class="card dash-card task-center__instructions" aria-label="备注与实验说明">
          <article class="task-center__note"><header><h2>备注</h2><button v-if="note" type="button" class="task-center__link" @click="openDetails('备注', note)">查看全文</button></header><p v-if="note" class="task-center__preview">{{ note }}</p><p v-else class="task-center__muted">教师暂未填写备注</p></article>
          <article class="task-center__explanation"><header><h2>实验说明</h2><button v-if="tip" type="button" class="task-center__link" @click="openDetails('实验说明', tip)">查看全文</button></header><p v-if="tip" class="task-center__preview">{{ tip }}</p><p v-else class="task-center__muted">教师暂未填写实验说明</p></article>
        </section>
      </div>
    </template>
    <dialog ref="detailsDialog" class="task-center__dialog" aria-labelledby="task-details-title"><header><h2 id="task-details-title">{{ detailsTitle }}</h2><button type="button" class="task-center__close" aria-label="关闭全文" @click="detailsDialog.close()">×</button></header><div class="task-center__full-text">{{ detailsText }}</div><footer><button type="button" class="btn btn-ghost" @click="detailsDialog.close()">关闭</button></footer></dialog>
    <dialog ref="membersDialog" class="task-center__dialog" aria-labelledby="task-members-title"><header><h2 id="task-members-title">小组成员 · {{ mates.length }} 人</h2><button type="button" class="task-center__close" aria-label="关闭小组成员" @click="membersDialog.close()">×</button></header><ul class="task-center__all-members"><li v-for="member in mates" :key="member.sid"><strong>{{ member.name }}<small v-if="member.sid === student.sid">本人</small></strong><span>{{ member.sid }}</span><span>{{ [member.major, member.cls].filter(Boolean).join(' · ') }}</span></li></ul><footer><button type="button" class="btn btn-ghost" @click="membersDialog.close()">关闭</button></footer></dialog>
  </section>
</template>

<style scoped>
:global(.stage:has(> .task-center)) { height: 100%; min-height: 0; }
.task-center { height: 100%; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr); gap: 14px; color: #263d49; font-size: 14px; line-height: 1.65; }
.task-center .stu-hero-card { grid-template-columns: minmax(0, 1.7fr) minmax(240px, 1fr); padding: 20px 26px; gap: 28px; margin: 0; border-radius: 14px; }
.task-center .stu-exp-name { margin: 0 0 12px; font-size: 28px; line-height: 1.35; overflow-wrap: anywhere; }
.task-center .stu-exp-badge { margin-bottom: 8px; padding: 2px 8px; }
.task-center .stu-exp-meta { margin: 0; gap: 7px 24px; opacity: 1; }
.task-center .stu-exp-meta > div { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.task-center .stu-exp-meta dt { color: #cce6e9; font-size: 13px; flex-shrink: 0; }
.task-center .stu-exp-meta dd { margin: 0; overflow-wrap: anywhere; }
.task-center__trials { margin: 10px 0 0; font-size: 12px; color: #dceef0; }
.task-center .stu-hero-right { min-width: 0; }
.task-center .stu-status-badge { margin-bottom: 12px; padding: 6px 12px; }
.task-center__primary { min-height: 40px; border-radius: 8px; font-size: 15px; }
.task-center__primary:focus-visible { outline: 2px solid #fff; outline-offset: 4px; }
.task-center__primary:disabled { cursor: default; opacity: .7; }
.task-center__feedback { margin: 8px 0 0; display: flex; justify-content: flex-end; gap: 8px; color: #fff; font-size: 13px; }
.task-center__feedback > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-center__feedback button { border: 0; background: transparent; padding: 0; font: inherit; text-decoration: underline; color: #fff; cursor: pointer; flex-shrink: 0; }
.task-center .dash-card { margin: 0; padding: 18px 20px; min-width: 0; min-height: 0; border-radius: 10px; }
.task-center .task-center__journey { padding: 10px 14px; }
.task-center .journey-bar { list-style: none; padding: 0; margin: 0; }
.task-center .journey-step { min-width: 0; padding: 10px; font-size: 13px; flex-direction: row; gap: 10px; }
.task-center__panels { min-height: 0; display: grid; grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr); gap: 16px; }
.task-center .dash-card-head { margin: 0 0 14px; gap: 12px; }
.task-center .dash-card-title { margin: 0; font-size: 16px; }
.task-center .dash-card-title small { margin-left: 10px; font-size: 13px; font-weight: 400; color: #647985; }
.task-center .mate-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); list-style: none; margin: 0; padding: 0; gap: 10px; }
.task-center .mate-chip { padding: 10px 12px; gap: 10px; align-items: center; }
.task-center .mate-chip:hover { transform: none; }
.task-center .mate-avatar { width: 32px; height: 32px; }
.task-center__member-info { min-width: 0; }
.task-center__member-info strong { display: block; font-size: 14px; font-weight: 550; }
.task-center__member-info strong small { margin-left: 8px; font-size: 12px; font-weight: 400; color: #3d7b69; }
.task-center .task-center__member-info > span { display: block; color: #647985; font-size: 12px; overflow-wrap: anywhere; }
.task-center__instructions { display: grid; grid-template-rows: repeat(2, minmax(0, 1fr)); gap: 16px; }
.task-center__instructions article { min-height: 0; display: flex; flex-direction: column; padding: 12px 14px; border: 1px solid #dce7eb; border-radius: 6px; background: #f2f7f8; }
.task-center__instructions header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 8px; flex-shrink: 0; }
.task-center__instructions h2 { margin: 0; font-size: 15px; font-weight: 600; }
.task-center__instructions p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.task-center__preview { min-height: 0; overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 5; line-height: 1.8; }
.task-center__link { padding: 0; border: 0; background: none; color: #316d7c; font: inherit; font-size: 13px; cursor: pointer; flex-shrink: 0; }
.task-center__link:hover { text-decoration: underline; }
.task-center__link:focus-visible { outline: 2px solid #2a7684; outline-offset: 4px; }
.task-center__muted { margin: 0; color: #647985; font-size: 13px; }
.task-center .student-waiting h1 { margin: 0; font-size: 22px; }
.task-center__dialog { width: min(680px, calc(100vw - 32px)); max-height: 82dvh; border: 1px solid #d9e4e9; border-radius: 12px; padding: 0; color: #263d49; }
.task-center__dialog::backdrop { background: #18354170; }
.task-center__dialog > header, .task-center__dialog > footer { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 22px; }
.task-center__dialog h2 { margin: 0; font-size: 18px; }
.task-center__dialog > footer { justify-content: flex-end; }
.task-center__close { border: 0; background: transparent; color: #617581; padding: 0 6px; font-size: 26px; cursor: pointer; }
.task-center__full-text { padding: 6px 22px 16px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; }
.task-center__all-members { list-style: none; margin: 0; padding: 0 22px; }
.task-center__all-members li { display: grid; grid-template-columns: 100px 120px minmax(0, 1fr); gap: 12px; padding: 12px 0; border-top: 1px solid #edf1f3; overflow-wrap: anywhere; }
.task-center__all-members small { margin-left: 8px; font-weight: 400; color: #3d7b69; }
@media (max-width: 1000px), (max-height: 650px) {
  :global(.stage:has(> .task-center)) { height: auto; }
  .task-center { height: auto; grid-template-rows: auto; }
  .task-center__panels { grid-template-columns: minmax(0, 1fr); }
  .task-center__instructions { grid-template-rows: auto auto; }
  .task-center__preview { display: block; overflow: visible; }
}
@media (max-width: 760px) { .task-center .stu-hero-card { grid-template-columns: minmax(0, 1fr); padding: 22px; gap: 20px; } .task-center .stu-hero-right { text-align: left; } .task-center .stu-exp-name { font-size: 25px; } .task-center .journey-step { flex-direction: column; font-size: 12px; padding: 10px 4px; gap: 5px; } }
@media (max-width: 480px) { .task-center .stu-hero-card { padding: 20px 18px; } .task-center .stu-exp-meta { flex-direction: column; } .task-center .mate-grid { grid-template-columns: minmax(0, 1fr); } .task-center .dash-card { padding: 16px; } .task-center .task-center__journey { padding: 10px; } .task-center__all-members li { grid-template-columns: 1fr 1fr; } .task-center__all-members li > span:last-child { grid-column: 1 / -1; } }
</style>
