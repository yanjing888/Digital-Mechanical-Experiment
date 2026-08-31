'use strict';

const express = require('express');
const store = require('../services/store');
const dify = require('../services/dify');
const fracture = require('../services/fracture');

function getToken(req) {
  const h = req.headers.authorization || '';
  if (h.startsWith('Bearer ')) return h.slice(7);
  return req.headers['x-session-token'] || req.query.token || null;
}

async function requireAuth(req, res, next) {
  try {
    const sess = await store.getSession(getToken(req));
    if (!sess) return res.status(401).json({ error: '未登录' });
    req.session = sess;
    next();
  } catch (e) {
    next(e);
  }
}

function requireTeacher(req, res, next) {
  if (!req.session || req.session.role !== 'teacher') {
    return res.status(403).json({ error: '需要教师权限' });
  }
  next();
}

function requireStudent(req, res, next) {
  if (!req.session || req.session.role !== 'student') {
    return res.status(403).json({ error: '需要学生权限' });
  }
  next();
}

function wrap(fn) {
  return function (req, res, next) {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
}

const router = express.Router();

router.get('/meta', wrap(async (_req, res) => {
  res.json(await store.listMeta());
}));

router.get('/assistant/status', (_req, res) => {
  res.json(dify.getStatus());
});

router.post('/auth/login', wrap(async (req, res) => {
  try {
    const { account, password } = req.body || {};
    const result = await store.loginWithPassword(account, password);
    res.json(result);
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '登录失败' });
  }
}));

router.post('/auth/logout', requireAuth, wrap(async (req, res) => {
  await store.deleteSession(getToken(req));
  res.json({ ok: true });
}));

router.get('/session', requireAuth, wrap(async (req, res) => {
  const snap = await store.sessionSnapshot(getToken(req));
  if (!snap) return res.status(401).json({ error: '会话无效' });
  res.json(snap);
}));

router.get('/tasks', requireAuth, requireTeacher, wrap(async (_req, res) => {
  res.json({ tasks: await store.listTasks() });
}));

router.get('/roster/template', requireAuth, requireTeacher, wrap(async (_req, res) => {
  const { buildTemplateBuffer } = require('../services/roster');
  const buf = buildTemplateBuffer();
  res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  res.setHeader('Content-Disposition', 'attachment; filename="student-roster-template.xlsx"');
  res.send(buf);
}));

router.get('/roster/students', requireAuth, requireTeacher, wrap(async (_req, res) => {
  const students = await store.listStudents();
  res.json({
    students,
    groups: await store.listGroups(),
    ungrouped: students.filter((s) => !s.groupId)
  });
}));

router.post('/roster/upload', requireAuth, requireTeacher, wrap(async (req, res) => {
  const multer = require('multer');
  const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 5 * 1024 * 1024 }
  }).single('file');

  await new Promise((resolve, reject) => {
    upload(req, res, (err) => (err ? reject(err) : resolve()));
  });

  if (!req.file) return res.status(400).json({ error: '请上传学生名单文件' });
  const { parseRosterBuffer } = require('../services/roster');
  let rows;
  try {
    rows = parseRosterBuffer(req.file.buffer, req.file.originalname);
  } catch (e) {
    return res.status(400).json({ error: '无法解析表格，请使用下载的模板' });
  }
  try {
    const result = await store.importRoster(rows);
    res.json(result);
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '导入失败' });
  }
}));

router.post('/groups', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    res.json(await store.createGroup(req.body.name));
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '创建失败' });
  }
}));

router.patch('/groups/:id', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    if (req.body.name != null) {
      res.json(await store.renameGroup(req.params.id, req.body.name));
      return;
    }
    if (req.body.memberSids) {
      res.json(await store.setGroupMembers(req.params.id, req.body.memberSids));
      return;
    }
    res.status(400).json({ error: '无有效更新内容' });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '更新失败' });
  }
}));

router.post('/groups/:id/members', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    res.json(await store.moveStudentsToGroup(req.params.id, req.body.memberSids || []));
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '移入失败' });
  }
}));

router.post('/groups/ungroup', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    res.json(await store.ungroupStudents(req.body.memberSids || []));
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '移出失败' });
  }
}));

router.delete('/groups/:id', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    res.json(await store.deleteGroup(req.params.id));
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '删除失败' });
  }
}));

