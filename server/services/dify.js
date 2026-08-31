'use strict';

/**
 * Dify 接入：报告评阅工作流 +（预留）对话。
 *
 * 环境变量：
 *   DIFY_ENABLED=true
 *   DIFY_API_URL=https://api.dify.ai/v1
 *   DIFY_GRADING_API_KEY=app-xxxx   # 评阅工作流（优先）
 *   DIFY_API_KEY=app-xxxx           # 通用回退
 */

function isEnabled() {
  return String(process.env.DIFY_ENABLED || '').toLowerCase() === 'true'
    && !!String(process.env.DIFY_API_URL || '').trim()
    && !!(process.env.DIFY_GRADING_API_KEY || process.env.DIFY_API_KEY);
}

function apiBase() {
  return String(process.env.DIFY_API_URL || '').replace(/\/+$/, '');
}

function gradingApiKey() {
  return String(process.env.DIFY_GRADING_API_KEY || process.env.DIFY_API_KEY || '').trim();
}

function getStatus() {
  const enabled = isEnabled();
  return {
    enabled,
    provider: 'dify',
    ready: enabled,
    gradingReady: enabled && !!gradingApiKey(),
    message: enabled
      ? 'Dify 已启用，可用于报告 AI 评阅'
      : '未接入。配置 DIFY_ENABLED / DIFY_API_URL / DIFY_GRADING_API_KEY 后启用'
  };
}

function htmlToText(html) {
  return String(html == null ? '' : html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>/gi, '\n')
    .replace(/<\/h[1-6]>/gi, '\n')
    .replace(/<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/\s+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();
}

function clampScore(n) {
  const x = Number(n);
  if (!Number.isFinite(x)) return null;
  return Math.max(0, Math.min(100, Math.round(x * 10) / 10));
}

/** 去掉平台注入的章节标题后，统计有效汉字/字母数量 */
function measureReportContent(reportText) {
  let t = htmlToText(reportText || '');
  t = t
    .replace(/一、实验步骤描述/g, ' ')
    .replace(/二、数据分析与误差/g, ' ')
    .replace(/三、总结与反思/g, ' ')
    .replace(/附件[：:].*/g, ' ')
    .replace(/（报告正文为空）/g, ' ')
    .replace(/\(报告正文为空\)/g, ' ')
    .replace(/^[—\-–]+$/gm, ' ');
  const chars = t.match(/[\u4e00-\u9fffA-Za-z]/g) || [];
  return { text: t.trim(), charCount: chars.length };
}

function emptyReportGrade(reportType, charCount) {
  const kind = reportType === 'group' ? '组报告' : '个人报告';
  let score = 12;
  let comment;
  if (charCount <= 0) {
    score = 8;
    comment = kind + '正文几乎为空（步骤/分析/反思未见有效内容），无法体现实验理解与数据处理。建议退回重写后再评。参考分 '
      + score + '。';
  } else if (charCount < 40) {
    score = 18;
    comment = kind + '有效文字过少（约 ' + charCount + ' 字），缺少具体数据、曲线/断口分析与误差讨论，内容不充分。建议补充后再评。参考分 '
      + score + '。';
  } else {
    score = 28;
    comment = kind + '篇幅偏短且信息不足，尚未达到合格报告要求。请结合现场 Fmax、ΔLmax、断口结论做定量分析。参考分 '
      + score + '。';
  }
  return { score, comment, source: 'empty-guard', charCount };
}

function parseGradePayload(outputs, rawText) {
  const out = outputs || {};
  let score = clampScore(out.score != null ? out.score : out.Score);
  let comment = out.comment != null ? String(out.comment) : (out.Comment != null ? String(out.Comment) : '');

  if (score == null || !comment) {
    const text = String(rawText || out.text || out.result || out.output || '').trim();
    const m = text.match(/\{[\s\S]*\}/);
    if (m) {
      try {
        const data = JSON.parse(m[0]);
        if (score == null) score = clampScore(data.score);
        if (!comment) comment = String(data.comment || '').trim();
      } catch (_) { /* ignore */ }
    }
    if (!comment && text) comment = text.slice(0, 800);
  }

  if (score == null) score = 40;
  if (!comment) comment = 'AI 评阅结果不完整，请教师人工核阅后给分。';
  return { score, comment };
}

/**
 * 调用 Dify Workflow（blocking）
 * @param {object} inputs
 * @param {string} user
 * @param {string} [apiKey]
 */
async function runWorkflow(inputs, user, apiKey) {
  if (!isEnabled()) {
    const err = new Error('Dify 未启用，请配置 DIFY_* 环境变量');
    err.status = 503;
    throw err;
  }
  const key = String(apiKey || gradingApiKey() || '').trim();
  if (!key) {
    const err = new Error('缺少 DIFY_GRADING_API_KEY / DIFY_API_KEY');
    err.status = 503;
    throw err;
  }
  const url = apiBase() + '/workflows/run';
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + key,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      inputs: inputs || {},
      response_mode: 'blocking',
      user: String(user || 'lab-teacher')
    })
  });
  let data = null;
  try { data = await res.json(); } catch (_) { data = null; }
  if (!res.ok) {
    const msg = (data && (data.message || data.error || data.code)) || ('Dify 调用失败 HTTP ' + res.status);
    const err = new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    err.status = res.status >= 400 && res.status < 600 ? res.status : 502;
    throw err;
  }
  return data;
}

