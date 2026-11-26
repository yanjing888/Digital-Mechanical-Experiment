<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api.js'
import ToastNotice from './ToastNotice.vue'
const props = defineProps({ snapshot: Object, onRefresh: Function, manage: Boolean, askPassword: Function })
const snap = ref(props.snapshot)
const major = ref(''), cls = ref(''), query = ref(''), status = ref('')
const statusFilterButton = ref(null), statusFilterPopover = ref(null)
const statusOptions = [{ value: '', label: '全部学生' }, { value: 'assigned', label: '已安排' }, { value: 'unassigned', label: '未安排' }]
const statusLabel = computed(() => statusOptions.find(option => option.value === status.value)?.label)
const statusFilterId = computed(() => props.manage ? 'roster-status-filter' : 'assignment-status-filter')
function positionStatusFilter() {
  const rect = statusFilterButton.value.getBoundingClientRect()
  Object.assign(statusFilterPopover.value.style, {
    left: `${Math.max(12, Math.min(rect.right - 176, window.innerWidth - 188))}px`,
    top: `${Math.max(12, Math.min(rect.bottom + 8, window.innerHeight - 214))}px`,
  })
}
function chooseStatus(value) {
  status.value = value
  statusFilterPopover.value.hidePopover()
  statusFilterButton.value.focus()
}
const selected = ref(new Set()), page = ref(1), busy = ref(false), message = ref(''), error = ref('')
const pageSize = ref(10)
const visiblePassword = ref(null)
const size = ref(4), preview = ref(null), groups = ref([]), groupEdited = ref(false)
const form = reactive({ expId: 'TENSION_COMPRESSION', timeText: '', place: '', note: '', tip: '', requiredTrials: [] })
const notesDialog = ref(null)
const notesDraft = reactive({ note: '', tip: '' })
function editNotes() {
  notesDraft.note = form.note
  notesDraft.tip = form.tip
  notesDialog.value.showModal()
}
function saveNotes() {
  form.note = notesDraft.note.trim()
  form.tip = notesDraft.tip.trim()
  notesDialog.value.close()
}
const trialNames = { STEEL_TENS:'低碳钢拉伸', CAST_TENS:'铸铁拉伸', STEEL_COMP:'低碳钢压缩', CAST_COMP:'铸铁压缩' }
const roster = computed(() => {
  const ids = new Set(snap.value.meta?.importedStudentIds || [])
  return (snap.value.meta?.students || []).filter(s => ids.has(s.sid)).sort((a,b) => a.sid.localeCompare(b.sid))
})
const majors = computed(() => [...new Set(roster.value.map(s => s.major || '未设置专业'))].sort())
const classes = computed(() => [...new Set(roster.value.filter(s => !major.value || (s.major || '未设置专业') === major.value).map(s => s.cls || '未设置班级'))].sort())
const filtered = computed(() => roster.value.filter(s => (!major.value || (s.major || '未设置专业') === major.value)
  && (!cls.value || (s.cls || '未设置班级') === cls.value)
  && (!status.value || (status.value === 'unassigned' ? !s.taskId : !!s.taskId))
  && (!query.value.trim() || [s.sid,s.name,s.major,s.cls].join(' ').toLowerCase().includes(query.value.trim().toLowerCase()))))
const firstRow = computed(() => (page.value - 1) * pageSize.value)
const hasFilters = computed(() => Boolean(major.value || cls.value || query.value || status.value))
const rows = computed(() => filtered.value.slice(firstRow.value, firstRow.value + pageSize.value))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const allSelected = computed(() => filtered.value.length > 0 && filtered.value.every(s => selected.value.has(s.sid)))
const someSelected = computed(() => filtered.value.some(s => selected.value.has(s.sid)) && !allSelected.value)
const chosen = computed(() => roster.value.filter(s => selected.value.has(s.sid)))
const hiddenCount = computed(() => chosen.value.filter(s => !filtered.value.some(f => f.sid === s.sid)).length)
const tasks = computed(() => (snap.value.tasks || []).filter(t => t.teacher === snap.value.teacherName))
const taskStates = { scheduled: '待开课', active: '进行中', finished: '已结束' }
const taskStateFilter = ref('')
const taskPage = ref(1)
const taskPageSize = ref(10)
const taskRows = computed(() => tasks.value.filter(task => !taskStateFilter.value || task.lessonState === taskStateFilter.value))
const canStartSomeTasks = computed(() => taskRows.value.some(task => task.lessonState === 'scheduled'))
const taskPages = computed(() => Math.max(1, Math.ceil(taskRows.value.length / taskPageSize.value)))
const taskOffset = computed(() => (taskPage.value - 1) * taskPageSize.value)
const visibleTasks = computed(() => taskRows.value.slice(taskOffset.value, taskOffset.value + taskPageSize.value))
const membersDialog = ref(null)
const memberTask = ref(null)
const memberGroups = computed(() => [...(memberTask.value?.groups || [])]
  .sort((a,b) => a.name.localeCompare(b.name, 'zh', { numeric: true })))
