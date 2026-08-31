'use strict';

const { nanoid } = require('nanoid');
const { query, withTransaction } = require('../db');
const { verifyPassword } = require('./auth');

function parseJson(s, fallback) {
  if (s == null || s === '') return fallback;
  try { return JSON.parse(s); } catch (_) { return fallback; }
}

/**
 * 实验操作记录扣分项目录（默认值，最终以学校提供清单为准）。
 * 仅针对危险操作、课堂纪律扣分；原始数据好坏不扣分。
 */
const DEDUCTION_CATALOG = [
  { id: 'safety_major', label: '重大危险操作（危及人身/设备安全）', points: 40, group: '安全' },
  { id: 'safety_door', label: '安全门未关闭即尝试启动', points: 20, group: '安全' },
  { id: 'safety_op', label: '违规操作试验机', points: 15, group: '安全' },
  { id: 'no_ppe', label: '未按要求穿戴防护用品', points: 10, group: '安全' },
  { id: 'order_chaos', label: '小组秩序混乱', points: 10, group: '纪律' },
  { id: 'disobey', label: '不听从指挥', points: 10, group: '纪律' },
  { id: 'late', label: '迟到 / 早退', points: 5, group: '纪律' },
  { id: 'phone', label: '课堂玩手机 / 做无关事', points: 5, group: '纪律' },
  { id: 'env', label: '实验后未清理台面 / 归位', points: 5, group: '纪律' }
];

const OPERATION_BASE_SCORE = 100;

function computeOperationScore(deductions) {
  const total = (deductions || []).reduce((sum, d) => sum + (Number(d.points) || 0), 0);
  return Math.max(0, OPERATION_BASE_SCORE - total);
}

function escHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function calcWork(points) {
  let work = 0;
  for (let i = 1; i < (points || []).length; i++) {
    const dd = points[i].d - points[i - 1].d;
    if (dd > 0) work += 0.5 * (points[i].f + points[i - 1].f) * dd;
  }
  return work;
}

function buildCurveSvg(points) {
  const pts = (points || []).filter((p) => p && Number.isFinite(Number(p.d)) && Number.isFinite(Number(p.f)));
  if (pts.length < 2) {
    return '<p class="rpt-empty">暂无力—位移曲线数据</p>';
  }
  const W = 640;
  const H = 280;
  const padL = 52;
  const padR = 18;
  const padT = 18;
  const padB = 42;
  let maxD = 0;
  let maxF = 0;
  pts.forEach((p) => {
    maxD = Math.max(maxD, Number(p.d) || 0);
    maxF = Math.max(maxF, Number(p.f) || 0);
  });
  maxD = Math.max(maxD, 1e-6);
  maxF = Math.max(maxF, 1e-6);
  const xOf = (d) => padL + (Number(d) / maxD) * (W - padL - padR);
  const yOf = (f) => padT + (1 - Number(f) / maxF) * (H - padT - padB);
  const poly = pts.map((p) => xOf(p.d).toFixed(1) + ',' + yOf(p.f).toFixed(1)).join(' ');
  const area = pts.map((p) => xOf(p.d).toFixed(1) + ',' + yOf(p.f).toFixed(1)).join(' ')
    + ' ' + xOf(pts[pts.length - 1].d).toFixed(1) + ',' + (H - padB).toFixed(1)
    + ' ' + xOf(pts[0].d).toFixed(1) + ',' + (H - padB).toFixed(1);
  const gridY = [0.25, 0.5, 0.75, 1].map((t) => {
    const y = padT + (1 - t) * (H - padT - padB);
    const val = (maxF * t).toFixed(1);
    return '<line x1="' + padL + '" y1="' + y.toFixed(1) + '" x2="' + (W - padR) + '" y2="' + y.toFixed(1)
      + '" stroke="#E8EEF2" stroke-width="1"/>'
      + '<text x="' + (padL - 8) + '" y="' + (y + 4).toFixed(1) + '" text-anchor="end" font-size="11" fill="#64748b">'
      + val + '</text>';
  }).join('');
  const gridX = [0, 0.25, 0.5, 0.75, 1].map((t) => {
    const x = padL + t * (W - padL - padR);
    const val = (maxD * t).toFixed(1);
    return '<line x1="' + x.toFixed(1) + '" y1="' + padT + '" x2="' + x.toFixed(1) + '" y2="' + (H - padB)
      + '" stroke="#E8EEF2" stroke-width="1"/>'
      + '<text x="' + x.toFixed(1) + '" y="' + (H - 14) + '" text-anchor="middle" font-size="11" fill="#64748b">'
      + val + '</text>';
  }).join('');
  return ''
    + '<div class="rpt-curve-wrap">'
    + '<svg class="rpt-curve" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="力—位移曲线">'
    + '<rect x="0" y="0" width="' + W + '" height="' + H + '" fill="#fff"/>'
    + gridY + gridX
    + '<line x1="' + padL + '" y1="' + padT + '" x2="' + padL + '" y2="' + (H - padB) + '" stroke="#94a3b8" stroke-width="1.2"/>'
    + '<line x1="' + padL + '" y1="' + (H - padB) + '" x2="' + (W - padR) + '" y2="' + (H - padB) + '" stroke="#94a3b8" stroke-width="1.2"/>'
    + '<polygon points="' + area + '" fill="rgba(14,124,139,0.10)"/>'
    + '<polyline points="' + poly + '" fill="none" stroke="#0E7C8B" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>'
    + '<text x="14" y="16" font-size="11" fill="#64748b">F / kN</text>'
    + '<text x="' + (W - 8) + '" y="' + (H - 6) + '" text-anchor="end" font-size="11" fill="#64748b">ΔL / mm</text>'
    + '</svg></div>';
}