/**
 * 本地启发式评阅（Dify 未配置时的演示兜底）
 */
function localGradeFallback(payload) {
  const text = String(payload.reportText || '');
  const measured = measureReportContent(text);
  if (measured.charCount < 60) {
    return emptyReportGrade(payload.reportType, measured.charCount);
  }
  const lab = payload.labData || {};
  let score = 55;
  const notes = [];

  if (text.length > 80) { score += 8; notes.push('正文有一定篇幅'); }
  else notes.push('正文偏短，建议补充数据分析');

  if (/目的|设备|过程|结论|误差|反思|断口|曲线|Fmax|σ|延伸/.test(text)) {
    score += 10;
    notes.push('覆盖了实验报告常见要素');
  }
  if (lab.fMax != null && String(lab.fMax) && text.indexOf(String(Math.round(Number(lab.fMax)))) >= 0) {
    score += 8;
    notes.push('提到了与现场接近的 Fmax');
  } else if (lab.fMax != null) {
    notes.push('建议明确引用现场 Fmax=' + Number(lab.fMax).toFixed(2) + ' kN');
  }
  if (lab.fractureType === 'ductile' && /塑|杯锥|纤维|剪切唇/.test(text)) {
    score += 8;
    notes.push('断口描述与塑性结论大体一致');
  } else if (lab.fractureType === 'brittle' && /脆|解理|平整|放射/.test(text)) {
    score += 8;
    notes.push('断口描述与脆性结论大体一致');
  } else if (lab.fractureType) {
    notes.push('请核对断口类型表述是否与识别结果一致');
  }
  if (payload.reportType === 'personal' && /误差|不足|改进|反思/.test(text)) {
    score += 6;
    notes.push('个人报告含误差/反思表述');
  }
  score = clampScore(Math.min(92, score));
  const kind = payload.reportType === 'group' ? '组报告' : '个人报告';
  const comment = '【本地演示评阅，未连接 Dify】' + kind + '参考分 '
    + score + '。' + notes.join('；')
    + '。配置 DIFY_GRADING_API_KEY 并导入 dify/lab-report-grading.yml 后可切换为正式 AI 评阅。';
  return { score, comment, source: 'local-fallback', charCount: measured.charCount };
}

/**
 * @param {{
 *   reportType: 'group'|'personal',
 *   studentName: string,
 *   studentSid: string,
 *   groupName?: string,
 *   expName?: string,
 *   reportText: string,
 *   labData?: object,
 *   user?: string
 * }} payload
 */
async function gradeReport(payload) {
  const reportType = payload.reportType === 'personal' ? 'personal' : 'group';
  const reportText = htmlToText(payload.reportText || '').slice(0, 14000);
  const labData = payload.labData || {};
  const measured = measureReportContent(reportText);

  const inputs = {
    report_type: reportType,
    student_name: String(payload.studentName || ''),
    student_sid: String(payload.studentSid || ''),
    group_name: String(payload.groupName || ''),
    exp_name: String(payload.expName || ''),
    report_text: reportText || '（报告正文为空：步骤/分析/反思均无有效文字）',
    lab_data: JSON.stringify(labData, null, 2),
    content_chars: String(measured.charCount)
  };

  if (!isEnabled()) {
    return localGradeFallback({
      reportType,
      reportText,
      labData
    });
  }

  /* 空报告也走 Dify：由工作流「个人/组」分支按提示词给分 */
  const raw = await runWorkflow(inputs, payload.user || payload.studentSid || 'teacher');
  const outputs = (raw && raw.data && raw.data.outputs) || (raw && raw.outputs) || {};
  const graded = parseGradePayload(outputs, outputs.text || outputs.result);
  return {
    score: graded.score,
    comment: graded.comment,
    source: 'dify',
    workflowRunId: raw && raw.data && (raw.data.id || raw.data.workflow_run_id),
    charCount: measured.charCount
  };
}

async function chat(/* payload */) {
  if (!isEnabled()) {
    const err = new Error('Dify 未启用');
    err.status = 503;
    throw err;
  }
  const err = new Error('对话能力请使用物小智本地助手；报告评阅请用 gradeReport 工作流');
  err.status = 501;
  throw err;
}

module.exports = {
  isEnabled,
  getStatus,
  chat,
  runWorkflow,
  gradeReport,
  htmlToText
};