watch([taskStateFilter, taskPageSize], () => { taskPage.value = 1 })
watch(taskPages, count => { taskPage.value = Math.min(taskPage.value, count) })
function showMembers(task) {
  memberTask.value = task
  membersDialog.value.showModal()
}
const selectedClasses = computed(() => new Set(chosen.value.map(s => [s.major,s.cls].join('\0'))).size)
watch(major, () => { cls.value='' })
watch([major,cls,query,status,pageSize], () => { page.value=1; visiblePassword.value=null })
watch(pages, count => { page.value = Math.min(page.value, count) }, { flush: 'sync' })
watch(page, () => { visiblePassword.value=null })
watch([selected,size], () => { groupEdited.value=false; buildGroups() }, { deep: true })
function buildGroups() {
  const buckets = new Map()
  chosen.value.forEach(s => { const key=[s.major,s.cls].join('\0'); if (!buckets.has(key)) buckets.set(key,[]); buckets.get(key).push(s) })
  const result=[]
  const n=Math.max(1,Math.min(50,Number(size.value)||4))
  for (const members of buckets.values()) {
    // 均衡分组，避免最后一个小组只剩一人。
    const count=Math.ceil(members.length/n), base=Math.floor(members.length/count), extra=members.length%count
    let at=0
    for(let i=0;i<count;i++) { const length=base+(i<extra?1:0); result.push(members.slice(at,at+length)); at+=length }
  }
  groups.value=result
}
function autoGroup() {
  buildGroups()
  groupEdited.value = false
  preview.value.showModal()
}
function toggle(sid) { const next=new Set(selected.value); next.has(sid)?next.delete(sid):next.add(sid); selected.value=next }
function toggleAll() { const next=new Set(selected.value); filtered.value.forEach(s => allSelected.value?next.delete(s.sid):next.add(s.sid)); selected.value=next }
function move(sid,index) {
  let member
  groups.value.forEach(g => { const at=g.findIndex(s=>s.sid===sid); if(at>=0) member=g.splice(at,1)[0] })
  if (index === groups.value.length) groups.value.push([])
  groups.value[index].push(member)
  groups.value=groups.value.filter(g=>g.length)
  groupEdited.value=true
}
async function refresh() {
  snap.value = await api.session()
  props.onRefresh?.(snap.value)
  visiblePassword.value = null
  if (major.value && !majors.value.includes(major.value)) major.value = ''
  if (cls.value && !classes.value.includes(cls.value)) cls.value = ''
  const currentIds = new Set(roster.value.map(student => student.sid))
  const kept = [...selected.value].filter(sid => currentIds.has(sid))
  if (kept.length !== selected.value.size) selected.value = new Set(kept)
}
async function run(action) {
  if (busy.value) return
  busy.value=true; error.value=''; message.value=''
  try { await action() } catch(e) { error.value=e.message } finally { busy.value=false }
}
function dispatch() {
  run(async () => {
    if (!chosen.value.length) throw new Error('请选择学生')
    if (!form.requiredTrials.length) throw new Error('请选择本课次试验')
    await api.createTask({ ...form, timeText:form.timeText.replace('T',' '), studentGroups:groups.value.map(g=>g.map(s=>s.sid)) })
    const count=chosen.value.length
    selected.value=new Set(); groups.value=[]; groupEdited.value=false
    await refresh(); message.value=`已向 ${count} 名学生下发安排`
  })
}
function start(task) { run(async () => { await api.startTask(task.id); await refresh(); message.value='已开始本课次实验' }) }
function resetFilters() {
  query.value = major.value = cls.value = status.value = ''
  page.value = 1
}
function refreshRoster() {
  run(refresh)
}
function uploadRoster(event) {
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  run(async () => {
    const result = await api.uploadRoster(file)
    await refresh()
    resetFilters()
    message.value = `新增 ${result.created} 人，更新 ${result.updated} 人`
  })
}
function togglePassword(student) {
  if (visiblePassword.value?.sid === student.sid) {
    visiblePassword.value = null
    return
  }
  run(async () => {
    visiblePassword.value = null
    const result = await api.studentPassword(student.sid)
    if (!result.available) throw new Error(result.message)
    if (rows.value.some(row => row.sid === student.sid)) {
      visiblePassword.value = { sid: student.sid, value: result.password }
    }
  })
}
function resetPassword(student) {
  run(async () => {
    await api.resetStudentPassword(student.sid)
    visiblePassword.value = null
    message.value = `${student.name}的密码已恢复为初始密码（完整学号）`
  })
}
</script>