function favorLabel(favor) {
  if (favor === 'ductile') return '偏塑性';
  if (favor === 'brittle') return '偏脆性';
  return '中性';
}

/**
 * 实验操作记录正文：小组共用的原始实验数据（曲线、断口原图、关键结果）。
 * 断口的塑性/脆性判定为后台标准答案，不写入学生可见正文。
 */
function buildGroupHtml(data) {
  const fmax = Number(data.fMax || 0);
  const dmax = Number(data.dMax || 0);
  const A0 = 78.54;
  const L0 = 50;
  const sigb = fmax * 1000 / A0;
  const eps = dmax / L0 * 100;
  const work = calcWork(data.points || []);

  const imgHtml = data.fractureImg
    ? '<div class="rpt-frac"><img src="' + data.fractureImg + '" alt="断口原始图像" /></div>'
    : '<p class="rpt-empty">暂无断口图像</p>';

  return ''
    + '<h4>一、实验目的</h4><p>测定材料在拉伸载荷下的力学性能，获取力—位移曲线，观察断口形貌。</p>'
    + '<h4>二、实验设备与试样</h4><p>电子万能试验机；圆截面试样 L<sub>0</sub>=50 mm，d=10 mm，A<sub>0</sub>≈78.54 mm²。</p>'
    + '<h4>三、实验过程摘要</h4><p>小组现场完成安全门联锁确认、启动、数据采集与断口拍摄。数据来源学号：' + escHtml(data.sourceSid || '—') + '。</p>'
    + '<h4>四、主要结果</h4>'
    + '<p>F<sub>max</sub> = <strong>' + fmax.toFixed(2) + '</strong> kN；ΔL<sub>max</sub> = <strong>' + dmax.toFixed(2) + '</strong> mm；'
    + 'σ<sub>b</sub> ≈ <strong>' + sigb.toFixed(1) + '</strong> MPa；ε<sub>max</sub> ≈ <strong>' + eps.toFixed(2) + '</strong> %；'
    + '吸收功 W ≈ <strong>' + work.toFixed(1) + '</strong> J。</p>'
    + '<h4>五、力—位移曲线</h4>'
    + buildCurveSvg(data.points || [])
    + '<h4>六、断口原始图像</h4>'
    + imgHtml
    + '<h4>七、小组备注</h4><p>' + escHtml(data.note || '（无）') + '</p>';
}

function studentRowToObj(row, groupName) {
  if (!row) return null;
  return {
    sid: row.sid,
    name: row.name,
    cls: row.cls,
    groupId: row.group_id,
    groupName: groupName || '',
    status: row.status,
    taskId: row.task_id,
    expId: row.exp_id,
    expName: row.exp_name,
    note: row.note || '',
    tip: row.tip || '',
    place: row.place || '',
    timeText: row.time_text || '',
    doorClosed: !!row.door_closed,
    expFlow: row.exp_flow || 'wait_door',
    acqPaused: !!row.acq_paused,
    acq: parseJson(row.acq_json, { points: [], t0: null }),
    maxF: Number(row.max_f || 0),
    maxD: Number(row.max_d || 0),
    fractureImg: row.fracture_img,
    fractureType: row.fracture_type,
    fractureConf: Number(row.fracture_conf || 0),
    fractureAnalysis: parseJson(row.fracture_analysis_json, null),
    fractureSelf: parseJson(row.fracture_self_json, null),
    groupConfirmed: !!row.group_confirmed,
    personal: parseJson(row.personal_json, {}),
    personalSubmitted: !!row.personal_submitted,
    personalSubmittedAt: row.personal_submitted_at,
    personalScore: row.personal_score == null ? null : Number(row.personal_score),
    personalComment: row.personal_comment || ''
  };
}