router.post('/tasks', requireAuth, requireTeacher, wrap(async (req, res) => {
  try {
    const task = await store.createTask({
      expId: req.body.expId,
      groupIds: req.body.groupIds || [],
      place: req.body.place || '',
      timeText: req.body.timeText || '',
      note: req.body.note || '',
      tip: req.body.tip || '',
      teacher: req.session.teacher_name
    });
    res.json({ task, tasks: await store.listTasks() });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '下发失败' });
  }
}));

router.post('/lab/start', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || (s.status !== 'assigned' && s.status !== 'in_lab' && s.status !== 'lab_done')) {
    return res.status(400).json({ error: '当前无法开始实验' });
  }
  // Teammate already progressed — adopt shared progress instead of resetting
  const aligned = await store.alignStudentWithGroupLab(sid);
  if (aligned && aligned.status !== 'assigned') {
    return res.json({ student: aligned });
  }
  if (s.status !== 'assigned') return res.status(400).json({ error: '当前无法开始实验' });
  const student = await store.saveLabProgressForGroup(sid, {
    status: 'in_lab',
    expFlow: 'wait_door',
    doorClosed: false,
    acqPaused: false,
    acq: { points: [], t0: null },
    maxF: 0,
    maxD: 0,
    fractureImg: null,
    fractureType: null,
    fractureConf: 0,
    fractureAnalysis: null,
    groupConfirmed: false
  });
  res.json({ student });
}));

router.post('/lab/door', requireAuth, requireStudent, wrap(async (req, res) => {
  const student = await store.saveLabProgressForGroup(req.session.student_id, {
    doorClosed: !!req.body.closed
  });
  res.json({ student });
}));

router.post('/lab/start-machine', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  await store.alignStudentWithGroupLab(sid);
  const s = await store.getStudent(sid);
  if (!s || s.status !== 'in_lab' || s.expFlow !== 'wait_door') {
    return res.status(400).json({ error: '当前阶段无法启动' });
  }
  if (!s.doorClosed) return res.status(400).json({ error: '请先确认安全门已关闭' });
  const student = await store.saveLabProgressForGroup(sid, {
    expFlow: 'acquiring',
    acqPaused: false,
    acq: { points: [], t0: Date.now() },
    maxF: 0,
    maxD: 0
  });
  res.json({ student });
}));

router.post('/lab/acq', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || s.expFlow !== 'acquiring') return res.status(400).json({ error: '未在采集中' });
  const points = Array.isArray(req.body.points) ? req.body.points : (s.acq.points || []);
  const maxF = Number(req.body.maxF != null ? req.body.maxF : s.maxF);
  const maxD = Number(req.body.maxD != null ? req.body.maxD : s.maxD);
  const student = await store.saveLabProgressForGroup(sid, {
    acq: { points, t0: (s.acq && s.acq.t0) || Date.now() },
    maxF,
    maxD,
    acqPaused: !!req.body.paused
  });
  res.json({ student });
}));

router.post('/lab/pause', requireAuth, requireStudent, wrap(async (req, res) => {
  const student = await store.saveLabProgressForGroup(req.session.student_id, { acqPaused: !!req.body.paused });
  res.json({ student });
}));

router.post('/lab/rupture', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || s.expFlow !== 'acquiring') return res.status(400).json({ error: '未在采集中' });
  const patch = { expFlow: 'post_break', acqPaused: true };
  if (req.body.points) {
    patch.acq = { points: req.body.points, t0: (s.acq && s.acq.t0) || Date.now() };
    patch.maxF = Number(req.body.maxF != null ? req.body.maxF : s.maxF);
    patch.maxD = Number(req.body.maxD != null ? req.body.maxD : s.maxD);
  }
  const student = await store.saveLabProgressForGroup(sid, patch);
  res.json({ student });
}));

router.post('/lab/fracture/analyze', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || (s.expFlow !== 'post_break' && s.expFlow !== 'done_viz')) {
    return res.status(400).json({ error: '当前阶段无法分析断口' });
  }
  const imageBase64 = req.body.imageBase64 || req.body.fractureImg || '';
  const points = (req.body.points && req.body.points.length)
    ? req.body.points
    : ((s.acq && s.acq.points) || []);
  try {
    const result = await fracture.analyzeFracture({ imageBase64, points });
    res.json({ analysis: result });
  } catch (e) {
    const status = e.status || 500;
    res.status(status).json({ error: e.message || '断口分析失败' });
  }
}));