<template>
  <div class="roster-assignment" :class="{'ra-page--management': manage}" :aria-busy="busy">
    <ToastNotice :message="error || message" :type="error ? 'error' : 'success'" @close="error='';message=''" />
    <div class="ra-workspace" :class="{'ra-workspace--assignment': !manage}">
      <section class="ra-roster" :class="{'ra-management': manage}" :aria-label="manage ? '学生名单' : '选择学生'">
        <header class="ra-heading ra-roster-heading">
          <h2>{{ manage ? '学生名单' : '选择学生' }}</h2>
          <div v-if="manage" class="ra-actions">
            <button type="button" class="btn btn-ghost" :disabled="busy" @click="run(()=>api.downloadRosterTemplate())">下载模板</button>
            <label class="btn btn-primary ra-upload">上传名单<input type="file" accept=".xlsx,.xls" :disabled="busy" @change="uploadRoster"></label>
          </div>
        </header>
        <div class="ra-filterbar">
          <div class="ra-filters">
          <input v-model="query" type="search" aria-label="搜索学生" placeholder="搜索姓名、学号">
          <select v-model="major" aria-label="专业"><option value="">全部专业</option><option v-for="m in majors" :key="m">{{m}}</option></select>
          <select v-model="cls" aria-label="班级"><option value="">全部班级</option><option v-for="c in classes" :key="c">{{c}}</option></select>
          </div>
          <div class="ra-filter-actions">
            <button v-if="status" type="button" class="ra-active-filter" :disabled="busy" :aria-label="'清除安排状态筛选：' + statusLabel" @click="status=''">{{statusLabel}}<span aria-hidden="true">×</span></button>
            <button v-if="hasFilters" type="button" class="ra-text-action" :disabled="busy" @click="resetFilters">重置</button>
            <button type="button" class="ra-refresh" :disabled="busy" aria-label="刷新名单" title="刷新名单" @click="refreshRoster"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.2 12a7.2 7.2 0 1 0-1.8 4.8M20 12l-2.6-5"/></svg></button>
          </div>
        </div>
        <div v-if="selected.size" class="ra-selection">
          <span>已选 <b>{{selected.size}}</b> 人 <small v-if="hiddenCount"> · 含其他筛选 {{hiddenCount}} 人</small></span>
          <button type="button" class="ra-text-action" :disabled="busy" @click="selected=new Set()">清空选择</button>
        </div>
        <div class="ra-table-scroll">
          <table class="ra-table">
            <thead><tr>
              <th scope="col">
                <label class="ra-student-cell">
                  <input v-if="!manage" type="checkbox" aria-label="选择当前筛选全部学生" :checked="allSelected" :indeterminate="someSelected" :disabled="!filtered.length || busy" @change="toggleAll">
                  姓名
                </label>
              </th>
              <th scope="col">{{ manage ? '学号 / 登录账号' : '学号' }}</th>
              <th scope="col">专业</th><th scope="col">班级</th><th scope="col" class="ra-centered"><div class="ra-status-heading"><span>实验安排</span><button ref="statusFilterButton" type="button" class="ra-status-trigger" :class="{ 'is-filtered': status }" :popovertarget="statusFilterId" :aria-label="'筛选实验安排：' + statusLabel" :title="status ? '当前筛选：' + statusLabel : '筛选实验安排'" :disabled="busy" @click="positionStatusFilter"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 5h16l-6 7v6l-4 2v-8z"/></svg></button></div></th>
              <th v-if="manage" scope="col" class="ra-centered">密码管理</th>
            </tr></thead>
            <tbody>
              <tr v-for="s in rows" :key="s.sid" :class="{selected:selected.has(s.sid), 'ra-selectable':!manage}" @click="!manage && !busy && toggle(s.sid)">
                <td>
                  <div class="ra-student-cell">
                    <input v-if="!manage" type="checkbox" :aria-label="'选择' + s.name" :checked="selected.has(s.sid)" :disabled="busy" @click.stop @change="toggle(s.sid)">
                    <strong>{{s.name}}</strong>
                  </div>
                </td>
                <td class="ra-numeric">{{s.sid}}</td><td>{{s.major || '—'}}</td><td>{{s.cls || '—'}}</td>
                <td class="ra-centered"><span class="ra-task-state" :data-state="s.taskId ? 'active' : 'finished'">{{s.taskId ? '已安排' : '未安排'}}</span></td>
                <td v-if="manage" class="ra-centered">
                  <div class="ra-row-actions">
                    <button type="button" class="ra-text-action" :disabled="busy" :aria-expanded="visiblePassword?.sid === s.sid" :aria-label="(visiblePassword?.sid === s.sid ? '隐藏' : '查看') + s.name + '的密码'" @click="togglePassword(s)">{{visiblePassword?.sid === s.sid ? '隐藏' : '查看'}}</button>
                    <button type="button" class="ra-text-action" :disabled="busy" :aria-label="'将' + s.name + '的密码重置为完整学号'" title="恢复初始密码（完整学号）" @click="resetPassword(s)">重置</button>
                  </div>
                  <div v-if="visiblePassword?.sid === s.sid" class="student-password-value">{{visiblePassword.value}}</div>
                </td>
              </tr>
              <tr v-if="!rows.length"><td :colspan="manage ? 6 : 5" class="ra-empty">{{roster.length ? '无匹配学生' : '暂无学生名单'}}</td></tr>
            </tbody>
          </table>
        </div>
        <footer v-if="!manage" class="ra-pagination">
          <span v-if="pages > 1">{{firstRow + 1}}–{{firstRow + rows.length}} / {{filtered.length}} 人</span>
          <span v-else>共 {{filtered.length}} 人</span>
          <div>
            <label>每页 <select v-model.number="pageSize" aria-label="每页人数"><option v-for="n in [10,20,50]" :key="n" :value="n">{{n}} 人</option></select></label>
            <template v-if="pages > 1">
              <button type="button" aria-label="上一页" :disabled="page<=1" @click="page--">‹</button>
              <span aria-live="polite">{{page}} / {{pages}}</span>
              <button type="button" aria-label="下一页" :disabled="page>=pages" @click="page++">›</button>
            </template>
          </div>
        </footer>
      </section>
      <form v-if="!manage" class="ra-plan" aria-labelledby="arrangement-title" @submit.prevent="dispatch">
        <h2 id="arrangement-title">安排实验</h2>
        <div class="ra-plan-count"><strong>{{chosen.length}}</strong> 人 <span>{{selectedClasses}} 个班级 · {{groups.length}} 个小组</span></div>
        <label>实验项目<select v-model="form.expId"><option value="TENSION_COMPRESSION">拉伸压缩实验</option></select></label>
        <fieldset><legend>本课次试验</legend><div class="ra-trials"><label v-for="(name,id) in trialNames" :key="id"><input type="checkbox" v-model="form.requiredTrials" :value="id">{{name}}</label></div></fieldset>
        <div class="ra-form-pair"><label>每组人数<select v-model.number="size"><option v-for="n in [1,2,3,4,5,6,8,10]" :key="n" :value="n">{{n}} 人</option></select></label><button type="button" class="btn btn-ghost ra-preview" :disabled="!chosen.length || busy" aria-haspopup="dialog" @click="autoGroup">自动分组</button></div>
        <button type="button" class="ra-text-action" :disabled="!chosen.length || busy" aria-haspopup="dialog" @click="preview.showModal()">{{groupEdited?'查看已调整分组':'预览 / 调整分组'}}</button>
        <div class="ra-form-pair ra-schedule">
          <label>实验时间<div class="ra-date" :class="{'is-empty':!form.timeText}"><span v-if="!form.timeText">选择日期与时间</span><input v-model="form.timeText" type="datetime-local" aria-label="实验时间" @click="$event.target.showPicker?.()"></div></label>
          <label>地点<input v-model="form.place" placeholder="实验地点" maxlength="255"></label>
        </div>
        <div class="ra-notes-entry">
          <button type="button" class="btn btn-ghost" aria-haspopup="dialog" :disabled="busy" @click="editNotes">备注与实验说明</button>
          <span v-if="form.note || form.tip">已填写</span>
        </div>
        <button class="btn btn-primary ra-dispatch" type="submit" :disabled="!chosen.length || busy || !form.requiredTrials.length">{{busy?'处理中…':`下发安排${chosen.length ? ' · '+chosen.length+' 人' : ''}`}}</button>
      </form>
    </div>
    <Teleport to="body">
    <footer v-if="manage" class="ra-pagination ra-pagination--page-bottom" aria-label="学生名单分页">
      <span v-if="pages > 1">{{firstRow + 1}}–{{firstRow + rows.length}} / {{filtered.length}} 人</span>
      <span v-else>共 {{filtered.length}} 人</span>
      <div>
        <label>每页 <select v-model.number="pageSize" aria-label="每页人数"><option v-for="n in [10,20,50]" :key="n" :value="n">{{n}} 人</option></select></label>
        <template v-if="pages > 1">
          <button type="button" aria-label="上一页" :disabled="page<=1" @click="page--">‹</button>
          <span aria-live="polite">{{page}} / {{pages}}</span>
          <button type="button" aria-label="下一页" :disabled="page>=pages" @click="page++">›</button>
        </template>
      </div>
    </footer>
    </Teleport>
    <div :id="statusFilterId" ref="statusFilterPopover" popover class="ra-status-popover" aria-label="实验安排筛选">
      <p>实验安排</p>
      <div role="group" aria-label="安排状态">
        <button v-for="option in statusOptions" :key="option.value" type="button" :aria-pressed="status === option.value" :class="{ selected: status === option.value }" :disabled="busy" @click="chooseStatus(option.value)"><span>{{option.label}}</span><svg v-if="status === option.value" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m4 10 4 4 8-8"/></svg></button>
      </div>
    </div>
    <section v-if="!manage" class="ra-tasks">
      <header class="ra-heading">
        <h2>已下发 <span>{{tasks.length}}</span></h2>
        <div class="ra-actions">
          <select id="task-state-filter" v-model="taskStateFilter" aria-label="筛选任务状态">
            <option value="">全部状态</option>
            <option v-for="(label,key) in taskStates" :key="key" :value="key">{{label}}</option>
          </select>
          <button type="button" class="ra-refresh" :disabled="busy" aria-label="刷新已下发任务" title="刷新已下发任务" @click="run(refresh)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.2 12a7.2 7.2 0 1 0-1.8 4.8M20 12l-2.6-5"/></svg></button>
        </div>
      </header>
      <div class="ra-table-scroll">
        <table class="ra-table">
          <thead><tr>
            <th scope="col">实验项目</th>
            <th scope="col">下发日期</th>
            <th scope="col">实验时间 / 地点</th>
            <th scope="col" class="ra-centered">学生 / 小组</th>
            <th scope="col" class="ra-centered">状态</th>
            <th v-if="canStartSomeTasks" scope="col" class="ra-centered">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="task in visibleTasks" :key="task.id">
              <td><strong>{{task.expName}}</strong></td>
              <td class="ra-numeric">{{task.createdAt?.slice(0,10) || '—'}}</td>
              <td>{{task.timeText || task.place || '—'}}<small v-if="task.timeText && task.place">{{task.place}}</small></td>
              <td class="ra-numeric ra-centered">
                <button type="button" class="ra-text-action ra-members-link" aria-haspopup="dialog" :aria-label="task.expName + '，查看组员'" @click="showMembers(task)">
                  {{task.students?.length || 0}} 人 / {{task.groups?.length || task.groupIds?.length || 0}} 组
                </button>
              </td>
              <td class="ra-centered"><span class="ra-task-state" :data-state="task.lessonState">{{taskStates[task.lessonState] || '进行中'}}</span></td>
              <td v-if="canStartSomeTasks" class="ra-centered">
                <button v-if="task.lessonState==='scheduled'" type="button" class="ra-text-action" :disabled="busy" @click="start(task)">开始实验</button>
              </td>
            </tr>
            <tr v-if="!visibleTasks.length"><td :colspan="canStartSomeTasks ? 6 : 5" class="ra-empty">{{taskStateFilter ? '当前状态下暂无任务' : '暂无实验安排'}}</td></tr>
          </tbody>
        </table>
      </div>
      <footer class="ra-pagination">
        <span v-if="taskPages > 1">{{taskOffset + 1}}–{{taskOffset + visibleTasks.length}} / {{taskRows.length}} 项</span>
        <span v-else>共 {{taskRows.length}} 项</span>
        <div>
          <label>每页 <select v-model.number="taskPageSize" aria-label="已下发任务每页条数"><option v-for="n in [10,20,50]" :key="n" :value="n">{{n}} 条</option></select></label>
          <template v-if="taskPages > 1">
          <button type="button" aria-label="已下发任务上一页" :disabled="taskPage<=1" @click="taskPage--">‹</button>
          <span aria-live="polite">{{taskPage}} / {{taskPages}}</span>
          <button type="button" aria-label="已下发任务下一页" :disabled="taskPage>=taskPages" @click="taskPage++">›</button>
          </template>
        </div>
      </footer>
    </section>
    <dialog v-if="!manage" ref="notesDialog" class="ra-dialog ra-notes-dialog" aria-labelledby="assignment-notes-title">
      <header>
        <h2 id="assignment-notes-title">备注与实验说明</h2>
        <button type="button" aria-label="关闭备注编辑" @click="notesDialog.close()">×</button>
      </header>
      <form id="assignment-notes-form" class="ra-notes-fields" @submit.prevent="saveNotes">
        <label>备注<input v-model="notesDraft.note" autofocus></label>
        <label>实验说明<textarea v-model="notesDraft.tip" rows="5"></textarea></label>
      </form>
      <footer>
        <button type="button" class="btn btn-ghost" @click="notesDialog.close()">取消</button>
        <button type="submit" form="assignment-notes-form" class="btn btn-primary">保存</button>
      </footer>
    </dialog>
    <dialog v-if="!manage" ref="membersDialog" class="ra-dialog ra-members-dialog" aria-labelledby="task-members-title">
      <header>
        <div class="ra-members-heading">
          <h2 id="task-members-title">小组成员</h2>
          <p>{{ memberTask?.expName }}<template v-if="memberTask?.timeText"> · {{memberTask.timeText}}</template><template v-if="memberTask?.place"> · {{memberTask.place}}</template></p>
        </div>
        <button type="button" aria-label="关闭小组成员" @click="membersDialog.close()">×</button>
      </header>
      <div class="ra-members-summary">共 <strong>{{memberGroups.reduce((total, group) => total + group.members.length, 0)}}</strong> 名学生 <span>·</span> <strong>{{memberGroups.length}}</strong> 个小组</div>
      <div v-if="memberGroups.length" class="ra-members-body">
        <section v-for="(group,index) in memberGroups" :key="group.id" class="ra-members-group">
          <h3>{{group.name || '第 ' + (index + 1) + ' 组'}} <small>{{group.members.length}} 人</small></h3>
          <div class="ra-members-table-wrap">
            <table class="ra-members-table" :aria-label="(group.name || '第 ' + (index + 1) + ' 组') + '学生名单'">
              <thead><tr><th scope="col">姓名</th><th scope="col">学号</th><th scope="col">班级</th></tr></thead>
              <tbody>
                <tr v-for="student in group.members" :key="student.sid"><td><strong>{{student.name}}</strong></td><td class="ra-numeric">{{student.sid}}</td><td>{{student.cls || '—'}}</td></tr>
                <tr v-if="!group.members.length"><td colspan="3" class="ra-members-empty">暂无成员记录</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
      <p v-else class="ra-members-empty">暂无小组记录</p>
      <footer><button type="button" class="btn btn-ghost" @click="membersDialog.close()">关闭</button></footer>
    </dialog>
    <dialog ref="preview" class="ra-dialog"><header><h2>分组预览 <span>{{chosen.length}} 人 · {{groups.length}} 组</span></h2><button type="button" aria-label="关闭分组预览" @click="preview.close()">×</button></header><div class="ra-dialog-body"><section v-for="(g,i) in groups" :key="i" class="ra-group"><h3>第 {{i+1}} 组 <small>{{g.length}} 人</small></h3><div v-for="s in g" :key="s.sid" class="ra-member"><span><strong>{{s.name}}</strong><small>{{s.sid}} · {{s.cls}}</small></span><select :value="i" :aria-label="`调整${s.name}的小组`" @change="move(s.sid,Number($event.target.value))"><option v-for="(_,j) in groups" :value="j" :key="j">第 {{j+1}} 组</option><option :value="groups.length">新小组</option></select></div></section></div><footer><button type="button" class="btn btn-ghost" @click="buildGroups();groupEdited=false">重新分组</button><button type="button" class="btn btn-primary" @click="preview.close()">完成</button></footer></dialog>
  </div>