async function getGroupName(groupId) {
  if (!groupId) return '未分组';
  const rows = await query('SELECT name FROM lab_groups WHERE id = ?', [groupId]);
  return rows[0] ? rows[0].name : groupId;
}

async function getStudent(sid) {
  const rows = await query('SELECT * FROM students WHERE sid = ?', [sid]);
  if (!rows[0]) return null;
  return studentRowToObj(rows[0], await getGroupName(rows[0].group_id));
}

async function listStudents() {
  const rows = await query(`
    SELECT s.*, g.name AS group_name
    FROM students s
    LEFT JOIN lab_groups g ON g.id = s.group_id
    ORDER BY (s.group_id IS NULL) DESC, s.group_id, s.sid
  `);
  return rows.map((r) => studentRowToObj(r, r.group_name || '未分组'));
}

async function listGroups() {
  const groups = await query('SELECT id, name FROM lab_groups ORDER BY name, id');
  const out = [];
  for (const g of groups) {
    const members = await query('SELECT sid, name, cls FROM students WHERE group_id = ? ORDER BY sid', [g.id]);
    out.push({ id: g.id, name: g.name, members });
  }
  return out;
}

async function listMeta() {
  const experiments = await query('SELECT id, name, hours FROM experiments ORDER BY id');
  const teachers = await query('SELECT name FROM teachers ORDER BY name');
  const students = await listStudents();
  return {
    experiments,
    teachers: teachers.map((t) => t.name),
    groups: await listGroups(),
    students,
    ungrouped: students.filter((s) => !s.groupId)
  };
}

