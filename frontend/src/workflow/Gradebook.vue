<script setup>
import { computed, onMounted, ref } from 'vue'
import { call } from './api.js'
import ToastNotice from './ToastNotice.vue'

const data = ref({ experiments: [], students: [] })
const query = ref(''), selectedClass = ref('all'), busy = ref(false), error = ref('')
const classes = computed(() => [...new Set(data.value.students.map(student => student.cls).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const students = computed(() => data.value.students.filter(student => {
  const text = `${student.name} ${student.sid} ${student.cls || ''}`.toLowerCase()
  return (selectedClass.value === 'all' || student.cls === selectedClass.value) && text.includes(query.value.trim().toLowerCase())
}))
const completed = computed(() => data.value.students.filter(student => Object.values(student.scores || {}).some(score => score.status === '已完成')).length)
const cell = (student, experiment) => student.scores?.[experiment.id] || { status: '未开课' }
async function load() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { data.value = await call('/gradebook') } catch (e) { error.value = e.message } finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <section class="gradebook">
    <ToastNotice :message="error" type="error" @close="error = ''" />
    <header class="gradebook-head">
      <div><h2>实验成绩总表</h2><div class="gradebook-counts"><span>{{ classes.length }} 个班级</span><span>{{ data.students.length }} 名学生</span><span>{{ completed }} 人已有完整成绩</span></div></div>
      <button type="button" class="btn btn-ghost" :disabled="busy" @click="load">刷新</button>
    </header>

    <div class="gradebook-experiments">
      <article v-for="experiment in data.experiments" :key="experiment.id" :class="{ disabled: !experiment.available }">
        <header><strong>{{ experiment.name }}</strong><span>{{ experiment.available ? `${experiment.hours} 学时` : '待建设' }}</span></header>
        <div v-if="experiment.trials?.length" class="gradebook-trials"><span v-for="trial in experiment.trials" :key="trial">{{ trial }}</span></div>
      </article>
    </div>

    <div class="gradebook-tools">
      <select v-model="selectedClass" aria-label="筛选班级"><option value="all">全部班级</option><option v-for="name in classes" :key="name" :value="name">{{ name }}</option></select>
      <input v-model="query" type="search" placeholder="姓名 / 学号" aria-label="搜索学生">
      <span>{{ students.length }} 名学生</span>
    </div>

    <div class="gradebook-table">
      <table>
        <thead>
          <tr><th rowspan="2">班级</th><th rowspan="2">学生</th><th v-for="experiment in data.experiments" :key="experiment.id" colspan="2">{{ experiment.name }}</th></tr>
          <tr><template v-for="experiment in data.experiments" :key="experiment.id"><th>操作记录</th><th>实验报告</th></template></tr>
        </thead>
        <tbody>
          <tr v-for="student in students" :key="student.sid">
            <td>{{ student.cls || '未填写' }}</td>
            <td><strong>{{ student.name }}</strong><small>{{ student.sid }}</small></td>
            <template v-for="experiment in data.experiments" :key="experiment.id">
              <td><b v-if="cell(student, experiment).operationScore != null">{{ cell(student, experiment).operationScore }}</b><span v-else>—</span><small>{{ cell(student, experiment).status }}</small></td>
              <td><b v-if="cell(student, experiment).reportScore != null">{{ cell(student, experiment).reportScore }}</b><span v-else>—</span><small v-if="cell(student, experiment).reportSubmitted && cell(student, experiment).reportScore == null">待批阅</small></td>
            </template>
          </tr>
          <tr v-if="!students.length"><td :colspan="2 + data.experiments.length * 2" class="gradebook-empty">暂无符合条件的学生</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.gradebook{background:#fff;border:1px solid #dfe7eb;border-radius:10px;padding:22px;min-width:0}.gradebook-head,.gradebook-head>div,.gradebook-counts,.gradebook-experiments article header,.gradebook-tools{display:flex;align-items:center}.gradebook-head{justify-content:space-between;gap:20px;margin-bottom:18px}.gradebook-head>div{align-items:flex-start;flex-direction:column;gap:10px}.gradebook h2{margin:0;font-size:22px}.gradebook-counts{gap:8px;flex-wrap:wrap}.gradebook-counts span{padding:5px 9px;border-radius:5px;background:#f1f5f6;color:#52666f;font-size:12px}.gradebook-experiments{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:20px}.gradebook-experiments article{padding:16px;border:1px solid #dce6e9;border-radius:8px;background:#fbfcfd}.gradebook-experiments article.disabled{background:#f6f7f8;color:#78878e}.gradebook-experiments article header{justify-content:space-between;gap:12px}.gradebook-experiments article header span{padding:4px 7px;border-radius:4px;background:#e8f4f5;color:#087c88;font-size:12px}.gradebook-experiments article.disabled header span{background:#e8ecee;color:#687980}.gradebook-trials{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}.gradebook-trials span{font-size:11px;color:#52666f;background:#eef4f5;padding:4px 6px;border-radius:4px}.gradebook-tools{gap:12px;padding:14px;background:#f6f8f9;border-radius:7px;margin-bottom:16px}.gradebook-tools input{width:min(280px,100%)}.gradebook-tools span{margin-left:auto;color:#647980;font-size:13px}.gradebook-error{padding:12px;background:#fff0ee;color:#a33728}.gradebook-table{overflow:auto}.gradebook table{width:100%;min-width:1050px;border-collapse:collapse;font-size:13px}.gradebook th,.gradebook td{padding:13px 12px;border:1px solid #e1e8eb;text-align:center}.gradebook th{background:#f0f6f7;color:#405660;white-space:nowrap}.gradebook td:first-child,.gradebook td:nth-child(2){text-align:left}.gradebook td strong,.gradebook td small{display:block}.gradebook td b{color:#087c88;font-size:16px}.gradebook td small{margin-top:5px;color:#788990;font-size:11px}.gradebook-empty{text-align:center!important;color:#788990;padding:28px!important}@media(max-width:900px){.gradebook-experiments{grid-template-columns:1fr}.gradebook-tools{align-items:stretch;flex-direction:column}.gradebook-tools span{margin-left:0}.gradebook-head{align-items:flex-start}.gradebook{padding:14px}}
</style>