router.post('/lab/fracture', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || (s.expFlow !== 'post_break' && s.expFlow !== 'done_viz')) {
    return res.status(400).json({ error: '当前阶段无法更新断口' });
  }
  const patch = {};
  if (req.body.fractureImg != null) patch.fractureImg = req.body.fractureImg;
  if (req.body.fractureType != null) patch.fractureType = req.body.fractureType;
  if (req.body.fractureConf != null) patch.fractureConf = Number(req.body.fractureConf);
  if (req.body.fractureAnalysis != null) patch.fractureAnalysis = req.body.fractureAnalysis;
  if (s.expFlow === 'post_break' && patch.fractureImg) patch.expFlow = 'done_viz';
  const student = await store.saveLabProgressForGroup(sid, patch);
  res.json({ student });
}));

router.post('/lab/finish', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || s.expFlow !== 'done_viz') return res.status(400).json({ error: '请先完成断口确认' });
  const groupReport = await store.syncGroupDataFromStudent(sid);
  res.json({ student: await store.getStudent(sid), groupReport });
}));

router.post('/reports/group/confirm', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || !s.taskId) return res.status(400).json({ error: '无任务' });
  const gr = await store.ensureGroupReport(s.taskId, s.groupId);
  if (gr.confirmedBy.indexOf(sid) < 0) gr.confirmedBy.push(sid);
  await store.saveGroupReport(gr);
  await store.saveStudentFields(sid, { groupConfirmed: true });
  res.json({
    student: await store.getStudent(sid),
    groupReport: await store.ensureGroupReport(s.taskId, s.groupId)
  });
}));

router.post('/reports/group/submit', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s || !s.taskId) return res.status(400).json({ error: '无任务' });
  const gr = await store.ensureGroupReport(s.taskId, s.groupId);
  if (!gr.confirmedBy || gr.confirmedBy.length < 1) {
    return res.status(400).json({ error: '请先由一名组员确认组报告' });
  }
  if (!gr.data) return res.status(400).json({ error: '尚无组实验数据' });
  gr.submitted = true;
  gr.submittedAt = new Date().toLocaleString();
  await store.saveGroupReport(gr);
  res.json({ groupReport: await store.ensureGroupReport(s.taskId, s.groupId) });
}));

router.post('/reports/personal/save', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s) return res.status(400).json({ error: '学生不存在' });
  if (s.personalSubmitted) return res.status(400).json({ error: '已提交，无法修改' });
  const personal = Object.assign({}, s.personal || {}, {
    steps: req.body.steps != null ? req.body.steps : (s.personal.steps || ''),
    analysis: req.body.analysis != null ? req.body.analysis : (s.personal.analysis || ''),
    reflection: req.body.reflection != null ? req.body.reflection : (s.personal.reflection || ''),
    fileName: req.body.fileName != null ? req.body.fileName : (s.personal.fileName || ''),
    fileDataUrl: req.body.fileDataUrl != null ? req.body.fileDataUrl : (s.personal.fileDataUrl || '')
  });
  const student = await store.saveStudentFields(sid, { personal });
  res.json({ student });
}));