async function importRoster(rows) {
  if (!rows || !rows.length) throw Object.assign(new Error('表格中没有有效学生数据'), { status: 400 });
  const { hashPassword } = require('./auth');
  const { STUDENT_DEFAULT_PASSWORD } = require('../seed');
  const defaultHash = hashPassword(STUDENT_DEFAULT_PASSWORD);

  let created = 0;
  let updated = 0;
  await withTransaction(async (conn) => {
    for (const row of rows) {
      const sid = String(row.sid || '').trim();
      const name = String(row.name || '').trim();
      const cls = String(row.cls || '').trim() || '—';
      if (!sid || !name) continue;
      const [exist] = await conn.execute('SELECT sid, password_hash FROM students WHERE sid = ?', [sid]);
      if (exist[0]) {
        await conn.execute('UPDATE students SET name = ?, cls = ? WHERE sid = ?', [name, cls, sid]);
        updated++;
      } else {
        await conn.execute(
          `INSERT INTO students
           (sid, name, cls, group_id, password_hash, status, personal_json, acq_json)
           VALUES (?, ?, ?, NULL, ?, 'none', '{}', '{"points":[],"t0":null}')`,
          [sid, name, cls, defaultHash]
        );
        created++;
      }
    }
  });
  return {
    created,
    updated,
    students: await listStudents(),
    groups: await listGroups(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

async function createGroup(name) {
  const n = String(name || '').trim();
  if (!n) throw Object.assign(new Error('请输入小组名称'), { status: 400 });
  const id = 'G_' + nanoid(8);
  await query('INSERT INTO lab_groups (id, name) VALUES (?, ?)', [id, n]);
  return { group: { id, name: n, members: [] }, groups: await listGroups() };
}

async function renameGroup(groupId, name) {
  const n = String(name || '').trim();
  if (!n) throw Object.assign(new Error('请输入小组名称'), { status: 400 });
  const rows = await query('SELECT id FROM lab_groups WHERE id = ?', [groupId]);
  if (!rows[0]) throw Object.assign(new Error('小组不存在'), { status: 404 });
  await query('UPDATE lab_groups SET name = ? WHERE id = ?', [n, groupId]);
  return { groups: await listGroups() };
}

async function deleteGroup(groupId) {
  const rows = await query('SELECT id FROM lab_groups WHERE id = ?', [groupId]);
  if (!rows[0]) throw Object.assign(new Error('小组不存在'), { status: 404 });
  // 若已有任务关联，禁止删除
  const used = await query('SELECT task_id FROM task_groups WHERE group_id = ? LIMIT 1', [groupId]);
  if (used[0]) throw Object.assign(new Error('该小组已有下发任务，无法删除'), { status: 400 });
  await withTransaction(async (conn) => {
    await conn.execute('UPDATE students SET group_id = NULL WHERE group_id = ?', [groupId]);
    await conn.execute('DELETE FROM lab_groups WHERE id = ?', [groupId]);
  });
  return {
    groups: await listGroups(),
    students: await listStudents(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

async function setGroupMembers(groupId, memberSids) {
  const rows = await query('SELECT id FROM lab_groups WHERE id = ?', [groupId]);
  if (!rows[0]) throw Object.assign(new Error('小组不存在'), { status: 404 });
  const sids = Array.isArray(memberSids) ? memberSids.map(String) : [];
  await withTransaction(async (conn) => {
    // 先把本组现有成员移出
    await conn.execute('UPDATE students SET group_id = NULL WHERE group_id = ?', [groupId]);
    for (const sid of sids) {
      await conn.execute('UPDATE students SET group_id = ? WHERE sid = ?', [groupId, sid]);
    }
  });
  return {
    groups: await listGroups(),
    students: await listStudents(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

async function moveStudentsToGroup(groupId, memberSids) {
  const rows = await query('SELECT id FROM lab_groups WHERE id = ?', [groupId]);
  if (!rows[0]) throw Object.assign(new Error('小组不存在'), { status: 404 });
  const sids = Array.isArray(memberSids) ? memberSids.map(String) : [];
  if (!sids.length) throw Object.assign(new Error('请先选择学生'), { status: 400 });
  await withTransaction(async (conn) => {
    for (const sid of sids) {
      await conn.execute('UPDATE students SET group_id = ? WHERE sid = ?', [groupId, sid]);
    }
  });
  return {
    groups: await listGroups(),
    students: await listStudents(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

/**
 * 自动分组：按每组人数或组数，随机/平均分配全部学生。
 * @param {{ size?: number, count?: number, mode?: 'random'|'even', prefix?: string }} opts
 */
async function autoGroup(opts) {
  const options = opts || {};
  const mode = options.mode === 'even' ? 'even' : 'random';
  const prefix = String(options.prefix || '第').trim() || '第';
  const students = await listStudents();
  const sids = students.map((s) => s.sid);
  if (!sids.length) throw Object.assign(new Error('暂无学生，请先导入名单'), { status: 400 });

  if (mode === 'random') {
    for (let i = sids.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const t = sids[i]; sids[i] = sids[j]; sids[j] = t;
    }
  }

  let groupCount;
  if (options.count && Number(options.count) > 0) {
    groupCount = Math.min(Number(options.count), sids.length);
  } else {
    const size = Math.max(1, Number(options.size) || 4);
    groupCount = Math.max(1, Math.ceil(sids.length / size));
  }

  /* 平均分配：轮转填充，使各组人数尽量均衡 */
  const buckets = Array.from({ length: groupCount }, () => []);
  sids.forEach((sid, i) => { buckets[i % groupCount].push(sid); });

  await withTransaction(async (conn) => {
    /* 清空既有分组关系与空的历史小组（未被任务占用的） */
    await conn.execute('UPDATE students SET group_id = NULL');
    const [used] = await conn.execute('SELECT DISTINCT group_id FROM task_groups');
    const usedIds = new Set(used.map((r) => r.group_id));
    const [olds] = await conn.execute('SELECT id FROM lab_groups');
    for (const g of olds) {
      if (!usedIds.has(g.id)) await conn.execute('DELETE FROM lab_groups WHERE id = ?', [g.id]);
    }
    for (let i = 0; i < buckets.length; i++) {
      const gid = 'G_' + nanoid(8);
      await conn.execute('INSERT INTO lab_groups (id, name) VALUES (?, ?)', [gid, prefix + (i + 1) + '组']);
      for (const sid of buckets[i]) {
        await conn.execute('UPDATE students SET group_id = ? WHERE sid = ?', [gid, sid]);
      }
    }
  });

  return {
    groups: await listGroups(),
    students: await listStudents(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

async function ungroupStudents(memberSids) {
  const sids = Array.isArray(memberSids) ? memberSids.map(String) : [];
  if (!sids.length) throw Object.assign(new Error('请先选择学生'), { status: 400 });
  await withTransaction(async (conn) => {
    for (const sid of sids) {
      await conn.execute('UPDATE students SET group_id = NULL WHERE sid = ?', [sid]);
    }
  });
  return {
    groups: await listGroups(),
    students: await listStudents(),
    ungrouped: (await listStudents()).filter((s) => !s.groupId)
  };
}

async function saveStudentFields(sid, patch) {
  const map = {
    status: 'status',
    taskId: 'task_id',
    expId: 'exp_id',
    expName: 'exp_name',
    note: 'note',
    tip: 'tip',
    place: 'place',
    timeText: 'time_text',
    doorClosed: 'door_closed',
    expFlow: 'exp_flow',
    acqPaused: 'acq_paused',
    acq: 'acq_json',
    maxF: 'max_f',
    maxD: 'max_d',
    fractureImg: 'fracture_img',
    fractureType: 'fracture_type',
    fractureConf: 'fracture_conf',
    fractureAnalysis: 'fracture_analysis_json',
    fractureSelf: 'fracture_self_json',
    groupConfirmed: 'group_confirmed',
    personal: 'personal_json',
    personalSubmitted: 'personal_submitted',
    personalSubmittedAt: 'personal_submitted_at',
    personalScore: 'personal_score',
    personalComment: 'personal_comment'
  };
  const sets = [];
  const vals = [];
  Object.keys(patch).forEach((k) => {
    if (!(k in map)) return;
    let v = patch[k];
    if (k === 'acq' || k === 'personal' || k === 'fractureAnalysis' || k === 'fractureSelf') v = JSON.stringify(v == null ? null : v);
    if (k === 'doorClosed' || k === 'acqPaused' || k === 'groupConfirmed' || k === 'personalSubmitted') v = v ? 1 : 0;
    sets.push(map[k] + ' = ?');
    vals.push(v);
  });
  if (!sets.length) return getStudent(sid);
  vals.push(sid);
  await query('UPDATE students SET ' + sets.join(', ') + ' WHERE sid = ?', vals);
  return getStudent(sid);
}

async function ensureGroupReport(taskId, groupId) {
  const rows = await query('SELECT * FROM group_reports WHERE task_id = ? AND group_id = ?', [taskId, groupId]);
  if (!rows[0]) {
    await query(
      `INSERT INTO group_reports (task_id, group_id, data_json, html, confirmed_by_json, submitted, comment)
       VALUES (?, ?, NULL, '', '[]', 0, '')`,
      [taskId, groupId]
    );
    return ensureGroupReport(taskId, groupId);
  }
  const row = rows[0];
  const data = parseJson(row.data_json, null);
  /* 有现场数据时按最新模板重渲，保证曲线/断口图齐全 */
  const html = data ? buildGroupHtml(data) : (row.html || '');
  return {
    taskId: row.task_id,
    groupId: row.group_id,
    data,
    html,
    confirmedBy: parseJson(row.confirmed_by_json, []),
    submitted: !!row.submitted,
    submittedAt: row.submitted_at,
    score: row.score == null ? null : Number(row.score),
    comment: row.comment || '',
    sourceSid: row.source_sid
  };
}

async function saveGroupReport(gr) {
  await query(
    `UPDATE group_reports SET
      data_json = ?, html = ?, confirmed_by_json = ?, submitted = ?, submitted_at = ?,
      score = ?, comment = ?, source_sid = ?
     WHERE task_id = ? AND group_id = ?`,
    [
      gr.data ? JSON.stringify(gr.data) : null,
      gr.html || '',
      JSON.stringify(gr.confirmedBy || []),
      gr.submitted ? 1 : 0,
      gr.submittedAt || null,
      gr.score == null ? null : gr.score,
      gr.comment || '',
      gr.sourceSid || null,
      gr.taskId,
      gr.groupId
    ]
  );
  return ensureGroupReport(gr.taskId, gr.groupId);
}

/** 教师现场扣分：追加一条扣分记录（实验操作记录，扣分制） */
async function addDeduction(taskId, groupId, { itemId, label, points, reason, by }) {
  const gr = await ensureGroupReport(taskId, groupId);
  const catalog = DEDUCTION_CATALOG.find((c) => c.id === itemId);
  const entry = {
    id: 'd_' + nanoid(8),
    itemId: itemId || 'custom',
    label: String(label || (catalog && catalog.label) || '自定义扣分'),
    points: Number(points != null ? points : (catalog ? catalog.points : 0)) || 0,
    reason: String(reason || ''),
    by: by || '',
    at: new Date().toLocaleString()
  };
  gr.deductions = (gr.deductions || []).concat([entry]);
  return saveGroupReport(gr);
}

/** 移除一条扣分记录 */
async function removeDeduction(taskId, groupId, deductionId) {
  const gr = await ensureGroupReport(taskId, groupId);
  gr.deductions = (gr.deductions || []).filter((d) => d.id !== deductionId);
  return saveGroupReport(gr);
}

/** 教师录入试件材质（塑性/脆性兜底基准） */
async function setMaterialType(taskId, groupId, materialType) {
  const gr = await ensureGroupReport(taskId, groupId);
  gr.materialType = materialType || null;
  return saveGroupReport(gr);
}

async function listTasks() {
  const tasks = await query('SELECT * FROM tasks ORDER BY created_at DESC');
  const out = [];
  for (const t of tasks) {
    const gids = await query('SELECT group_id FROM task_groups WHERE task_id = ?', [t.id]);
    out.push({
      id: t.id,
      expId: t.exp_id,
      expName: t.exp_name,
      place: t.place || '',
      timeText: t.time_text || '',
      note: t.note || '',
      tip: t.tip || '',
      teacher: t.teacher,
      createdAt: t.created_at,
      groupIds: gids.map((g) => g.group_id)
    });
  }
  return out;
}

async function createTask({ expId, groupIds, place, timeText, note, tip, teacher }) {
  const exps = await query('SELECT * FROM experiments WHERE id = ?', [expId]);
  if (!exps[0]) throw Object.assign(new Error('实验不存在'), { status: 400 });
  if (!groupIds || !groupIds.length) throw Object.assign(new Error('请选择小组'), { status: 400 });
  const exp = exps[0];
  const tipText = String(tip || '').trim();

  const ungrouped = await query('SELECT sid FROM students WHERE group_id IS NULL OR group_id = ""');
  if (ungrouped.length) {
    throw Object.assign(new Error('还有学生未分配组别，请全部分组后再下发'), { status: 400 });
  }

  for (const gid of groupIds) {
    const members = await query('SELECT sid FROM students WHERE group_id = ?', [gid]);
    if (!members.length) throw Object.assign(new Error('所选小组存在空组，请先完成分组'), { status: 400 });
  }

  const id = 'task_' + nanoid(10);
  const createdAt = new Date().toISOString();

  await withTransaction(async (conn) => {
    await conn.execute(
      `INSERT INTO tasks (id, exp_id, exp_name, place, time_text, note, tip, teacher, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [id, exp.id, exp.name, place || '', timeText || '', note || '', tipText, teacher || '', createdAt]
    );
    for (const gid of groupIds) {
      await conn.execute('INSERT INTO task_groups (task_id, group_id) VALUES (?, ?)', [id, gid]);
      await conn.execute(
        `INSERT INTO group_reports
         (task_id, group_id, data_json, html, confirmed_by_json, submitted, submitted_at, score, comment, source_sid)
         VALUES (?, ?, NULL, '', '[]', 0, NULL, NULL, '', NULL)
         ON DUPLICATE KEY UPDATE
           data_json=NULL, html='', confirmed_by_json='[]', submitted=0, submitted_at=NULL,
           score=NULL, comment='', source_sid=NULL`,
        [id, gid]
      );
      await conn.execute(
        `UPDATE students SET
          status='assigned', task_id=?, exp_id=?, exp_name=?,
          note=?, tip=?, place=?, time_text=?,
          door_closed=0, exp_flow='wait_door', acq_paused=0,
          acq_json='{"points":[],"t0":null}', max_f=0, max_d=0,
          fracture_img=NULL, fracture_type=NULL, fracture_conf=0, fracture_analysis_json=NULL,
          group_confirmed=0,
          personal_json='{}', personal_submitted=0, personal_submitted_at=NULL,
          personal_score=NULL, personal_comment=''
         WHERE group_id=?`,
        [id, exp.id, exp.name, note || '', tipText, place || '', timeText || '', gid]
      );
    }
  });

  const tasks = await listTasks();
  return tasks.find((t) => t.id === id);
}

async function syncGroupDataFromStudent(sid) {
  const s = await getStudent(sid);
  if (!s || !s.taskId || !s.groupId) return null;
  const data = {
    fMax: s.maxF,
    dMax: s.maxD,
    points: (s.acq && s.acq.points) || [],
    fractureType: s.fractureType,
    fractureConf: s.fractureConf,
    fractureImg: s.fractureImg,
    fractureAnalysis: s.fractureAnalysis || null,
    sourceSid: sid,
    note: s.note || ''
  };
  const html = buildGroupHtml(data);
  const gr = await ensureGroupReport(s.taskId, s.groupId);
  gr.data = data;
  gr.html = html;
  gr.sourceSid = sid;
  await saveGroupReport(gr);

  await saveLabProgressForGroup(sid, {
    status: 'lab_done',
    expFlow: 'done_viz',
    maxF: s.maxF,
    maxD: s.maxD,
    acq: s.acq,
    fractureImg: s.fractureImg,
    fractureType: s.fractureType,
    fractureConf: s.fractureConf,
    fractureAnalysis: s.fractureAnalysis || null
  });
  return ensureGroupReport(s.taskId, s.groupId);
}

/** Lab progress rank for comparing group members */
function labProgressRank(s) {
  if (!s) return 0;
  const statusRank = ({ none: 0, assigned: 1, in_lab: 2, lab_done: 3, submitted: 4 })[s.status] || 0;
  const flowRank = ({ wait_door: 0, acquiring: 1, post_break: 2, done_viz: 3 })[s.expFlow] || 0;
  const pts = (s.acq && s.acq.points && s.acq.points.length) || 0;
  return statusRank * 100000 + flowRank * 1000 + Math.min(pts, 999);
}

function pickSharedLabFields(s) {
  return {
    status: s.status === 'submitted' ? 'lab_done' : s.status,
    expFlow: s.expFlow,
    doorClosed: !!s.doorClosed,
    acqPaused: !!s.acqPaused,
    acq: s.acq || { points: [], t0: null },
    maxF: s.maxF,
    maxD: s.maxD,
    fractureImg: s.fractureImg,
    fractureType: s.fractureType,
    fractureConf: s.fractureConf,
    fractureAnalysis: s.fractureAnalysis || null
  };
}

async function listTaskGroupMembers(groupId, taskId) {
  if (!groupId || !taskId) return [];
  const rows = await query(
    'SELECT sid FROM students WHERE group_id = ? AND task_id = ? ORDER BY sid',
    [groupId, taskId]
  );
  return rows.map((r) => r.sid);
}

/**
 * Write lab progress for one student, then mirror shared lab fields to all group mates
 * on the same task. Does not overwrite mates who already submitted personal reports
 * (keeps their status=submitted) but still syncs experiment data.
 */
async function saveLabProgressForGroup(sid, patch) {
  const self = await getStudent(sid);
  if (!self || !self.groupId || !self.taskId) {
    return saveStudentFields(sid, patch);
  }
  await saveStudentFields(sid, patch);
  const updated = await getStudent(sid);
  const shared = pickSharedLabFields(updated);
  const newRank = labProgressRank(updated);
  const mateSids = await listTaskGroupMembers(self.groupId, self.taskId);
  for (const mateSid of mateSids) {
    if (mateSid === sid) continue;
    const mate = await getStudent(mateSid);
    if (!mate) continue;
    // Never pull a teammate backward if they are already further along
    if (labProgressRank(mate) > newRank) continue;
    if (mate.status === 'submitted') {
      const { status, ...rest } = shared;
      await saveStudentFields(mateSid, rest);
    } else {
      await saveStudentFields(mateSid, shared);
    }
  }
  return getStudent(sid);
}

/** If a teammate is further along, pull their lab progress onto this student. */
async function alignStudentWithGroupLab(sid) {
  const self = await getStudent(sid);
  if (!self || !self.groupId || !self.taskId) return self;
  if (self.status === 'none') return self;

  const mateSids = await listTaskGroupMembers(self.groupId, self.taskId);
  let leader = self;
  for (const mateSid of mateSids) {
    const mate = await getStudent(mateSid);
    if (!mate) continue;
    if (labProgressRank(mate) > labProgressRank(leader)) leader = mate;
  }
  if (leader.sid === self.sid) return self;
  if (labProgressRank(leader) <= labProgressRank(self)) return self;

  const shared = pickSharedLabFields(leader);
  if (self.status === 'submitted') {
    const { status, ...rest } = shared;
    return saveStudentFields(sid, rest);
  }
  return saveStudentFields(sid, shared);
}

async function gradingQueue() {
  const rows = await query(`
    SELECT s.*, g.name AS group_name
    FROM students s
    JOIN lab_groups g ON g.id = s.group_id
    WHERE s.status != 'none'
    ORDER BY s.group_id, s.sid
  `);
  const out = [];
  for (const r of rows) {
    const stu = studentRowToObj(r, r.group_name);
    const gr = stu.taskId ? await ensureGroupReport(stu.taskId, stu.groupId) : null;
    out.push({
      sid: stu.sid,
      name: stu.name,
      groupName: stu.groupName,
      expName: stu.expName || '—',
      status: stu.status,
      groupSubmitted: !!(gr && gr.submitted),
      personalSubmitted: !!stu.personalSubmitted,
      groupScore: gr && gr.score != null ? gr.score : null,
      personalScore: stu.personalScore,
      taskId: stu.taskId,
      groupId: stu.groupId
    });
  }
  return out;
}

async function createSession({ role, studentId, teacherName }) {
  const token = nanoid(24);
  await query(
    `INSERT INTO sessions (token, role, student_id, teacher_name, created_at) VALUES (?, ?, ?, ?, ?)`,
    [token, role, studentId || null, teacherName || null, new Date().toISOString()]
  );
  return token;
}

/**
 * 账号密码登录：系统自动识别教师/学生角色
 * - 教师：username + password
 * - 学生：学号(sid) + password
 */
async function loginWithPassword(account, password) {
  const acc = String(account || '').trim();
  const pwd = String(password || '');
  if (!acc || !pwd) throw Object.assign(new Error('请输入账号和密码'), { status: 400 });

  const teachers = await query(
    'SELECT id, name, username, password_hash FROM teachers WHERE username = ? LIMIT 1',
    [acc]
  );
  if (teachers[0]) {
    if (!verifyPassword(pwd, teachers[0].password_hash)) {
      throw Object.assign(new Error('账号或密码错误'), { status: 401 });
    }
    const token = await createSession({ role: 'teacher', teacherName: teachers[0].name });
    return { token, role: 'teacher', teacherName: teachers[0].name };
  }

  const students = await query('SELECT sid, name, password_hash FROM students WHERE sid = ? LIMIT 1', [acc]);
  if (students[0]) {
    if (!verifyPassword(pwd, students[0].password_hash)) {
      throw Object.assign(new Error('账号或密码错误'), { status: 401 });
    }
    const token = await createSession({ role: 'student', studentId: students[0].sid });
    const student = await getStudent(students[0].sid);
    return { token, role: 'student', student };
  }

  throw Object.assign(new Error('账号或密码错误'), { status: 401 });
}

async function getSession(token) {
  if (!token) return null;
  const rows = await query('SELECT * FROM sessions WHERE token = ?', [token]);
  return rows[0] || null;
}

async function deleteSession(token) {
  if (!token) return;
  await query('DELETE FROM sessions WHERE token = ?', [token]);
}

async function sessionSnapshot(token) {
  const sess = await getSession(token);
  if (!sess) return null;
  const meta = await listMeta();
  if (sess.role === 'teacher') {
    return {
      role: 'teacher',
      teacherName: sess.teacher_name,
      tasks: await listTasks(),
      grading: await gradingQueue(),
      meta
    };
  }
  const student = await alignStudentWithGroupLab(sess.student_id);
  if (!student) return null;
  const groupReport = student.taskId ? await ensureGroupReport(student.taskId, student.groupId) : null;
  const mates = meta.groups.find((g) => g.id === student.groupId);
  return {
    role: 'student',
    student,
    mates: mates ? mates.members : [],
    groupReport,
    meta
  };
}

module.exports = {
  escHtml, calcWork, buildGroupHtml,
  DEDUCTION_CATALOG, OPERATION_BASE_SCORE, computeOperationScore,
  getStudent, saveStudentFields, listMeta, listGroups, listTasks, listStudents,
  importRoster, createGroup, renameGroup, deleteGroup, setGroupMembers, moveStudentsToGroup, ungroupStudents,
  autoGroup,
  createTask, ensureGroupReport, saveGroupReport, syncGroupDataFromStudent,
  addDeduction, removeDeduction, setMaterialType,
  saveLabProgressForGroup, alignStudentWithGroupLab,
  gradingQueue, createSession, getSession, deleteSession, sessionSnapshot,
  loginWithPassword
};