</template>

<style scoped>
.roster-assignment{--ra-text-size:14px;--ra-control-height:34px;--ra-radius:6px;--ra-line:#dfe7eb;--ra-ink:#263b47;--ra-muted:#657985;color:var(--ra-ink)}

.ra-roster,.ra-plan,.ra-tasks{background:#fff;border:1px solid var(--ra-line);border-radius:10px;overflow:hidden}
.ra-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.ra-heading h2,.ra-plan h2{font-size:19px;margin:0;font-weight:650}.ra-heading h2 span{font-size:14px;color:var(--ra-muted);margin-left:8px;font-weight:400}

.roster-assignment input:not([type=checkbox]):not([type=file]),.roster-assignment select,.roster-assignment textarea{width:100%;min-width:0;box-sizing:border-box;border:1px solid #d8e2e7;border-radius:8px;padding:10px 11px;font:inherit;font-size:14px;background:#fff;color:var(--ra-ink);min-height:40px;box-shadow:none}
.roster-assignment input:focus-visible,.roster-assignment select:focus-visible,.roster-assignment textarea:focus-visible{outline:2px solid var(--focus-ring);outline-offset:2px}
.roster-assignment input[type=checkbox]{accent-color:#347988;width:16px;height:16px;cursor:pointer;vertical-align:middle;flex-shrink:0}
.ra-selection{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--ra-line);font-size:13px;color:var(--ra-muted)}.ra-selection small{font-size:12px}
.ra-table-scroll {
  overflow: auto;
  margin: 0 20px 10px;
  border: 1px solid #bdcbd7;
  border-radius: 6px;
}
.ra-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: var(--ra-text-size);
  line-height: 1.4;
}
.ra-table th, .ra-table td {
  box-sizing: border-box;
  height: 44px;
  padding: 7px 20px;
  border-bottom: 1px solid #edf1f3;
  vertical-align: middle;
  overflow-wrap: anywhere;
}
.ra-table :is(th,td) + :is(th,td) { border-left: 1px solid #e5ebf0; }
.ra-table .ra-centered { text-align: center; }
.ra-centered .ra-row-actions { justify-content: center; }
.ra-centered .student-password-value { margin-inline: auto; }
.ra-table th {
  height: 40px;
  background: #f2f5f8;
  color: #455765;
  font-weight: 600;
  border-bottom-color: var(--ra-line);
  text-align: left;
}
.ra-table tbody tr:last-child td { border-bottom: 0; }
.ra-table tbody tr { transition: background-color var(--interaction-time); }
.ra-table tbody tr:hover, .ra-table tbody tr:focus-within { background: var(--surface-hover); }
.ra-student-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
}
.ra-student-cell input { margin: 0; }
.ra-table small { display: block; font-size: 12px; color: var(--ra-muted); margin-top: 2px; }
.ra-table strong { font-weight: 600; }
.ra-roster tbody .ra-selectable { cursor: pointer; }
.ra-roster tbody tr.selected { background: var(--surface-selected); }
.ra-roster tbody tr.selected > td:first-child { box-shadow: inset 3px 0 var(--action); }
.ra-roster tbody tr.selected:hover, .ra-roster tbody tr.ra-selectable:active {
  background: var(--surface-pressed);
}
.ra-table td.ra-empty { text-align: center; height: 150px; color: var(--ra-muted); }
.ra-pagination{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:var(--ra-muted)}.ra-pagination>div{display:flex;align-items:center;gap:12px}.ra-pagination button{border:1px solid var(--ra-line);background:#fff;border-radius:6px;font-size:22px;line-height:1;width:30px;height:30px;cursor:pointer}.ra-pagination button:disabled{opacity:.35;cursor:default}
.ra-plan{display:flex;flex-direction:column}.ra-plan-count{font-size:13px;color:var(--ra-muted);border-bottom:1px solid var(--ra-line)}.ra-plan-count strong{font-size:26px;font-weight:600;color:#2e6875;font-variant-numeric:tabular-nums}.ra-plan-count span{float:right;margin-top:10px}.ra-plan>label,.ra-notes-fields label,.ra-form-pair>label{display:flex;flex-direction:column;gap:7px;font-size:13px;color:var(--ra-muted)}.ra-plan fieldset{border:0;padding:0;margin:0}.ra-plan legend{font-size:13px;color:var(--ra-muted);margin-bottom:9px}.ra-trials{display:grid;grid-template-columns:1fr 1fr;gap:11px 7px}.ra-trials label{display:flex;gap:6px;align-items:center;font-size:13px}.ra-form-pair{display:grid;grid-template-columns:1fr 1fr;gap:14px;align-items:end}.ra-plan .ra-preview{height:40px}.ra-date{position:relative;padding:0}.ra-date>span{position:absolute;left:12px;top:12px;font-size:14px;pointer-events:none}.ra-date.is-empty input{color:transparent!important}.ra-date.is-empty input::-webkit-datetime-edit{visibility:hidden}.ra-date:focus-within>span{display:none}.ra-date:focus-within input::-webkit-datetime-edit{visibility:visible}.ra-date:focus-within input{color:var(--ra-ink)!important}.ra-dispatch{width:100%}.ra-tasks{margin-top:22px}
.ra-dialog{width:min(850px,calc(100vw - 32px));max-height:85vh;box-sizing:border-box;border:1px solid var(--ra-line);border-radius:16px;padding:0;color:var(--ra-ink)}.ra-dialog::backdrop{background:#172a3759}.ra-dialog>header,.ra-dialog>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:18px 22px}.ra-dialog h2{font-size:18px;margin:0}.ra-dialog h2 span{font-size:13px;font-weight:400;color:var(--ra-muted)}.ra-dialog>header button{border:0;background:none;font-size:26px;cursor:pointer}.ra-dialog-body{display:grid;grid-template-columns:1fr 1fr;gap:16px;max-height:60vh;overflow:auto;padding:0 22px 16px}.ra-group{border:1px solid var(--ra-line);border-radius:10px;padding:14px}.ra-group h3{font-size:14px;margin:0 0 8px}.ra-group h3 small{float:right;font-weight:400;color:var(--ra-muted)}.ra-member{display:flex;align-items:center;justify-content:space-between;padding:10px 0;gap:12px;border-top:1px solid #edf1f3}.ra-member strong{font-size:13px;font-weight:550}.ra-member small{display:block;font-size:11px;color:var(--ra-muted);margin-top:3px}.roster-assignment .ra-member select{width:98px;min-height:32px;font-size:12px;padding:6px}
.ra-roster { display: flex; flex-direction: column; }
.ra-workspace--assignment {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.ra-workspace--assignment .ra-pagination { margin-top: auto; }
.ra-heading { padding: 14px 20px 10px; }
.ra-filters {
  display: grid;
  grid-template-columns: minmax(135px, 1.4fr) repeat(2, minmax(0, 1fr));
  gap: 8px;
  flex: 1 1 350px;
  min-width: 0;
}
.roster-assignment .ra-filters input[type=search],
.roster-assignment .ra-filters select,
.roster-assignment .ra-heading select {
  height: var(--ra-control-height);
  min-height: var(--ra-control-height);
  padding: 0 9px;
  border-radius: var(--ra-radius);
  font-size: var(--ra-text-size);
}
.roster-assignment .ra-heading select { width: 112px; }
.ra-filterbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 0 20px 12px;
}
.ra-filter-actions, .ra-row-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.ra-filter-actions { margin-left: auto; flex-shrink: 0; }
.ra-filter-actions .btn { height: 34px; border-radius: 6px; padding-inline: 10px; font-size: 13px; }
.ra-selection {
  min-height: 34px;
  padding: 4px 20px;
  background: var(--surface-selected);
  color: var(--action-pressed);
}
.ra-roster .ra-table { min-width: 560px; }
.ra-management .ra-filters { max-width: 820px; }
.ra-management .ra-table { min-width: 820px; }
.ra-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.ra-roster-heading { flex-wrap: wrap; }
.ra-upload { position: relative; cursor: pointer; }
.ra-upload:not(:has(input:disabled)):hover { background: var(--action-hover); }
.ra-upload:not(:has(input:disabled)):active { background: var(--action-pressed); }
.ra-upload input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.ra-upload:focus-within { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.ra-upload:has(input:disabled) { opacity: .55; cursor: default; }
.ra-selection { flex-wrap: wrap; gap: 0 10px; }
.ra-selection button:disabled { opacity: .5; cursor: default; }
.ra-pagination { flex-wrap: wrap; gap: 8px; }
.ra-pagination label { display: flex; align-items: center; gap: 6px; white-space: nowrap; }
.ra-pagination label select { width: auto; min-height: 30px; padding: 3px 5px; font-size: 12px; }
.ra-pagination {
  border-top: 1px solid var(--ra-line);
  padding: 9px 20px;
  background: #fff;
}
.ra-pagination button { transition: background-color var(--interaction-time), border-color var(--interaction-time); }
.ra-pagination button:not(:disabled):hover { background: var(--surface-hover); border-color: #95bbcb; }
.ra-pagination button:not(:disabled):active { background: var(--surface-pressed); }
.roster-assignment :is(input[type=search], select, input[type=datetime-local], input[type=text], input:not([type]), textarea):not(:disabled) {
  transition: background-color var(--interaction-time), border-color var(--interaction-time);
}
.roster-assignment :is(input[type=search], select, input[type=datetime-local], input[type=text], input:not([type]), textarea):not(:disabled):hover {
  border-color: #95bbcb;
  background-color: var(--surface-tint);
}
.ra-notes-entry { display: flex; align-items: center; gap: 10px; }
.ra-notes-entry span { color: var(--ra-muted); font-size: 13px; }
.ra-notes-dialog { width: min(540px, calc(100vw - 32px)); }
.ra-notes-fields { display: grid; gap: 14px; padding: 0 22px 4px; }
.ra-notes-fields textarea { resize: vertical; }
.ra-notes-dialog > footer { justify-content: flex-end; }
.ra-plan { padding: 14px 20px 16px; gap: 12px; }
.ra-plan-count { padding-bottom: 8px; }
.ra-plan .ra-dispatch { margin-top: auto; }
.ra-schedule { grid-template-columns: minmax(210px, 1fr) minmax(0, 1fr); }
.ra-tasks .ra-heading { padding-bottom: 14px; flex-wrap: wrap; }
.roster-assignment :is(.ra-heading, .ra-table, .ra-selection, .ra-plan, .ra-dialog) .btn {
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: var(--ra-control-height);
  min-height: var(--ra-control-height);
  padding: 5px 10px;
  border-radius: var(--ra-radius);
  font-size: var(--ra-text-size);
  line-height: 1.4;
  box-shadow: none;
  white-space: nowrap;
}
.ra-tasks .ra-table { min-width: 700px; }
.ra-task-state {
  display: inline-flex;
  padding: 3px 9px;
  border-radius: 999px;
  background: #e7f3ed;
  color: #235e49;
  font-size: 12px;
}
.ra-task-state[data-state="scheduled"] { background: #fff2d8; color: #79520d; }
.ra-task-state[data-state="finished"] { background: #eef0f3; color: #475561; }
.ra-text-action {
  min-height: 28px;
  padding: 2px 4px;
  margin-inline: -4px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--action-hover);
  font: inherit;
  white-space: nowrap;
  cursor: pointer;
}
.ra-text-action:not(:disabled):hover { background: var(--surface-selected); text-decoration: underline; }
.ra-text-action:not(:disabled):active { background: var(--surface-pressed); }
.ra-text-action:disabled { opacity: .55; cursor: default; }
.ra-members-link { text-decoration: underline; text-underline-offset: 3px; }
.ra-page--management { padding-bottom: 90px; }
.ra-pagination.ra-pagination--page-bottom { position: fixed; z-index: 100; bottom: 0; left: 0; right: 0; box-sizing: border-box; min-height: 60px; margin: 0; padding: 12px clamp(1rem, 2.5vw, 2rem); padding-bottom: max(12px, env(safe-area-inset-bottom)); border-top: 1px solid #dfe7eb; background: #fff; color: #657985; }
.ra-status-heading { display: flex; align-items: center; justify-content: center; gap: 5px; white-space: nowrap; }
.ra-status-trigger { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; width: 28px; height: 28px; padding: 5px; border: 0; border-radius: 4px; background: transparent; color: #788a96; cursor: pointer; }
.ra-status-trigger svg { width: 16px; height: 16px; }
.ra-status-trigger:hover, .ra-status-trigger.is-filtered { background: #dfedef; color: #245b65; }
.ra-status-trigger:focus-visible, .ra-status-popover button:focus-visible { outline: 2px solid #245b65; outline-offset: 2px; }
.ra-status-popover { position: fixed; inset: auto; box-sizing: border-box; width: 176px; margin: 0; padding: 7px; border: 1px solid #dfe7eb; border-radius: 8px; background: #fff; color: #263b47; box-shadow: 0 6px 22px #263b471a; }
.ra-status-popover p { margin: 0; padding: 6px 9px 9px; font-size: 12px; color: #657985; }
.ra-status-popover button { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; min-height: 38px; padding: 8px 9px; border: 0; border-radius: 4px; background: transparent; color: inherit; font: inherit; font-size: 13px; cursor: pointer; }
.ra-status-popover button:hover { background: #f2f5f8; }
.ra-status-popover button.selected { background: #edf4f5; color: #245b65; }
.ra-status-popover svg { width: 16px; height: 16px; }
.ra-active-filter { display: inline-flex; align-items: center; gap: 9px; padding: 5px 9px; border: 1px solid #cbdde1; border-radius: 4px; background: #edf4f5; color: #245b65; font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer; }
.ra-active-filter span { font-size: 16px; line-height: 1; }
@media (max-width: 700px) { .ra-status-popover button { min-height: 44px; } .ra-filter-actions { flex-wrap: wrap; } }
.ra-refresh { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; flex-shrink: 0; padding: 8px; border: 0; border-radius: 6px; background: transparent; color: var(--ra-muted); cursor: pointer; }
.ra-refresh svg { display: block; width: 20px; height: 20px; }
.ra-refresh:hover:not(:disabled) { background: var(--surface-hover); color: var(--ra-ink); }
.ra-refresh:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.ra-refresh:disabled { opacity: .45; cursor: default; }
@media (max-width: 700px) { .ra-refresh { width: 44px; height: 44px; } }
.ra-numeric { font-variant-numeric: tabular-nums; }
.ra-member-list { list-style: none; padding: 0; margin: 0; }
.ra-member-list .ra-member { flex-wrap: wrap; gap: 4px 12px; font-size: 13px; }
.ra-member-list .ra-member span, .ra-members-empty { color: var(--ra-muted); font-size: 13px; }
.ra-members-empty { margin: 0; padding: 16px 22px 24px; }
.ra-members-dialog { display: none; width: min(880px, calc(100vw - 32px)); overflow: hidden; }
.ra-members-dialog[open] { display: flex; flex-direction: column; }
.ra-members-dialog > header { align-items: flex-start; border-bottom: 1px solid var(--ra-line); flex-shrink: 0; }
.ra-members-heading { min-width: 0; }
.ra-members-heading p { margin: 7px 0 0; color: var(--ra-muted); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.ra-members-dialog > header button { min-width: 36px; min-height: 36px; line-height: 1; }
.ra-members-summary { padding: 14px 22px; font-size: 14px; color: var(--ra-muted); flex-shrink: 0; }
.ra-members-summary strong { color: var(--ra-ink); font-variant-numeric: tabular-nums; }
.ra-members-summary > span { margin: 0 10px; }
.ra-members-body { min-height: 0; overflow-y: auto; padding: 0 22px 6px; }
.ra-members-group { margin-bottom: 18px; }
.ra-members-group h3 { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0; padding: 10px 12px; background: #f2f5f8; border-radius: 6px; font-size: 14px; }
.ra-members-group h3 small { font-size: 13px; color: var(--ra-muted); font-weight: 400; white-space: nowrap; }
.ra-members-table-wrap { overflow-x: auto; }
.ra-members-table { width: 100%; table-layout: fixed; border-collapse: collapse; text-align: left; font-size: 14px; line-height: 1.6; }
.ra-members-table th, .ra-members-table td { padding: 10px 12px; border-bottom: 1px solid #edf1f3; overflow-wrap: anywhere; vertical-align: top; }
.ra-members-table th { font-size: 12px; font-weight: 400; color: var(--ra-muted); }
.ra-members-table th:first-child { width: 22%; }
.ra-members-table th:nth-child(2) { width: 34%; }
.ra-members-table td strong { font-weight: 600; }
.ra-members-dialog > footer { justify-content: flex-end; border-top: 1px solid var(--ra-line); flex-shrink: 0; padding-block: 12px; }
@media (max-width: 600px) {
  .ra-members-dialog > header, .ra-members-summary { padding-inline: 16px; }
  .ra-members-body { padding-inline: 16px; }
  .ra-members-table { min-width: 540px; }
  .ra-members-table th:first-child, .ra-members-table td:first-child { position: sticky; left: 0; background: #fff; }
}

@media (max-width: 700px) {
  .ra-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1160px) {
  .ra-workspace--assignment { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 480px) {
  .ra-heading, .ra-plan { padding-inline: 14px; }
  .ra-filterbar, .ra-pagination { padding-inline: 14px; }
  .ra-selection { padding-inline: 14px; }
  .ra-schedule, .ra-dialog-body { grid-template-columns: minmax(0, 1fr); }
  .ra-dialog h2 span { display: block; margin-top: 4px; }
  .ra-table-scroll { margin-inline: 14px; }
  .ra-table th, .ra-table td { padding-inline: 14px; }
}
</style>