router.post('/reports/personal/submit', requireAuth, requireStudent, wrap(async (req, res) => {
  const sid = req.session.student_id;
  const s = await store.getStudent(sid);
  if (!s) return res.status(400).json({ error: '学生不存在' });
  if (s.personalSubmitted) return res.status(400).json({ error: '已提交' });
  const personal = Object.assign({}, s.personal || {}, {
    steps: req.body.steps || '',
    analysis: req.body.analysis || '',
    reflection: req.body.reflection || '',
    fileName: req.body.fileName || s.personal.fileName || '',
    fileDataUrl: req.body.fileDataUrl || s.personal.fileDataUrl || ''
  });
  if (!String(personal.steps || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').trim()
      && !String(personal.analysis || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').trim()
      && !personal.fileName) {
    return res.status(400).json({ error: '请至少填写部分内容或上传附件' });
  }
  const student = await store.saveStudentFields(sid, {
    personal,
    personalSubmitted: true,
    personalSubmittedAt: new Date().toLocaleString(),
    status: 'submitted'
  });
  res.json({ student });
}));

router.get('/grading', requireAuth, requireTeacher, wrap(async (_req, res) => {
  res.json({ items: await store.gradingQueue() });
}));

router.get('/grading/:sid', requireAuth, requireTeacher, wrap(async (req, res) => {
  const student = await store.getStudent(req.params.sid);
  if (!student || student.status === 'none') return res.status(404).json({ error: '无此学生任务' });
  const groupReport = student.taskId ? await store.ensureGroupReport(student.taskId, student.groupId) : null;
  res.json({ student, groupReport });
}));

router.post('/grading/:sid', requireAuth, requireTeacher, wrap(async (req, res) => {
  const student = await store.getStudent(req.params.sid);
  if (!student || !student.taskId) return res.status(404).json({ error: '无此学生任务' });
  const gr = await store.ensureGroupReport(student.taskId, student.groupId);
  if (req.body.groupScore != null && gr.submitted) {
    gr.score = Number(req.body.groupScore);
    gr.comment = req.body.groupComment != null ? String(req.body.groupComment) : gr.comment;
    await store.saveGroupReport(gr);
  }
  const patch = {};
  if (req.body.personalScore != null && student.personalSubmitted) {
    patch.personalScore = Number(req.body.personalScore);
  }
  if (req.body.personalComment != null && student.personalSubmitted) {
    patch.personalComment = String(req.body.personalComment);
  }
  if (Object.keys(patch).length) await store.saveStudentFields(student.sid, patch);
  res.json({
    student: await store.getStudent(student.sid),
    groupReport: await store.ensureGroupReport(student.taskId, student.groupId)
  });
}));

router.post('/grading/:sid/ai-review', requireAuth, requireTeacher, wrap(async (req, res) => {
  const student = await store.getStudent(req.params.sid);
  if (!student || !student.taskId) return res.status(404).json({ error: '无此学生任务' });
  const reportType = req.body.reportType === 'personal' ? 'personal' : 'group';
  const gr = await store.ensureGroupReport(student.taskId, student.groupId);
  const data = (gr && gr.data) || {};

  let reportText = '';
  if (reportType === 'group') {
    if (!gr || !gr.submitted) return res.status(400).json({ error: '组报告尚未提交，无法 AI 评阅' });
    reportText = gr.html || '';
  } else {
    if (!student.personalSubmitted) return res.status(400).json({ error: '个人报告尚未提交，无法 AI 评阅' });
    const p = student.personal || {};
    reportText = [
      '一、实验步骤描述\n' + (p.steps || ''),
      '二、数据分析与误差\n' + (p.analysis || ''),
      '三、总结与反思\n' + (p.reflection || ''),
      p.fileName ? ('附件：' + p.fileName) : ''
    ].filter(Boolean).join('\n\n');
  }

  const labData = {
    fMax: data.fMax != null ? data.fMax : student.maxF,
    dMax: data.dMax != null ? data.dMax : student.maxD,
    fractureType: data.fractureType || student.fractureType || null,
    fractureConf: data.fractureConf != null ? data.fractureConf : student.fractureConf,
    sourceSid: data.sourceSid || null,
    pointCount: (data.points && data.points.length) || ((student.acq && student.acq.points && student.acq.points.length) || 0)
  };

  const result = await dify.gradeReport({
    reportType,
    studentName: student.name,
    studentSid: student.sid,
    groupName: student.groupName,
    expName: student.expName,
    reportText,
    labData,
    user: 'teacher:' + (req.session.teacher_name || 'unknown')
  });

  res.json({
    reportType,
    score: result.score,
    comment: result.comment,
    source: result.source || 'dify',
    workflowRunId: result.workflowRunId || null
  });
}));

router.post('/assistant/chat', requireAuth, wrap(async (req, res) => {
  try {
    const result = await dify.chat({
      message: req.body.message || '',
      user: req.session.role === 'teacher' ? req.session.teacher_name : req.session.student_id,
      conversationId: req.body.conversationId,
      inputs: req.body.inputs || {}
    });
    res.json(result);
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message || '智能体暂不可用' });
  }
}));

module.exports = router;
