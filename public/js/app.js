'use strict';

(function () {
  var api = window.LabAPI;
  var PAGE_KEY = 'lab-platform-page';
  var TAB_KEY = 'lab-platform-report-tab';

  var ui = {
    page: localStorage.getItem(PAGE_KEY) || null,
    reportTab: localStorage.getItem(TAB_KEY) || 'group',
    snap: null,
    acqTimer: null,
    localPoints: null,
    localMaxF: 0,
    localMaxD: 0,
    syncCounter: 0,
    acqStartedAt: null,
    ruptureHinted: false,
    labView: null, // 'acq' | 'frac' — 采集完成后在步骤间切换
    assignDraft: { expId: '', timeText: '', place: '', note: '', tip: '' },
    fracStepId: 'original',
    fracBusy: false,
    fracCamStream: null,
    tensileCurve: null,
    curvePlayIdx: 0
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function formatPersonalHtml(raw) {
    if (!raw) return '<p class="sub">—</p>';
    if (window.LabRichText) {
      var html = window.LabRichText.plainToHtml(raw);
      return html || '<p class="sub">—</p>';
    }
    return '<p>' + esc(raw).replace(/\n/g, '<br>') + '</p>';
  }

  function statusLabel(st) {
    return ({ none: '无任务', assigned: '待实验', in_lab: '实验中', lab_done: '待交报告', submitted: '已提交' })[st] || st;
  }

  function statusTag(st) {
    var cls = st === 'submitted' ? 'ok' : (st === 'in_lab' ? 'warn' : (st === 'none' ? 'muted' : ''));
    return '<span class="tag ' + cls + '">' + esc(statusLabel(st)) + '</span>';
  }

  function calcWork(points) {
    var work = 0;
    for (var i = 1; i < (points || []).length; i++) {
      var dd = points[i].d - points[i - 1].d;
      if (dd > 0) work += 0.5 * (points[i].f + points[i - 1].f) * dd;
    }
    return work;
  }

  /* 试样几何（模拟用，后续接真实设备时可改为下发参数） */
  var SPEC_A0 = 78.54; // mm²，φ10
  var SPEC_L0 = 50;    // mm
  var LOAD_RATE = 2.0; // mm/min

  function formatMmSs(ms) {
    var sec = Math.max(0, Math.floor(Number(ms || 0) / 1000));
    var m = Math.floor(sec / 60);
    var s = sec % 60;
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  /** 根据位移判断拉伸阶段（阈值按学校拉力机实测曲线量级） */
  function detectTensileStage(points, ruptured) {
    if (ruptured) return 4;
    if (!points || !points.length) return -1;
    var d = points[points.length - 1].d;
    var maxF = 0;
    points.forEach(function (p) { if (p.f > maxF) maxF = p.f; });
    var curF = points[points.length - 1].f;
    if (d < 1.2) return 0;
    if (d < 3.0) return 1;
    /* 峰值后力值明显回落 → 颈缩 */
    if (maxF > 1 && curF < maxF * 0.97 && d >= 18) return 3;
    if (d < 20.5) return 2;
    return 3;
  }

  function simForceAt(d) {
    /* 实测曲线未加载时的兜底形态 */
    var f;
    if (d < 0.55) f = d * 42;
    else if (d < 1.0) f = 23.1 + (d - 0.55) * 2.2;
    else if (d < 2.5) f = 24.1 + Math.sin((d - 1.0) * 4) * 0.35;
    else if (d < 7.0) f = 24.0 + (d - 2.5) * 1.78;
    else if (d < 10) f = 32.0 - (d - 7.0) * 1.0;
    else if (d < 14.5) f = 29.0 - (d - 10) * 1.2;
    else f = Math.max(4, 23.6 - (d - 14.5) * 8);
    return Math.max(0, f);
  }

  function ensureTensileCurve() {
    if (ui.tensileCurve && ui.tensileCurve.points && ui.tensileCurve.points.length) {
      return Promise.resolve(ui.tensileCurve);
    }
    if (window.LabTensileCurve && window.LabTensileCurve.points && window.LabTensileCurve.points.length) {
      ui.tensileCurve = window.LabTensileCurve;
      return Promise.resolve(ui.tensileCurve);
    }
    return fetch('/data/tensile-curve.json?v=2')
      .then(function (r) {
        if (!r.ok) throw new Error('加载实测曲线失败 HTTP ' + r.status);
        return r.json();
      })
      .then(function (data) {
        if (!data || !data.points || !data.points.length) {
          throw new Error('实测曲线文件为空');
        }
        ui.tensileCurve = data;
        return data;
      });
  }

  function drawChart(canvas, points, opts) {
    if (!canvas) return;
    opts = opts || {};
    var parent = canvas.parentElement;
    if (parent && parent.classList.contains('rm-chart-wrap')) {
      var cw = parent.clientWidth || 700;
      var ch = parent.clientHeight || 360;
      var dpr = window.devicePixelRatio || 1;
      canvas.width = Math.floor(cw * dpr);
      canvas.height = Math.floor(ch * dpr);
      canvas.style.width = cw + 'px';
      canvas.style.height = ch + 'px';
    }
    var ctx = canvas.getContext('2d');
    var w = canvas.width, h = canvas.height;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    if (parent && parent.classList.contains('rm-chart-wrap')) {
      var dpr2 = window.devicePixelRatio || 1;
      ctx.setTransform(dpr2, 0, 0, dpr2, 0, 0);
      w = parent.clientWidth || 700;
      h = parent.clientHeight || 360;
    }
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, w, h);

    var padL = 54, padR = 16, padT = 28, padB = 42;
    var ref = window.LabTensileCurve || ui.tensileCurve || null;
    var dataMaxD = 0;
    var dataMaxF = 0;
    (points || []).forEach(function (p) {
      if (p.d > dataMaxD) dataMaxD = p.d;
      if (p.f > dataMaxF) dataMaxF = p.f;
    });
    /* 有实测曲线时按全量程建轴，避免前期局部放大看起来像假曲线 */
    var maxD = Math.max(dataMaxD, (ref && ref.dMax) || 0, 1) * 1.02;
    var maxF = Math.max(dataMaxF, (ref && ref.fMax) || 0, 1) * 1.05;
    if (opts.fitData) {
      maxD = Math.max(dataMaxD * 1.08, 1);
      maxF = Math.max(dataMaxF * 1.1, 1);
    }

    function xOf(d) { return padL + (d / maxD) * (w - padL - padR); }
    function yOf(f) { return (h - padB) - (f / maxF) * (h - padT - padB); }

    function niceStep(span, target) {
      var raw = span / Math.max(target, 1);
      var pow = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
      var n = raw / pow;
      var step = n < 1.5 ? 1 : (n < 3.5 ? 2 : (n < 7.5 ? 5 : 10));
      return step * pow;
    }

    /* 网格 + 刻度 */
    ctx.strokeStyle = '#EEF2F4';
    ctx.lineWidth = 1;
    ctx.fillStyle = '#5B6B78';
    ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
    var xStep = niceStep(maxD, 5);
    var yStep = niceStep(maxF, 5);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (var xv = 0; xv <= maxD + 1e-9; xv += xStep) {
      var x = xOf(xv);
      ctx.beginPath();
      ctx.moveTo(x, padT);
      ctx.lineTo(x, h - padB);
      ctx.stroke();
      ctx.fillText(xv >= 10 ? xv.toFixed(0) : xv.toFixed(1), x, h - padB + 8);
    }
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (var yv = 0; yv <= maxF + 1e-9; yv += yStep) {
      var y = yOf(yv);
      ctx.beginPath();
      ctx.moveTo(padL, y);
      ctx.lineTo(w - padR, y);
      ctx.stroke();
      ctx.fillText(yv >= 10 ? yv.toFixed(0) : yv.toFixed(1), padL - 8, y);
    }

    /* 坐标轴 */
    ctx.strokeStyle = '#94A3B8';
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(padL, padT);
    ctx.lineTo(padL, h - padB);
    ctx.lineTo(w - padR, h - padB);
    ctx.stroke();

    /* 轴标题 */
    ctx.fillStyle = '#334155';
    ctx.font = '600 12px sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'bottom';
    ctx.fillText('F / kN', padL + 4, padT - 8);
    ctx.textAlign = 'right';
    ctx.textBaseline = 'alphabetic';
    ctx.fillText('ΔL / mm', w - padR, h - 8);

    if (!points || !points.length) {
      ctx.fillStyle = '#8A9AA6';
      ctx.font = '13px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('等待采集数据…', w / 2, h / 2);
      return;
    }

    /* 填充 */
    var grad = ctx.createLinearGradient(0, padT, 0, h - padB);
    grad.addColorStop(0, 'rgba(14,124,139,0.22)');
    grad.addColorStop(1, 'rgba(14,124,139,0.02)');
    ctx.beginPath();
    points.forEach(function (p, i) {
      var px = xOf(p.d), py = yOf(p.f);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.lineTo(xOf(points[points.length - 1].d), h - padB);
    ctx.lineTo(xOf(points[0].d), h - padB);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    /* 曲线 */
    ctx.strokeStyle = '#0E7C8B';
    ctx.lineWidth = 2;
    ctx.lineJoin = 'round';
    ctx.beginPath();
    points.forEach(function (p, i) {
      var px = xOf(p.d), py = yOf(p.f);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.stroke();

    /* 阶段标注虚线 */
    if (opts.stageMarks !== false) {
      var marks = [
        { d: 0.8, label: '弹性段', color: '#0E7C8B' },
        { d: 2.0, label: '屈服点', color: '#C8810A' },
        { d: 10.0, label: '强化', color: '#0C6A78' },
        { d: 21.0, label: '颈缩', color: '#C8810A' }
      ];
      var curD = points[points.length - 1].d;
      marks.forEach(function (m) {
        if (curD < m.d * 0.85 || m.d > maxD) return;
        var mx = xOf(m.d);
        ctx.save();
        ctx.setLineDash([4, 4]);
        ctx.strokeStyle = m.color;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(mx, padT);
        ctx.lineTo(mx, h - padB);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = m.color;
        ctx.font = '600 11px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'bottom';
        ctx.fillText(m.label, mx, padT - 4);
        ctx.restore();
      });
    }

    if (opts.markRupture) {
      var last = points[points.length - 1];
      var lx = xOf(last.d), ly = yOf(last.f);
      ctx.fillStyle = '#D4453A';
      ctx.beginPath();
      ctx.arc(lx, ly, 5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.fillStyle = '#D4453A';
      ctx.font = '600 12px sans-serif';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText('断裂', Math.min(lx + 8, w - 48), Math.max(ly - 8, padT + 12));
    }
  }

  function renderStageTimeline(stageIdx) {
    var stages = [
      { name: '弹性段', meta: 'σ≤305MPa' },
      { name: '屈服', meta: 'σ≈305MPa' },
      { name: '强化', meta: 'σ↑' },
      { name: '颈缩', meta: 'σ↓' },
      { name: '断裂', meta: '—' }
    ];
    var html = '<div class="rm-timeline" id="rm-timeline">';
    stages.forEach(function (st, i) {
      var state = 'pending';
      if (stageIdx > i) state = 'done';
      else if (stageIdx === i) state = (i === 4 ? 'break' : 'active');
      var leftLine = i === 0
        ? '<div class="rm-stage-line" style="visibility:hidden"></div>'
        : '<div class="rm-stage-line' + (stageIdx >= i ? ' on' : '') + '"></div>';
      var rightLine = i === stages.length - 1
        ? '<div class="rm-stage-line" style="visibility:hidden"></div>'
        : '<div class="rm-stage-line' + (stageIdx > i ? ' on' : '') + '"></div>';
      html += '<div class="rm-stage" data-st="' + state + '" data-stage="' + i + '">'
        + '<div class="rm-stage-top">' + leftLine + '<div class="rm-stage-dot"></div>' + rightLine + '</div>'
        + '<div class="rm-stage-name">' + st.name + '</div>'
        + '<div class="rm-stage-meta" data-stage-meta="' + i + '">' + st.meta + '</div>'
        + '</div>';
    });
    return html + '</div>';
  }

  /** mode: 'live' | 'done' */
  function renderRealtimeMonitor(s, mode) {
    var done = mode === 'done';
    var pts0 = (s.acq && s.acq.points) || ui.localPoints || [];
    var stage0 = done ? 4 : detectTensileStage(pts0, false);
    var paused = !done && !!s.acqPaused;
    var last0 = pts0.length ? pts0[pts0.length - 1] : { f: 0, d: 0 };
    var stress0 = (last0.f * 1000) / SPEC_A0;
    var maxF = Number(s.maxF || ui.localMaxF || last0.f || 0);
    var maxD = Number(s.maxD || ui.localMaxD || last0.d || 0);
    var sigb0 = (maxF * 1000) / SPEC_A0;
    var work0 = calcWork(pts0);
    var elapsed0 = ui.acqStartedAt
      ? (Date.now() - ui.acqStartedAt)
      : (pts0.length ? pts0.length * 120 : 0);
    if (done && pts0.length && pts0[0].t && pts0[pts0.length - 1].t) {
      elapsed0 = pts0[pts0.length - 1].t - pts0[0].t;
    }

    var stageNames = ['弹性段', '屈服', '强化', '颈缩', '断裂'];
    var stageSummary = done
      ? '5 阶段 · 4 已完成 · 已断裂'
      : ('5 阶段 · 当前：' + (stageNames[Math.max(0, stage0)] || '等待'));

    var badgeColor = done ? 'var(--ok)' : (paused ? 'var(--warn)' : 'var(--ok)');
    var badgeText = done ? '已完成' : (paused ? '已暂停' : '采集中');
    var statusClass = done ? 'is-break' : (paused ? 'is-paused' : 'is-live');
    var statusColor = done ? 'var(--danger)' : (paused ? 'var(--warn)' : 'var(--info)');
    var statusTitle = done ? '试样已断裂' : (paused ? '采集已暂停' : '拉力机采集中');
    var statusDesc = done
      ? ('数据采集已完成 · F<sub>max</sub> ' + maxF.toFixed(2) + ' kN · 曲线已固化')
      : (paused ? '点击继续采集恢复数据流' : '学校拉力机实测回放 · Fmax≈34.9 kN');

    var actions = '';
    if (!done) {
      actions = '<div class="row" style="gap:.4rem">'
        + (!paused
          ? '<button type="button" class="btn btn-ghost btn-sm" id="btn-acq-stop">暂停采集</button>'
          : '<button type="button" class="btn btn-primary btn-sm" id="btn-acq-resume">继续采集</button>')
        + '<button type="button" class="btn btn-primary btn-sm" id="btn-rupture">标记样件已断裂</button>'
        + '</div>';
    } else {
      actions = '<button type="button" class="btn btn-primary btn-sm" data-lab-view="frac">前往断口确认 →</button>';
    }

    return '<div class="card" style="padding:1.15rem 1.2rem" data-rm-mode="' + (done ? 'done' : 'live') + '">'
      + '<div class="row" style="justify-content:space-between;margin-bottom:.85rem;flex-wrap:wrap;gap:.5rem">'
      +   '<h3 class="section-title" style="margin:0">实时实验监测</h3>'
      +   '<span class="tag">' + esc(s.expName || '材料拉伸') + (s.groupName ? ' · ' + esc(s.groupName) : '') + '</span>'
      + '</div>'
      + '<div class="rm-alert ' + statusClass + '" id="rm-status-bar">'
      +   '<div style="flex:1;min-width:0;padding-left:.25rem;display:flex;align-items:center;gap:.55rem;flex-wrap:wrap">'
      +     '<strong id="rm-status-title" style="font-size:.9rem;color:' + statusColor + '">' + statusTitle + '</strong>'
      +     '<span style="color:var(--border)">·</span>'
      +     '<span class="sub" id="rm-status-desc">' + statusDesc + '</span>'
      +   '</div>'
      +   actions
      + '</div>'
      + '<div class="rm-main">'
      +   '<div class="card rm-panel-left" style="margin:0;box-shadow:var(--shadow)">'
      +     '<div class="row" style="justify-content:space-between;margin-bottom:1rem">'
      +       '<div style="font-weight:600;font-size:.95rem">' + (done ? '关键结果' : '实时数据') + '</div>'
      +       '<span id="rm-live-badge" style="display:inline-flex;align-items:center;gap:.35rem;font-size:.72rem;font-weight:600;color:' + badgeColor + '">'
      +         '<span style="width:8px;height:8px;border-radius:50%;background:currentColor"></span>'
      +         badgeText + '</span>'
      +     '</div>'
      +     '<div class="rm-data-stack">'
      +       '<div class="rm-data-block"><div class="rm-data-label">' + (done ? '峰值力' : '当前力') + '</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-f">' + (done ? maxF : Number(last0.f)).toFixed(2) + '</span><span class="rm-data-unit">kN</span></div></div>'
      +       '<div class="rm-data-divider"></div>'
      +       '<div class="rm-data-block"><div class="rm-data-label">' + (done ? '最大位移' : '位 移') + '</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-d">' + (done ? maxD : Number(last0.d)).toFixed(2) + '</span><span class="rm-data-unit">mm</span></div></div>'
      +       '<div class="rm-data-divider"></div>'
      +       '<div class="rm-data-block"><div class="rm-data-label">应 力</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-stress">' + (done ? sigb0 : stress0).toFixed(1) + '</span><span class="rm-data-unit">MPa</span></div></div>'
      +       '<div class="rm-data-divider"></div>'
      +       '<div class="rm-data-block"><div class="rm-data-label">应 变</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-strain">' + ((done ? maxD : last0.d) / SPEC_L0 * 100).toFixed(2) + '</span><span class="rm-data-unit">%</span></div></div>'
      +       '<div class="rm-data-divider"></div>'
      +       '<div class="rm-data-block"><div class="rm-data-label">加载时间</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-time">' + formatMmSs(elapsed0) + '</span><span class="rm-data-unit">mm:ss</span></div></div>'
      +       '<div class="rm-data-divider"></div>'
      +       '<div class="rm-data-block"><div class="rm-data-label">加载速率</div>'
      +         '<div class="rm-data-value"><span class="rm-data-num" id="rd-rate">' + LOAD_RATE.toFixed(1) + '</span><span class="rm-data-unit">mm/min</span></div></div>'
      +     '</div>'
      +     '<div style="margin-top:1.1rem;padding-top:.85rem;border-top:1px solid var(--border);font-size:.72rem;color:var(--info);font-weight:600">'
      +       (done ? '曲线已固化 · 点击上方步骤可切换到断口确认' : '实测回放 · 力(kN) / 位移(mm) · 数据已缓存')
      +     '</div>'
      +   '</div>'
      +   '<div class="card rm-panel-right" style="margin:0;box-shadow:var(--shadow)">'
      +     '<div class="row" style="justify-content:space-between;margin-bottom:.9rem;flex-wrap:wrap;gap:.5rem">'
      +       '<div style="font-weight:600;font-size:.95rem">力-位移曲线</div>'
      +       '<div class="row" style="gap:.85rem;font-size:.78rem;color:var(--muted)">'
      +         '<span style="display:inline-flex;align-items:center;gap:.35rem"><span style="width:14px;height:3px;background:var(--blue);border-radius:2px;display:inline-block"></span>力-位移</span>'
      +         '<span style="display:inline-flex;align-items:center;gap:.35rem"><span style="width:12px;height:0;border-top:2px dashed var(--warn);display:inline-block"></span>关键阶段</span>'
      +       '</div>'
      +     '</div>'
      +     '<div class="rm-chart-wrap"><canvas id="chart-fd"></canvas></div>'
      +     '<div class="rm-chart-foot">'
      +       '<div class="row" style="gap:1rem">'
      +         '<span>峰值力 <strong id="lm-maxf">' + maxF.toFixed(2) + ' kN</strong></span>'
      +         '<span style="color:var(--border)">|</span>'
      +         '<span>抗拉强度 <strong id="lm-sigb">' + sigb0.toFixed(0) + ' MPa</strong></span>'
      +         '<span style="color:var(--border)">|</span>'
      +         '<span>总位移 <strong id="lm-maxd">' + maxD.toFixed(2) + ' mm</strong></span>'
      +         '<span style="color:var(--border)">|</span>'
      +         '<span>功 <strong id="lm-work">' + work0.toFixed(1) + ' J</strong></span>'
      +       '</div>'
      +       '<span id="rm-curve-note">' + (done ? '实验已完成 · 曲线已固化' : (paused ? '已暂停 · 曲线冻结' : '实时刷新中')) + '</span>'
      +     '</div>'
      +   '</div>'
      + '</div>'
      + '<div class="card" style="margin:0;box-shadow:var(--shadow)">'
      +   '<div class="row" style="justify-content:space-between;margin-bottom:1.1rem">'
      +     '<div style="font-weight:600;font-size:.95rem">实验阶段进度</div>'
      +     '<span class="sub" id="rm-stage-summary">' + stageSummary + '</span>'
      +   '</div>'
      +   renderStageTimeline(stage0)
      + '</div>'
      + '</div>';
  }

  function fracStepMeta(stepId, pipeline) {
    var fallback = {
      original: {
        title: '原图', method: '采集输入',
        desc: '保留断口宏观形貌，作为后续处理与标注基准。',
        calc: '输入 JPEG/PNG → Base64 解码 → 最长边缩至 ≤900 px。'
      },
      gray: {
        title: '灰度', method: 'BGR→Gray',
        desc: '去掉颜色干扰，只保留亮度，便于阈值分割与边缘检测。',
        calc: 'cvtColor(BGR2GRAY) + GaussianBlur(5×5)。'
      },
      thresh: {
        title: '阈值分割', method: 'Otsu 自动阈值',
        desc: '自动区分暗色断口与背景，得到前景掩膜。',
        calc: 'THRESH_BINARY+OTSU；均值偏亮则取反，使暗色断口为前景。'
      },
      edges: {
        title: '边缘检测', method: 'Canny',
        desc: '提取纹理边缘；边缘密度参与塑性/脆性打分。',
        calc: 'Canny(60,160)；纹理密度 = 掩膜内边缘像素 / 掩膜像素。'
      },
      contour: {
        title: '轮廓提取', method: '最大外轮廓',
        desc: '提取断口外轮廓并计算面积、圆度、实心度等几何特征。',
        calc: '圆度=4πA/P²，实心度=A/凸包面积；用于剪切唇等打分。'
      }
    };
    var meta = fallback[stepId] || { title: '处理步骤', method: '', desc: '', calc: '' };
    (pipeline || []).forEach(function (p) {
      if (p.id === stepId) {
        meta = {
          title: p.title || meta.title,
          method: p.method || meta.method,
          desc: p.desc || meta.desc,
          calc: p.calc || meta.calc
        };
      }
    });
    return meta;
  }

  function defaultAlgorithmGuide() {
    return {
      title: 'OpenCV 宏观断口分析（教学辅助）',
      overview: '用 OpenCV 做宏观图像处理，再按五项形貌/曲线证据加权打分，比较塑性与脆性得分给出结论。属教学启发式判别，不能替代金相/SEM 标准鉴定。',
      pipelineSteps: [
        { step: 1, name: '原图预处理', algo: '解码 + 等比缩放', detail: '最长边 ≤900 px，保留宏观杯锥/平整等可辨特征。' },
        { step: 2, name: '灰度与平滑', algo: 'BGR→Gray + GaussianBlur(5×5)', detail: '去掉颜色，降低噪声，供阈值与边缘使用。' },
        { step: 3, name: '前景分割', algo: 'Otsu 自动阈值', detail: '分离暗色断口与背景；必要时反相，保证断口为前景。' },
        { step: 4, name: '纹理边缘', algo: 'Canny(60,160)', detail: '在掩膜内统计边缘占比，作为断面粗糙/纤维状纹理代理。' },
        { step: 5, name: '几何与分区', algo: '最大外轮廓 + 暗度/局部方差', detail: '算圆度、实心度；探测中心偏暗纤维区与放射区包围盒。' },
        { step: 6, name: '曲线特征', algo: '力—位移末端统计', detail: '由 Fmax、末端跌落比与延伸量合成延性指标，与断口交叉验证。' },
        { step: 7, name: '五项加权分类', algo: '中性带不计分', detail: '分项落入塑性/脆性阈值则累加权重，总分高者判型；置信度由分差比例映射。' }
      ],
      features: [
        { name: '圆度 roundness', formula: '4π × 面积 / 周长²', meaning: '越接近 1 轮廓越圆；杯锥投影常落在中等偏高区间。' },
        { name: '实心度 solidity', formula: '轮廓面积 / 凸包面积', meaning: '越低外缘越凹凸；偏低常作为剪切唇（塑性）迹象。' },
        { name: '断面平整度 flatness', formula: '1 / (1 + 55 × 局部灰度方差归一化值)', meaning: '局部起伏小 → 平整度高 → 偏脆性平断口。' },
        { name: '剪切唇指数 shearLip', formula: '由 solidity（及圆度）映射到 0～1', meaning: '轮廓相对凸包不充实 → 外缘剪切唇迹象增强。' },
        { name: '纹理密度 edgeDensity', formula: '掩膜内 Canny 边缘像素 / 掩膜像素', meaning: '偏高偏粗糙纤维状；偏低偏平。' },
        { name: '曲线延性 ductility', formula: '0.55×延伸代理 + 0.45×(1−跌落比)', meaning: '延伸充分且末端较缓 → 倾向塑性。' }
      ],
      scoring: {
        rule: '宏观五项：杯锥分区/纤维区、剪切唇迹象、断面平整度、曲线延性、断面纹理密度；落在中性带不计分，分高者结论。',
        channels: [
          { name: '杯锥分区/纤维区', ductile: '检出纤维区且中心偏暗 → 塑性 +0.14～0.24', brittle: '未稳定检出 → 脆性 +0.16', neutral: '不明确 → 0' },
          { name: '剪切唇迹象', ductile: 'shearLip ≥ 0.55 → +0.20', brittle: '迹象弱且轮廓齐整 → +0.16', neutral: '居中 → 0' },
          { name: '断面平整度', ductile: 'flatness ≤ 0.42 → +0.16', brittle: 'flatness ≥ 0.72 → +0.18', neutral: '居中 → 0' },
          { name: '曲线延性', ductile: '延性高或跌落缓 → +0.22', brittle: '延性低或跌落陡 → +0.22', neutral: '无曲线/不典型 → 0' },
          { name: '断面纹理密度', ductile: 'edgeDensity ≥ 0.11 → +0.16', brittle: 'edgeDensity ≤ 0.05 → +0.16', neutral: '居中 → 0' }
        ],
        decision: 'Sd > Sb → 塑性；Sb > Sd → 脆性；相等时用跌落比 / 延性 / 纤维区做平局裁决。',
        confidence: 'conf = 0.52 + 0.44 × |Sd−Sb|/(Sd+Sb)，限制在 0.50～0.96；得分接近时上限约 0.66。'
      },
      disclaimer: '光照、对焦、背景杂物会改变阈值与边缘结果；请结合实物照片与力—位移曲线人工复核。'
    };
  }

  function renderAlgorithmGuide(guide, fa, geo, classifyDetail) {
    var g = guide || defaultAlgorithmGuide();
    var scoring = g.scoring || {};
    var channels = scoring.channels || [];
    var features = g.features || [];
    var engine = (fa && fa.engine) || g.engine || 'opencv';
    var elapsed = fa && fa.elapsedMs != null ? Number(fa.elapsedMs) : null;
    /* 默认折叠：公式与打分细则属 L3，不抢结论视线 */
    var html = '<details class="fa-algo">'
      + '<summary class="fa-algo-summary">'
      +   '<span class="fa-algo-summary-title">查看计算细节（公式与打分规则）</span>'
      +   '<span class="tag">' + esc(String(engine).toUpperCase()) + '</span>'
      +   (elapsed != null && !isNaN(elapsed) ? '<span class="sub">耗时 ' + elapsed.toFixed(0) + ' ms</span>' : '')
      +   '<span class="fa-algo-hint">按需展开</span>'
      + '</summary>'
      + '<div class="fa-algo-body">';

    if (features.length) {
      html += '<div class="fa-sec-label">特征量怎么算</div><div class="fa-algo-feat">';
      features.forEach(function (ft) {
        html += '<div class="fa-algo-feat-row">'
          + '<strong>' + esc(ft.name || '') + '</strong>'
          + (ft.formula ? '<code>' + esc(ft.formula) + '</code>' : '')
          + (ft.meaning ? '<p>' + esc(ft.meaning) + '</p>' : '')
          + '</div>';
      });
      html += '</div>';
    }

    if (geo && (geo.roundness != null || geo.solidity != null || geo.aspectRatio != null || geo.area != null)) {
      html += '<div class="fa-sec-label">本次几何量</div><div class="fa-algo-geo">';
      [
        { key: 'area', label: '面积', unit: ' px²', raw: true },
        { key: 'perimeter', label: '周长', unit: ' px', raw: true },
        { key: 'aspectRatio', label: '长宽比', unit: '' },
        { key: 'roundness', label: '圆度', unit: '' },
        { key: 'solidity', label: '实心度', unit: '' }
      ].forEach(function (item) {
        if (geo[item.key] == null) return;
        var val = Number(geo[item.key]);
        var hint = item.raw ? '' : geoMeaning(item.key, val);
        html += '<div class="fa-algo-geo-cell"><span>' + item.label + '</span><b>'
          + (item.raw ? val.toFixed(item.key === 'aspectRatio' ? 3 : 1) : val.toFixed(3))
          + item.unit + '</b>'
          + (hint ? '<em>' + esc(hint) + '</em>' : '')
          + '</div>';
      });
      html += '</div>';
    }

    if (scoring.rule || channels.length) {
      html += '<div class="fa-sec-label">五项打分规则</div>';
      if (scoring.rule) html += '<p class="fa-algo-rule">' + esc(scoring.rule) + '</p>';
      if (classifyDetail && classifyDetail.rule && classifyDetail.rule !== scoring.rule) {
        html += '<p class="fa-algo-rule muted">' + esc(classifyDetail.rule) + '</p>';
      }
      if (channels.length) {
        html += '<div class="fa-algo-score-table">';
        channels.forEach(function (ch) {
          html += '<div class="fa-algo-score-row">'
            + '<strong>' + esc(ch.name || '') + '</strong>'
            + '<div class="fa-algo-score-cols">'
            +   '<span data-tone="ductile">塑性：' + esc(ch.ductile || '—') + '</span>'
            +   '<span data-tone="brittle">脆性：' + esc(ch.brittle || '—') + '</span>'
            +   '<span data-tone="neutral">中性：' + esc(ch.neutral || '—') + '</span>'
            + '</div></div>';
        });
        html += '</div>';
      }
      if (scoring.decision) html += '<p class="fa-algo-decision"><strong>判决：</strong>' + esc(scoring.decision) + '</p>';
      if (scoring.confidence) html += '<p class="fa-algo-decision"><strong>置信度：</strong>' + esc(scoring.confidence) + '</p>';
    }

    if (g.disclaimer) {
      html += '<p class="fa-algo-disclaimer">' + esc(g.disclaimer) + '</p>';
    }
    html += '</div></details>';
    return html;
  }

  function geoMeaning(key, val) {
    var n = Number(val);
    if (key === 'roundness') {
      if (n >= 0.55 && n <= 0.92) return '接近杯锥外形';
      if (n < 0.45) return '外形偏扁或不规则';
      return '偏离典型杯锥区间';
    }
    if (key === 'solidity') {
      if (n < 0.88) return '边缘不规则，常见剪切唇';
      return '轮廓较齐整';
    }
    if (key === 'aspectRatio') {
      if (n > 1.35 || n < 0.75) return '投影偏扁/偏长';
      return '接近圆形投影';
    }
    return '';
  }

  function renderZoneBoxes(zones, stepId) {
    if (!zones || !zones.radial || !zones.fibrous) return '';
    /* 仅在原图上叠加纤维区/放射区标注 */
    if (stepId && stepId !== 'original') return '';
    function boxHtml(z, extraClass) {
      var left = (Number(z.x) * 100).toFixed(2) + '%';
      var top = (Number(z.y) * 100).toFixed(2) + '%';
      var width = (Number(z.w) * 100).toFixed(2) + '%';
      var height = (Number(z.h) * 100).toFixed(2) + '%';
      return '<div class="fa-feature-box' + (extraClass ? ' ' + extraClass : '') + '" style="left:' + left + ';top:' + top + ';width:' + width + ';height:' + height + '">'
        + '<span class="fa-feature-tag">' + esc(z.label || '') + '</span></div>';
    }
    return boxHtml(zones.radial, 'is-radial') + boxHtml(zones.fibrous, 'is-fibrous');
  }

  function renderFracturePanel(s, f) {
    var fa = s.fractureAnalysis || null;
    var steps = ((fa && fa.steps) || []).filter(function (st) { return st.id !== 'zones'; });
    var zones = (fa && fa.zones) || null;
    var stepId = ui.fracStepId || 'original';
    if (stepId === 'zones') stepId = 'original';
    var activeStep = null;
    steps.forEach(function (st) { if (st.id === stepId) activeStep = st; });
    if (!activeStep && steps.length) {
      activeStep = steps[0];
      stepId = activeStep.id;
    }
    ui.fracStepId = stepId;
    var mainImg = (activeStep && activeStep.imageBase64) || s.fractureImg || '';
    var contourImg = '';
    var originalImg = '';
    steps.forEach(function (st) {
      if (st.id === 'contour' && st.imageBase64) contourImg = st.imageBase64;
      if (st.id === 'original' && st.imageBase64) originalImg = st.imageBase64;
    });
    /* 类型对照固定用轮廓提取图，不随过程步骤切换 */
    var resultImg = contourImg || originalImg || s.fractureImg || '';
    var geo = (fa && fa.geometry) || null;
    var insight = (fa && fa.curveInsight) || null;
    var pipeline = (fa && fa.pipeline) || [];
    var classifyDetail = (fa && fa.classifyDetail) || null;
    var stepMeta = fracStepMeta(stepId, pipeline);
    var isDuctile = s.fractureType === 'ductile';
    var isBrittle = s.fractureType === 'brittle';
    var ft2 = isDuctile ? '塑性断裂' : (isBrittle ? '脆性断裂' : '—');
    var confPct = Math.round(Number(s.fractureConf || 0) * 1000) / 10;
    var confBar = Math.max(0, Math.min(100, confPct));
    var hasResult = !!(f === 'done_viz' || s.fractureType);
    var ductMatch = hasResult ? (isDuctile ? confPct : Math.max(1, Math.round(100 - confPct))) : 0;
    var britMatch = hasResult ? (isBrittle ? confPct : Math.max(1, Math.round(100 - confPct))) : 0;

    function favorLabel(favor) {
      if (favor === 'ductile') return '偏塑性';
      if (favor === 'brittle') return '偏脆性';
      return '中性';
    }
    function favorClass(favor) {
      if (favor === 'ductile') return 'ok';
      if (favor === 'brittle') return 'warn';
      return 'muted';
    }

    var html = '<div class="card frac-card"><div class="row" style="justify-content:space-between;align-items:center;margin-bottom:.85rem;flex-wrap:wrap;gap:.5rem">'
      + '<h3 class="section-title" style="margin:0">断口采集与分析</h3>'
      + '<span class="tag">' + esc(s.expName || '材料拉伸') + (s.groupName ? ' · ' + esc(s.groupName) : '') + '</span>'
      + '</div>';

    if (f === 'post_break' || f === 'done_viz') {
      if (s.fractureImg) {
        html += '<div class="fa-status-bar">'
          + '<div style="flex:1;min-width:0;display:flex;align-items:center;gap:.55rem;flex-wrap:wrap;padding-left:.25rem">'
          +   '<strong style="color:var(--info);font-size:.9rem">' + (ui.fracBusy ? '分析中…' : '断口图像已采集') + '</strong>'
          +   '<span style="color:var(--border)">·</span>'
          +   '<span class="sub">可重新拍摄或上传以更新分析结果</span>'
          + '</div>'
          + '<button type="button" class="btn btn-ghost btn-sm" id="btn-frac-rerun"' + (ui.fracBusy ? ' disabled' : '') + '>重新分析</button>'
          + '<button type="button" class="btn btn-ghost btn-sm" id="btn-cap-ok"' + (ui.fracBusy ? ' disabled' : '') + '>重新拍摄</button>'
          + '<label class="btn btn-ghost btn-sm" style="cursor:pointer">上传图片<input type="file" id="frac-file" accept="image/*" hidden ' + (ui.fracBusy ? 'disabled' : '') + ' /></label>'
          + '</div>';
      } else {
        html += '<div class="row" style="margin-bottom:.75rem">'
          + '<button type="button" class="btn btn-primary btn-sm" id="btn-cap-ok"' + (ui.fracBusy ? ' disabled' : '') + '>拍摄断口并分析</button>'
          + '<label class="btn btn-ghost btn-sm" style="cursor:pointer">上传断口图分析<input type="file" id="frac-file" accept="image/*" hidden ' + (ui.fracBusy ? 'disabled' : '') + ' /></label>'
          + (ui.fracBusy ? '<span class="tag warn">分析中…</span>' : '')
          + '</div>';
      }
    }

    if (!s.fractureImg && !ui.fracBusy) {
      html += '<div class="empty-soft">尚未采集断口图像。请先用摄像头拍摄，或上传真实断口照片后再进行分析。</div>'
        + '<p class="sub" style="margin-top:.65rem">未采集前不会生成灰度 / 分割 / 边缘等处理结果</p>';
    } else {
      html += '<div class="fa-main">'
        + '<div class="card fa-panel-left">'
        +   '<div class="fa-panel-head"><div class="title">断口宏观图像</div></div>'
        +   '<div class="fa-img-stage">'
        +     '<div class="fa-img-frame">'
        +       (mainImg ? '<img class="frac-main" id="frac-main-img" src="' + mainImg + '" alt="断口分析" />' : '<div class="empty-soft" style="color:#94a3b8;padding:2rem;text-align:center">暂无图像</div>')
        +       renderZoneBoxes(zones, stepId)
        +     '</div>'
        +   '</div>'
        +   (steps.length
          ? '<div class="frac-steps">' + steps.map(function (st) {
              return '<button type="button" class="frac-step' + (activeStep && activeStep.id === st.id ? ' on' : '') + '" data-frac-step="' + esc(st.id) + '">'
                + '<img src="' + st.imageBase64 + '" alt="" />'
                + '<span>' + esc(st.title) + '</span></button>';
            }).join('') + '</div>'
          : '')
        +   (steps.length
          ? '<div class="fa-step-explain fa-step-explain-compact">'
            +   '<strong>' + esc(stepMeta.title) + '</strong>'
            +   (stepMeta.method ? '<span class="tag">' + esc(stepMeta.method) + '</span>' : '')
            +   '<span class="fa-step-desc">' + esc(stepMeta.desc) + '</span>'
            + '</div>'
          : '')
        + '</div>'
        + '<div class="card fa-panel-right">'
        +   '<div class="fa-panel-head">'
        +     '<div class="title">分析结论</div>'
        +     (hasResult ? '<span class="tag ok">已分析</span>' : '<span class="tag warn">分析中</span>')
        +   '</div>'
        +   (hasResult
          ? '<div class="fa-verdict">' + esc(ft2) + '</div>'
            + '<div class="fa-conf-block">'
            +   '<div class="row" style="justify-content:space-between;margin-bottom:.25rem">'
            +     '<span class="sub" style="font-size:.78rem">置信度</span>'
            +     '<strong style="color:var(--blue);font-family:var(--font-mono);font-size:.86rem">' + confPct.toFixed(1) + '%</strong>'
            +   '</div>'
            +   '<div class="fa-conf-track"><div class="fa-conf-fill" style="width:' + confBar + '%"></div></div>'
            + '</div>'
            + (function () {
                var engine = (fa && fa.engine) || 'opencv';
                var elapsed = fa && fa.elapsedMs != null ? Number(fa.elapsedMs) : null;
                return '<div class="fa-meta-bar">'
                  + '引擎 ' + esc(String(engine).toUpperCase())
                  + (elapsed != null && !isNaN(elapsed) ? ' · ' + elapsed.toFixed(0) + ' ms' : '')
                  + '</div>';
              })()
            + (classifyDetail
              ? (function () {
                  var sd = Number(classifyDetail.scoreDuctile || 0);
                  var sb = Number(classifyDetail.scoreBrittle || 0);
                  var tied = !!classifyDetail.tied || Math.abs(sd - sb) < 1e-6;
                  var close = !!classifyDetail.close || Math.abs(sd - sb) < 0.05;
                  var ductOn = !tied && !close && isDuctile;
                  var britOn = !tied && !close && isBrittle;
                  var note = tied
                    ? '两侧得分相同，证据不足以明显区分，仅作弱倾向参考'
                    : (close ? '两侧得分接近，结论区分度较低' : '');
                  return '<div class="fa-score-row">'
                    +   '<div class="fa-score-chip' + (ductOn ? ' on' : '') + (tied || close ? ' is-close' : '') + '"><span>塑性得分</span><b>' + sd.toFixed(2) + '</b></div>'
                    +   '<div class="fa-score-chip' + (britOn ? ' on' : '') + (tied || close ? ' is-close' : '') + '"><span>脆性得分</span><b>' + sb.toFixed(2) + '</b></div>'
                    + '</div>'
                    + (note ? '<p class="fa-score-note">' + note + '</p>' : '');
                })()
              : '')
            + (insight && insight.text ? '<p class="frac-insight">' + esc(insight.text) + '</p>' : '')
            + ((classifyDetail && classifyDetail.cues && classifyDetail.cues.length) || zones
              ? '<div class="fa-right-more">'
                +   (classifyDetail && classifyDetail.cues && classifyDetail.cues.length
                  ? '<div class="fa-sec-label">判定依据</div>'
                    + '<div class="fa-cue-list fa-cue-list-compact">'
                    + classifyDetail.cues.map(function (c) {
                        var w = Number(c.weight || 0);
                        var addLabel;
                        if (w <= 0) addLabel = '不计分';
                        else if (c.favor === 'ductile') addLabel = '塑性 +' + w.toFixed(2);
                        else if (c.favor === 'brittle') addLabel = '脆性 +' + w.toFixed(2);
                        else addLabel = '+' + w.toFixed(2);
                        return '<div class="fa-cue-row">'
                          + '<div class="fa-cue-main">'
                          +   '<strong>' + esc(c.name) + '</strong>'
                          +   '<span class="tag ' + favorClass(c.favor) + '">' + favorLabel(c.favor) + '</span>'
                          +   '<span class="fa-cue-val">' + addLabel + '</span>'
                          + '</div>'
                          + '<p>' + esc(c.note || '') + '</p>'
                          + '</div>';
                      }).join('')
                    + '</div>'
                  : '')
                +   (zones
                  ? '<p class="fa-fill-note"' + (classifyDetail && classifyDetail.cues && classifyDetail.cues.length ? ' style="margin-top:.4rem"' : '') + '>'
                    + '原图已标注纤维区 / 放射区'
                    + (zones.method === 'darkness+local-variance' ? '（暗度 + 局部纹理）' : '')
                    + '。</p>'
                  : '')
                + '</div>'
              : '')
          : '<p class="sub">等待分析结果…</p>')
        + '</div>'
        + '</div>';

      html += '<div class="card fa-ref-section">'
        + '<div class="row" style="justify-content:space-between;margin-bottom:.65rem">'
        +   '<div style="font-weight:600;font-size:.95rem">断裂类型对照</div>'
        +   '<span class="sub">塑 / 脆两类判定</span>'
        + '</div>'
        + '<div class="fa-ref-grid">'
        +   '<div class="fa-ref-card"' + (isDuctile ? ' data-selected="true"' : '') + '>'
        +     '<div class="fa-ref-thumb">'
        +       (resultImg && isDuctile ? '<img src="' + resultImg + '" alt="塑性断裂" />' : '<div class="fa-ref-placeholder">塑性</div>')
        +       (hasResult ? '<span class="fa-ref-match">匹配 ' + ductMatch + '%</span>' : '')
        +     '</div>'
        +     '<div class="fa-ref-body"><div style="font-size:.86rem;font-weight:600;color:' + (isDuctile ? 'var(--primary-700)' : 'var(--ink)') + '">塑性断裂</div>'
        +     '<div class="sub" style="font-size:.72rem;margin-top:.2rem;line-height:1.45">纤维区+放射区+剪切唇，杯锥状</div></div>'
        +   '</div>'
        +   '<div class="fa-ref-card"' + (isBrittle ? ' data-selected="true"' : '') + '>'
        +     '<div class="fa-ref-thumb">'
        +       (resultImg && isBrittle ? '<img src="' + resultImg + '" alt="脆性断裂" />' : '<div class="fa-ref-placeholder">脆性</div>')
        +       (hasResult ? '<span class="fa-ref-match">匹配 ' + britMatch + '%</span>' : '')
        +     '</div>'
        +     '<div class="fa-ref-body"><div style="font-size:.86rem;font-weight:600;color:' + (isBrittle ? 'var(--primary-700)' : 'var(--ink)') + '">脆性断裂</div>'
        +     '<div class="sub" style="font-size:.72rem;margin-top:.2rem;line-height:1.45">解理或沿晶，平整放射纹</div></div>'
        +   '</div>'
        + '</div></div>';

      if (f === 'done_viz') {
        html += '<div class="fa-finish-bar">'
          + '<button type="button" class="btn btn-primary" id="lab-finish">确认分析结论 → 生成组报告</button>'
          + '</div>';
      }

      if (hasResult) {
        html += renderAlgorithmGuide(
          (fa && fa.algorithmGuide) || null,
          fa,
          geo,
          classifyDetail
        );
      }
    }
    return html + '</div>';
  }


  function stu() {
    return ui.snap && ui.snap.student;
  }

  var modalCloser = null;

  function closeModal(result) {
    document.getElementById('modal-mask').classList.add('hidden');
    var fn = modalCloser;
    modalCloser = null;
    if (fn) fn(result);
  }

  function openModal(title, bodyHtml, actionsHtml, wide) {
    modalCloser = null;
    document.getElementById('modal-title').textContent = title;
    document.getElementById('modal-body').innerHTML = bodyHtml;
    document.getElementById('modal-actions').innerHTML = actionsHtml || '<button type="button" class="btn btn-ghost" data-close>关闭</button>';
    document.getElementById('modal-box').classList.toggle('wide', !!wide);
    document.getElementById('modal-mask').classList.remove('hidden');
    document.getElementById('modal-actions').onclick = function (e) {
      if (e.target && e.target.getAttribute('data-close') != null) closeModal(false);
    };
  }

  function showAlert(message, title) {
    return new Promise(function (resolve) {
      openModal(
        title || '提示',
        '<p class="ui-dialog-msg">' + esc(message) + '</p>',
        '<button type="button" class="btn btn-primary" id="ui-alert-ok">确定</button>',
        false
      );
      modalCloser = function () { resolve(); };
      var ok = document.getElementById('ui-alert-ok');
      if (ok) ok.onclick = function () { closeModal(); };
    });
  }

  function showConfirm(message, title) {
    return new Promise(function (resolve) {
      openModal(
        title || '确认',
        '<p class="ui-dialog-msg">' + esc(message) + '</p>',
        '<button type="button" class="btn btn-ghost" id="ui-confirm-cancel">取消</button>'
          + '<button type="button" class="btn btn-primary" id="ui-confirm-ok">确定</button>',
        false
      );
      modalCloser = function (result) { resolve(!!result); };
      var cancel = document.getElementById('ui-confirm-cancel');
      var ok = document.getElementById('ui-confirm-ok');
      if (cancel) cancel.onclick = function () { closeModal(false); };
      if (ok) ok.onclick = function () { closeModal(true); };
    });
  }

  function stopAcq() {
    if (ui.acqTimer) { clearInterval(ui.acqTimer); ui.acqTimer = null; }
  }

  async function refreshSession() {
    ui.snap = await api.session();
    return ui.snap;
  }

  function setPage(p) {
    ui.page = p;
    localStorage.setItem(PAGE_KEY, p || '');
  }

  /* ========== Pages ========== */
  function applyRosterMeta(result) {
    if (!ui.snap.meta) ui.snap.meta = {};
    if (result.students) ui.snap.meta.students = result.students;
    if (result.groups) ui.snap.meta.groups = result.groups;
    if (result.ungrouped) ui.snap.meta.ungrouped = result.ungrouped;
    else if (result.students) {
      ui.snap.meta.ungrouped = result.students.filter(function (s) { return !s.groupId; });
    }
  }

  function pageTeacherAssign() {
    var meta = ui.snap.meta || {};
    var tasks = ui.snap.tasks || [];
    var students = meta.students || [];
    var groups = meta.groups || [];
    var ungrouped = Array.isArray(meta.ungrouped)
      ? meta.ungrouped
      : students.filter(function (s) { return !s.groupId; });
    var readyGroups = groups.filter(function (g) { return (g.members || []).length > 0; });
    var canDispatch = students.length > 0 && !ungrouped.length && readyGroups.length > 0;
    var draft = ui.assignDraft || {};
    var exps = meta.experiments || [];
    if (!draft.expId && exps[0]) draft.expId = exps[0].id;

    var poolItems = ungrouped.length ? ungrouped.map(function (s) {
      return '<label class="stu-pick">'
        + '<input type="checkbox" class="pool-pick" value="' + esc(s.sid) + '" />'
        + '<span><strong>' + esc(s.name) + '</strong>'
        + '<span class="sub"> · ' + esc(s.sid) + (s.cls ? ' · ' + esc(s.cls) : '') + '</span></span>'
        + '</label>';
    }).join('') : '<div class="empty-soft">' + (students.length ? '已全部分组' : '暂无学生') + '</div>';

    var groupCards = groups.length ? groups.map(function (g) {
      var members = g.members || [];
      var memberHtml = members.length ? members.map(function (m) {
        return '<div class="member-row">'
          + '<span><strong>' + esc(m.name) + '</strong><span class="sub"> · ' + esc(m.sid) + '</span></span>'
          + '<button type="button" class="link-btn btn-ungroup-one" data-sid="' + esc(m.sid) + '">移出</button>'
          + '</div>';
      }).join('') : '<div class="empty-soft sm">暂无成员</div>';
      var ready = members.length > 0;
      return '<div class="group-card' + (ready ? ' is-ready' : '') + '" data-gid="' + esc(g.id) + '">'
        + '<div class="group-card-head">'
        +   '<div><h4>' + esc(g.name) + '</h4><span class="sub">' + members.length + ' 人</span></div>'
        +   '<button type="button" class="link-btn danger btn-del-group" data-gid="' + esc(g.id) + '">删除</button>'
        + '</div>'
        + '<div class="group-members">' + memberHtml + '</div>'
        + '<button type="button" class="btn btn-primary btn-sm btn-join-group" data-gid="' + esc(g.id) + '"'
        +   (ungrouped.length ? '' : ' disabled') + '>加入本组</button>'
        + '</div>';
    }).join('') : '';

    var groupPanelBody = groups.length
      ? '<div class="group-list">' + groupCards + '</div>'
      : '<div class="empty-soft">暂无小组</div>';

    var expOpts = exps.map(function (e) {
      return '<option value="' + e.id + '"' + (draft.expId === e.id ? ' selected' : '') + '>'
        + esc(e.name) + '（' + e.hours + ' 学时）</option>';
    }).join('');

    var taskRows = tasks.length ? tasks.map(function (t) {
      var gnames = (t.groupIds || []).map(function (id) {
        var g = groups.find(function (x) { return x.id === id; });
        return g ? g.name : id;
      }).join('、');
      return '<tr><td>' + esc(t.expName) + '</td><td>' + esc(gnames) + '</td><td>' + esc(t.timeText || '—') + '</td><td>' + esc(t.place || '—') + '</td><td class="sub">' + esc((t.createdAt || '').slice(0, 16).replace('T', ' ')) + '</td></tr>';
    }).join('') : '<tr><td colspan="5" class="empty">暂无任务</td></tr>';

    return ''
      + '<div class="page-head"><div><div class="page-kicker">教师</div><h2>下发实验任务</h2></div></div>'
      + '<div class="card assign-shell">'

      +   '<div class="assign-toolbar">'
      +     '<h3 class="section-title" style="margin:0">实验安排</h3>'
      +     '<div class="row roster-actions">'
      +       '<button type="button" class="btn btn-ghost btn-sm" id="btn-dl-template">下载名单模板</button>'
      +       '<label class="btn btn-primary btn-sm" style="cursor:pointer">上传名单<input type="file" id="roster-file" accept=".xlsx,.xls,.csv" hidden /></label>'
      +     '</div>'
      +   '</div>'

      +   '<div class="assign-fields grid-2">'
      +     '<div><label class="field-label">实验项目</label><select id="as-exp" class="inp">' + expOpts + '</select></div>'
      +     '<div><label class="field-label">实验时间</label><input id="as-time" class="inp" value="' + esc(draft.timeText || '') + '" placeholder="时间" /></div>'
      +     '<div><label class="field-label">地点</label><input id="as-place" class="inp" value="' + esc(draft.place || '') + '" placeholder="地点" /></div>'
      +     '<div><label class="field-label">备注</label><input id="as-note" class="inp" value="' + esc(draft.note || '') + '" placeholder="备注" /></div>'
      +     '<div style="grid-column:1/-1"><label class="field-label">实验说明</label>'
      +       '<textarea id="as-tip" class="inp" rows="3" placeholder="实验说明">' + esc(draft.tip || '') + '</textarea></div>'
      +   '</div>'

      +   '<div class="group-board">'
      +     '<div class="group-pool">'
      +       '<div class="panel-head">'
      +         '<strong>未分组</strong>'
      +         '<button type="button" class="btn btn-ghost btn-sm" id="btn-pool-toggle"' + (ungrouped.length ? '' : ' disabled') + '>全选</button>'
      +       '</div>'
      +       '<div id="pool-list" class="pool-scroll">' + poolItems + '</div>'
      +     '</div>'
      +     '<div class="group-side">'
      +       '<div class="panel-head">'
      +         '<strong>小组' + (groups.length ? ' · ' + groups.length : '') + '</strong>'
      +         '<button type="button" class="btn btn-ghost btn-sm" id="btn-create-group">+ 新建小组</button>'
      +       '</div>'
      +       groupPanelBody
      +     '</div>'
      +   '</div>'

      +   '<div class="assign-footer">'
      +     '<button type="button" class="btn btn-primary" id="btn-dispatch"'
      +     (canDispatch ? '' : ' disabled')
      +     ' data-gids="' + esc(readyGroups.map(function (g) { return g.id; }).join(',')) + '">'
      +     '下发任务</button>'
      +   '</div>'

      +   '<div class="assign-history">'
      +     '<h3 class="section-title">已下发</h3>'
      +     '<div style="overflow:auto"><table class="table"><thead><tr><th>实验</th><th>小组</th><th>时间</th><th>地点</th><th>下发于</th></tr></thead><tbody>' + taskRows + '</tbody></table></div>'
      +   '</div>'

      + '</div>';
  }


  function pageTeacherGrade() {
    var rows = ui.snap.grading || [];
    var stats = {
      total: rows.length,
      gDone: rows.filter(function (r) { return r.groupSubmitted; }).length,
      pDone: rows.filter(function (r) { return r.personalSubmitted; }).length
    };
    var body = rows.length ? rows.map(function (r) {
      return '<tr>'
        + '<td>' + esc(r.name) + '<div class="sub">' + esc(r.sid) + '</div></td>'
        + '<td><span class="pill-group">' + esc(r.groupName) + '</span></td>'
        + '<td>' + esc(r.expName) + '</td>'
        + '<td>' + statusTag(r.status) + '</td>'
        + '<td>' + (r.groupSubmitted ? '<span class="tag ok">组已交</span>' : '<span class="tag muted">组未交</span>')
        + ' ' + (r.personalSubmitted ? '<span class="tag ok">个人已交</span>' : '<span class="tag muted">个人未交</span>') + '</td>'
        + '<td>' + esc(String(r.groupScore != null ? r.groupScore : '—')) + ' / ' + esc(String(r.personalScore != null ? r.personalScore : '—')) + '</td>'
        + '<td><button type="button" class="btn btn-ghost btn-sm" data-grade="' + esc(r.sid) + '">评阅打分</button></td>'
        + '</tr>';
    }).join('') : '<tr><td colspan="7" class="empty">还没有已下发任务的学生</td></tr>';

    return ''
      + '<div class="page-head"><div><div class="page-kicker">教师</div><h2>报告评阅打分</h2></div></div>'
      + '<div class="stat-row">'
      +   '<div class="stat"><div class="n">' + stats.total + '</div><div class="l">已有任务人数</div></div>'
      +   '<div class="stat"><div class="n">' + stats.gDone + '</div><div class="l">组报告已提交</div></div>'
      +   '<div class="stat"><div class="n">' + stats.pDone + '</div><div class="l">个人报告已提交</div></div>'
      + '</div>'
      + '<div class="card"><div style="overflow:auto"><table class="table"><thead><tr>'
      + '<th>学生</th><th>小组</th><th>实验</th><th>状态</th><th>提交</th><th>组分/个人分</th><th>操作</th>'
      + '</tr></thead><tbody>' + body + '</tbody></table></div></div>';
  }



  function pageStudentHome() {
    var s = stu();
    if (!s) return '<p>未找到学生</p>';
    if (s.status === 'none') {
      return '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 1</div><h2>我的实验任务</h2></div></div><div class="card empty">当前没有实验任务</div>';
    }
    var mates = (ui.snap.mates || []).map(function (m) { return m.name; }).join('、');
    var cta = s.status === 'assigned'
      ? '<button type="button" class="btn btn-primary" id="btn-start-lab">开始现场实验</button>'
      : (s.status === 'in_lab'
          ? '<button type="button" class="btn btn-primary" id="btn-go-lab">继续实验</button>'
          : '<button type="button" class="btn btn-primary" id="btn-go-report">查看报告</button>');
    return ''
      + '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 1</div><h2>我的实验任务</h2></div></div>'
      + '<div class="task-hero">'
      +   '<div class="row" style="justify-content:space-between;align-items:flex-start">'
      +     '<div class="exp-name">' + esc(s.expName) + '实验</div>' + statusTag(s.status)
      +   '</div>'
      +   '<div class="meta-grid">'
      +     '<div><span class="field-label">小组</span><div><strong>' + esc(s.groupName) + '</strong> · ' + esc(mates) + '</div></div>'
      +     '<div><span class="field-label">时间 / 地点</span><div>' + esc(s.timeText || '—') + ' · ' + esc(s.place || '—') + '</div></div>'
      +     '<div style="grid-column:1/-1"><span class="field-label">备注</span><div>' + esc(s.note || '无') + '</div></div>'
      +     '<div style="grid-column:1/-1"><span class="field-label">实验说明</span><div>' + esc(s.tip || '无') + '</div></div>'
      +   '</div>'
      +   '<div class="row">' + cta + '</div>'
      + '</div>';
  }


  function pageStudentLab() {
    var s = stu();
    if (!s) return '<p>未找到学生</p>';
    var head = '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 2</div><h2>现场做实验</h2>'
      + (s.expName ? '<div class="page-meta">' + esc(s.expName) + '</div>' : '') + '</div></div>';

    if (s.status === 'none' || s.status === 'assigned') {
      return head + '<div class="card empty">尚无进行中的实验</div>';
    }

    if (s.status === 'lab_done' || s.status === 'submitted') {
      var d = (ui.snap.groupReport && ui.snap.groupReport.data) || {
        fMax: s.maxF, dMax: s.maxD, points: (s.acq && s.acq.points) || [],
        fractureType: s.fractureType, fractureImg: s.fractureImg
      };
      var work = calcWork(d.points || []);
      var A0 = 78.54, L0 = 50;
      var sigb = (d.fMax || 0) * 1000 / A0;
      var eps = ((d.dMax || 0) / L0) * 100;
      var ft = d.fractureType === 'ductile' ? '塑性断裂' : (d.fractureType === 'brittle' ? '脆性断裂' : '—');
      return ''
        + '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 2</div><h2>现场做实验</h2><div class="page-meta">已结束 · ' + esc(s.expName || '') + '</div></div>'
        + '<button type="button" class="btn btn-primary" id="btn-go-report">撰写/查看报告</button></div>'
        + '<div class="panel-stack">'
        +   '<div class="card"><h3 class="section-title">力—位移曲线</h3><div class="chart-wrap"><canvas id="chart-fd" width="640" height="260"></canvas></div></div>'
        +   '<div class="card"><h3 class="section-title">关键结果</h3><div class="kpi-grid">'
        +     '<div>F<sub>max</sub>：<strong>' + Number(d.fMax || 0).toFixed(2) + '</strong> kN</div>'
        +     '<div>ΔL<sub>max</sub>：<strong>' + Number(d.dMax || 0).toFixed(2) + '</strong> mm</div>'
        +     '<div>σ<sub>b</sub>：<strong>' + sigb.toFixed(1) + '</strong> MPa</div>'
        +     '<div>ε<sub>max</sub>：<strong>' + eps.toFixed(2) + '</strong> %</div>'
        +     '<div>W：<strong>' + work.toFixed(1) + '</strong> J</div>'
        +     '<div>断口：<span class="tag ' + (d.fractureType === 'ductile' ? 'ok' : 'warn') + '">' + esc(ft) + '</span></div>'
        +   '</div>'
        +   (d.fractureImg ? '<img class="frac-preview" style="margin-top:.7rem" src="' + d.fractureImg + '" alt="断口" />' : '')
        + '</div></div>';
    }

    var f = s.expFlow || 'wait_door';
    var order = ['wait_door', 'acquiring', 'post_break', 'done_viz'];
    var ci = order.indexOf(f);
    if (ci < 0) ci = 0;
    var stepCi = Math.min(ci, 2);

    /* 步骤换页：采集完成后在「实时采集 / 断口确认」间切换，互不叠在同一屏 */
    var labView;
    if (ci === 0) labView = 'door';
    else if (ci === 1) labView = 'acq';
    else {
      if (ui.labView === 'acq' || ui.labView === 'frac') labView = ui.labView;
      else labView = 'frac';
      ui.labView = labView;
    }
    var viewStep = labView === 'door' ? 0 : (labView === 'acq' ? 1 : 2);

    function stp(i, title, viewKey) {
      var reachable = i <= stepCi;
      var viewing = viewStep === i;
      var st = viewing ? 'on' : (stepCi > i ? 'done' : '');
      if (!reachable) {
        return '<div class="ls-step" aria-disabled="true"><div class="ls-num">' + (i + 1) + '</div>' + esc(title) + '</div>';
      }
      return '<button type="button" class="ls-step ' + st + '" data-lab-view="' + viewKey + '">'
        + '<div class="ls-num">' + (stepCi > i && !viewing ? '✓' : (i + 1)) + '</div>' + esc(title) + '</button>';
    }
    function ln(i) {
      var st = stepCi > i ? 'done' : (stepCi === i ? 'on' : '');
      return '<div class="ls-line ' + st + '"></div>';
    }

    var html = head
      + '<div class="lab-stepper">'
      +   stp(0, '联锁启动', 'door') + ln(0)
      +   stp(1, '实时采集', 'acq') + ln(1)
      +   stp(2, '断口确认', 'frac')
      + '</div>'
      + '<div class="panel-stack">';

    if (labView === 'door') {
      if (ci > 0) {
        html += '<div class="done-bar">✓ 安全联锁与启动已完成</div>'
          + '<p class="sub">此步骤已完成。请点击上方「实时采集」或「断口确认」继续。</p>';
      } else {
        html += '<div class="card"><h3 class="section-title">安全联锁与启动</h3>'
          + '<label class="row" style="cursor:pointer"><input type="checkbox" id="lab-door"' + (s.doorClosed ? ' checked' : '') + ' /> <span>安全门已关闭（门磁联锁）</span></label>'
          + '<div class="row" style="margin-top:.75rem">'
          +   '<button type="button" class="btn btn-primary" id="btn-start-machine">启动拉力机</button>'
          +   '<span class="tag warn">等待联锁</span></div></div>';
      }
    } else if (labView === 'acq') {
      if (ci > 0) html += '<div class="done-bar">✓ 安全联锁与启动已完成</div>';
      html += renderRealtimeMonitor(s, ci === 1 ? 'live' : 'done');
    } else if (labView === 'frac' && ci >= 2) {
      if (ci > 0) html += '<div class="done-bar">✓ 安全联锁与启动已完成</div>';
      html += renderFracturePanel(s, f);
    }

    return html + '</div>';
  }




  function pageStudentReport() {
    var s = stu();
    if (!s) return '<p>未找到学生</p>';
    var head = '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 3</div><h2>实验报告</h2>'
      + '<div class="page-meta">' + esc(s.name) + ' · ' + esc(s.groupName || '') + (s.expName ? ' · ' + esc(s.expName) : '') + '</div></div></div>';
    if (s.status === 'none' || s.status === 'assigned') return head + '<div class="card empty">尚无报告数据</div>';
    if (s.status === 'in_lab') return head + '<div class="card empty">实验进行中</div>';

    var gr = ui.snap.groupReport || { confirmedBy: [], html: '', submitted: false };
    var confirmedCount = (gr.confirmedBy || []).length;

    if (!ui.reportTab) ui.reportTab = gr.submitted ? 'personal' : 'group';
    var tab = ui.reportTab === 'personal' ? 'personal' : 'group';
    var p = s.personal || {};
    var confirmBtnLabel = s.groupConfirmed
      ? '组内共同商议确认'
      : (confirmedCount > 0 ? '确认组报告无误（已有组员确认）' : '确认组报告无误');

    var groupBlock = '<div id="panel-group" class="card' + (tab === 'group' ? '' : ' hidden') + '">'
      + '<div class="row" style="justify-content:space-between;margin-bottom:.55rem">'
      +   '<h3 class="section-title" style="margin:0">组报告 <span class="tag">同组共享</span></h3>'
      +   (gr.submitted ? '<span class="tag ok">已提交</span>' : '<span class="tag warn">待确认提交</span>') + '</div>'
      + '<div class="report-body">' + (gr.html || '<p class="sub">暂无组数据</p>') + '</div>'
      + (!gr.submitted
          ? '<div class="row" style="margin-top:.65rem"><button type="button" class="btn btn-primary" id="btn-confirm-group"' + (s.groupConfirmed ? ' disabled' : '') + '>'
            + confirmBtnLabel + '</button>'
            + '<button type="button" class="btn btn-ghost" id="btn-submit-group"' + (confirmedCount < 1 ? ' disabled' : '') + '>提交组报告</button></div>'
          : '<p class="sub">提交于 ' + esc(gr.submittedAt || '') + (gr.score != null ? ' · 教师评分 <strong>' + gr.score + '</strong>' : '') + '</p>')
      + '</div>';

    var personalBlock = '<div id="panel-personal" class="' + (tab === 'personal' ? '' : 'hidden') + '">'
      + '<div class="personal-split">'
      +   '<aside class="personal-group-ref">'
      +     '<div class="row" style="justify-content:space-between;margin-bottom:.45rem">'
      +       '<h3 class="section-title" style="margin:0">组报告（供参考）</h3>'
      +       (gr.submitted ? '<span class="tag ok">已提交</span>' : '<span class="tag muted">草稿</span>')
      +     '</div>'
      +     '<div class="report-body report-body-ref">' + (gr.html || '<p class="sub">暂无组数据，完成实验并生成组报告后可在此查看</p>') + '</div>'
      +   '</aside>'
      +   '<div class="card personal-write">'
      +     '<div class="row personal-write-head" style="justify-content:space-between;margin-bottom:.55rem">'
      +       '<h3 class="section-title" style="margin:0">个人报告</h3>'
      +       (s.personalSubmitted ? '<span class="tag ok">已提交</span>' : '<span class="tag warn">未提交</span>') + '</div>'
      +     '<div class="personal-write-body">'
      +       (!s.personalSubmitted ? '<div id="personal-rte-ribbon" class="personal-rte-ribbon"></div>' : '')
      +       '<label class="field-label">一、实验步骤描述</label>'
      +       '<div class="rte-host" id="rte-steps"></div>'
      +       '<label class="field-label">二、数据分析与误差</label>'
      +       '<div class="rte-host" id="rte-analysis"></div>'
      +       '<label class="field-label">三、总结与反思</label>'
      +       '<div class="rte-host" id="rte-reflection"></div>'
      +       '<div class="file-drop"><div>附件上传（可选，Word/PDF/图片）</div>'
      +         (p.fileName ? '<div style="margin-top:.35rem">已选：<strong>' + esc(p.fileName) + '</strong></div>' : '<div class="sub" style="margin-top:.25rem">未选择文件</div>')
      +         (!s.personalSubmitted ? '<input type="file" id="pr-file" style="margin-top:.45rem" accept=".doc,.docx,.pdf,image/*" />' : '')
      +       '</div>'
      +     '</div>'
      +     '<div class="row personal-write-actions">'
      +       (!s.personalSubmitted
          ? '<button type="button" class="btn btn-ghost" id="btn-save-personal">保存草稿</button><button type="button" class="btn btn-primary" id="btn-submit-personal">提交个人报告</button>'
          : '<span class="sub">提交于 ' + esc(s.personalSubmittedAt || '') + (s.personalScore != null ? ' · 得分 <strong>' + s.personalScore + '</strong>' : ' · 待教师评分')
            + (s.personalComment ? ' · 评语：' + esc(s.personalComment) : '') + '</span>')
      +     '</div>'
      +   '</div>'
      + '</div></div>';

    return ''
      + '<div class="page-head"><div><div class="page-kicker">学生 · 步骤 3</div><h2>实验报告</h2>'
      + '<div class="page-meta">' + esc(s.name) + ' · ' + esc(s.groupName || '') + (s.expName ? ' · ' + esc(s.expName) : '') + '</div></div>'
      + '<div class="seg" id="report-tabs">'
      +   '<button type="button" data-rtab="group"' + (tab === 'group' ? ' class="on"' : '') + '>组报告</button>'
      +   '<button type="button" data-rtab="personal"' + (tab === 'personal' ? ' class="on"' : '') + '>个人报告</button>'
      + '</div></div>'
      + groupBlock + personalBlock;
  }


  async function showPage(p) {
    stopAcq();
    setPage(p);
    var root = document.getElementById('pages');
    var map = {
      't-assign': pageTeacherAssign,
      't-grade': pageTeacherGrade,
      's-home': pageStudentHome,
      's-lab': pageStudentLab,
      's-report': pageStudentReport
    };
    root.innerHTML = (map[p] || function () { return '<p>未知页面</p>'; })();

    document.querySelectorAll('.nav-item').forEach(function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-p') === p);
    });
    var order = ['s-home', 's-lab', 's-report'];
    var idx = order.indexOf(p);
    document.querySelectorAll('#nav-student .nav-item').forEach(function (btn) {
      var bi = order.indexOf(btn.getAttribute('data-p'));
      btn.classList.toggle('done', idx > bi && bi >= 0);
    });
    document.querySelectorAll('#nav-student .fn-line').forEach(function (line) {
      line.classList.toggle('on', idx >= Number(line.getAttribute('data-line')));
    });

    await bindPage(p);
    setTimeout(function () {
      var s = stu();
      var canvas = document.getElementById('chart-fd');
      if (canvas && s) {
        var pts = (s.acq && s.acq.points)
          || ui.localPoints
          || (ui.snap.groupReport && ui.snap.groupReport.data && ui.snap.groupReport.data.points)
          || [];
        var rmHost = document.querySelector('[data-rm-mode]');
        var frozen = rmHost && rmHost.getAttribute('data-rm-mode') === 'done';
        drawChart(canvas, pts, { markRupture: !!frozen });
        if (!frozen) refreshLiveReadout();
      }
    }, 0);
  }

  function refreshLiveReadout() {
    var rmHost = document.querySelector('[data-rm-mode]');
    if (rmHost && rmHost.getAttribute('data-rm-mode') === 'done') return;

    var pts = ui.localPoints || [];
    var last = pts.length ? pts[pts.length - 1] : { f: 0, d: 0 };
    var elF = document.getElementById('rd-f');
    var elD = document.getElementById('rd-d');
    if (elF) elF.textContent = Number(last.f).toFixed(2);
    if (elD) elD.textContent = Number(last.d).toFixed(2);

    var stress = (last.f * 1000) / SPEC_A0;
    var strain = (last.d / SPEC_L0) * 100;
    var elS = document.getElementById('rd-stress');
    var elE = document.getElementById('rd-strain');
    if (elS) elS.textContent = stress.toFixed(1);
    if (elE) elE.textContent = strain.toFixed(2);

    var elT = document.getElementById('rd-time');
    if (elT) {
      var elapsed = ui.acqStartedAt ? (Date.now() - ui.acqStartedAt) : (pts.length * 120);
      elT.textContent = formatMmSs(elapsed);
    }
    var elR = document.getElementById('rd-rate');
    if (elR) elR.textContent = LOAD_RATE.toFixed(1);

    var mf = document.getElementById('lm-maxf');
    var md = document.getElementById('lm-maxd');
    var mw = document.getElementById('lm-work');
    var sb = document.getElementById('lm-sigb');
    if (mf) mf.textContent = Number(ui.localMaxF || 0).toFixed(2) + ' kN';
    if (md) md.textContent = Number(ui.localMaxD || 0).toFixed(2) + ' mm';
    if (mw) mw.textContent = calcWork(pts).toFixed(1) + ' J';
    if (sb) sb.textContent = ((Number(ui.localMaxF || 0) * 1000) / SPEC_A0).toFixed(0) + ' MPa';

    var stage = detectTensileStage(pts, !!ui.ruptureHinted);
    var names = ['弹性段', '屈服', '强化', '颈缩', '断裂'];
    var sum = document.getElementById('rm-stage-summary');
    if (sum) sum.textContent = '5 阶段 · 当前：' + (names[Math.max(0, stage)] || '等待');

    var timeline = document.getElementById('rm-timeline');
    if (timeline && timeline.parentElement) {
      var wrap = timeline.parentElement;
      var next = renderStageTimeline(stage);
      var tmp = document.createElement('div');
      tmp.innerHTML = next;
      var neu = tmp.firstChild;
      if (neu) wrap.replaceChild(neu, timeline);
    }

    /* 强化阶段时更新强化 meta 为当前峰值应力 */
    if (stage >= 2) {
      var meta2 = document.querySelector('[data-stage-meta="2"]');
      if (meta2) meta2.textContent = 'σ↑' + ((Number(ui.localMaxF || 0) * 1000) / SPEC_A0).toFixed(0) + 'MPa';
    }

    drawChart(document.getElementById('chart-fd'), pts, { markRupture: !!ui.ruptureHinted });
  }

  function markCurveFractured(message) {
    if (ui.ruptureHinted) return;
    ui.ruptureHinted = true;
    stopAcq();
    var title = document.getElementById('rm-status-title');
    var desc = document.getElementById('rm-status-desc');
    var bar = document.getElementById('rm-status-bar');
    if (bar) { bar.classList.remove('is-live'); bar.classList.add('is-break'); }
    if (title) { title.style.color = 'var(--danger)'; title.textContent = message || '已到达断裂点'; }
    if (desc) desc.textContent = '曲线已停止绘制。请点击「标记样件已断裂」进入断口采集';
    refreshLiveReadout();
    api.labAcq({
      points: ui.localPoints || [],
      maxF: ui.localMaxF,
      maxD: ui.localMaxD,
      paused: true
    }).then(function (r) {
      ui.snap.student = r.student;
    }).catch(function () {});
  }

  function startAcqLoop() {
    stopAcq();
    var s = stu();
    if (!s || s.expFlow !== 'acquiring' || s.acqPaused) return;
    ui.localPoints = (s.acq && s.acq.points && s.acq.points.slice()) || [];
    ui.localMaxF = s.maxF || 0;
    ui.localMaxD = s.maxD || 0;
    ui.curvePlayIdx = ui.localPoints.length;
    if (!ui.acqStartedAt) {
      ui.acqStartedAt = Date.now() - (ui.localPoints.length * 60);
    }
    ui.syncCounter = 0;

    ensureTensileCurve()
      .then(function (series) {
        var pts = (series && series.points) || [];
        if (!pts.length) throw new Error('实测曲线无数据点');
        var stopAt = (series.fractureIdx != null && series.fractureIdx >= 0)
          ? Math.min(series.fractureIdx, pts.length - 1)
          : (pts.length - 1);
        if (ui.localPoints.length && ui.localPoints.length > stopAt + 1) {
          ui.localPoints = [];
          ui.localMaxF = 0;
          ui.localMaxD = 0;
          ui.curvePlayIdx = 0;
        }
        ui.acqTimer = setInterval(function () {
          var st = stu();
          if (!st || st.expFlow !== 'acquiring' || st.acqPaused) return;
          if (ui.curvePlayIdx > stopAt) {
            markCurveFractured('已到达断裂点（实测回放）');
            return;
          }
          var p = pts[ui.curvePlayIdx++];
          ui.localPoints.push({ d: Number(p.d), f: Number(p.f), t: Date.now() });
          ui.localMaxF = Math.max(ui.localMaxF, Number(p.f));
          ui.localMaxD = Math.max(ui.localMaxD, Number(p.d));
          refreshLiveReadout();
          ui.syncCounter++;
          if (ui.syncCounter % 8 === 0) {
            api.labAcq({
              points: ui.localPoints,
              maxF: ui.localMaxF,
              maxD: ui.localMaxD,
              paused: false
            }).then(function (r) {
              ui.snap.student = r.student;
            }).catch(function () {});
          }
          if (ui.curvePlayIdx > stopAt) {
            markCurveFractured('已到达断裂点（实测回放）');
          }
        }, 60);
      })
      .catch(function (err) {
        console.error(err);
        showAlert((err && err.message) || '无法加载学校拉力机实测曲线，请刷新后重试');
      });
  }

  async function bindPage(p) {
    if (p === 't-assign') {
      function saveAssignDraft() {
        ui.assignDraft = {
          expId: (document.getElementById('as-exp') || {}).value || '',
          timeText: ((document.getElementById('as-time') || {}).value || '').trim(),
          place: ((document.getElementById('as-place') || {}).value || '').trim(),
          note: ((document.getElementById('as-note') || {}).value || '').trim(),
          tip: ((document.getElementById('as-tip') || {}).value || '').trim()
        };
      }

      ['as-exp', 'as-time', 'as-place', 'as-note', 'as-tip'].forEach(function (id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('change', saveAssignDraft);
        el.addEventListener('input', saveAssignDraft);
      });

      var dl = document.getElementById('btn-dl-template');
      if (dl) dl.onclick = function () {
        api.downloadRosterTemplate().catch(function (e) { showAlert(e.message); });
      };

      var file = document.getElementById('roster-file');
      if (file) file.onchange = async function () {
        var f = file.files && file.files[0];
        if (!f) return;
        saveAssignDraft();
        try {
          var r = await api.uploadRoster(f);
          applyRosterMeta(r);
          await showAlert('已导入：新增 ' + r.created + ' 人，更新 ' + r.updated + ' 人');
          showPage('t-assign');
        } catch (e) { await showAlert(e.message); }
        file.value = '';
      };

      function openCreateGroupModal() {
        saveAssignDraft();
        var selected = Array.prototype.slice.call(document.querySelectorAll('.pool-pick:checked')).map(function (el) {
          return el.value;
        });
        openModal(
          '新建小组',
          '<label class="field-label" for="new-group-name">小组名称</label>'
            + '<input id="new-group-name" class="inp" placeholder="小组名称" autocomplete="off" />',
          '<button type="button" class="btn btn-ghost" data-close>取消</button>'
            + '<button type="button" class="btn btn-primary" id="btn-create-group-ok">确定</button>',
          false
        );
        var input = document.getElementById('new-group-name');
        if (input) {
          input.focus();
          input.onkeydown = function (e) {
            if (e.key === 'Enter') {
              e.preventDefault();
              var okBtn = document.getElementById('btn-create-group-ok');
              if (okBtn) okBtn.click();
            }
          };
        }
        var ok = document.getElementById('btn-create-group-ok');
        if (ok) ok.onclick = async function () {
          var name = ((document.getElementById('new-group-name') || {}).value || '').trim();
          if (!name) {
            var el = document.getElementById('new-group-name');
            if (el) el.focus();
            return;
          }
          try {
            var r = await api.createGroup(name);
            if (selected.length && r.group && r.group.id) {
              r = await api.moveToGroup(r.group.id, selected);
            }
            applyRosterMeta(r);
            closeModal();
            showPage('t-assign');
          } catch (e) { await showAlert(e.message); }
        };
      }

      var createG = document.getElementById('btn-create-group');
      if (createG) createG.onclick = openCreateGroupModal;

      var poolToggle = document.getElementById('btn-pool-toggle');
      if (poolToggle) poolToggle.onclick = function () {
        var boxes = Array.prototype.slice.call(document.querySelectorAll('.pool-pick'));
        if (!boxes.length) return;
        var allOn = boxes.every(function (b) { return b.checked; });
        boxes.forEach(function (b) { b.checked = !allOn; });
        poolToggle.textContent = allOn ? '全选' : '取消全选';
      };

      function selectedPoolSids() {
        return Array.prototype.slice.call(document.querySelectorAll('.pool-pick:checked')).map(function (el) {
          return el.value;
        });
      }

      document.querySelectorAll('.btn-join-group').forEach(function (btn) {
        btn.onclick = async function () {
          var sids = selectedPoolSids();
          if (!sids.length) {
            await showAlert('请先在左侧勾选要编入的学生');
            return;
          }
          saveAssignDraft();
          try {
            var r = await api.moveToGroup(btn.getAttribute('data-gid'), sids);
            applyRosterMeta(r);
            showPage('t-assign');
          } catch (e) { await showAlert(e.message); }
        };
      });

      document.querySelectorAll('.btn-ungroup-one').forEach(function (btn) {
        btn.onclick = async function () {
          saveAssignDraft();
          try {
            var r = await api.ungroup([btn.getAttribute('data-sid')]);
            applyRosterMeta(r);
            showPage('t-assign');
          } catch (e) { await showAlert(e.message); }
        };
      });

      document.querySelectorAll('.btn-del-group').forEach(function (btn) {
        btn.onclick = async function () {
          if (!(await showConfirm('删除该小组？组内学生将变为未分组。'))) return;
          saveAssignDraft();
          try {
            var r = await api.deleteGroup(btn.getAttribute('data-gid'));
            applyRosterMeta(r);
            showPage('t-assign');
          } catch (e) { await showAlert(e.message); }
        };
      });

      var btn = document.getElementById('btn-dispatch');
      if (btn) btn.onclick = async function () {
        saveAssignDraft();
        var ungrouped = (ui.snap.meta && ui.snap.meta.ungrouped) || [];
        if (ungrouped.length) {
          await showAlert('还有 ' + ungrouped.length + ' 人未分配组别，请全部分组后再下发');
          return;
        }
        var gids = (btn.getAttribute('data-gids') || '').split(',').filter(Boolean);
        if (!gids.length) { await showAlert('请先完成分组后再下发'); return; }
        try {
          var r = await api.createTask({
            expId: document.getElementById('as-exp').value,
            timeText: document.getElementById('as-time').value.trim(),
            place: document.getElementById('as-place').value.trim(),
            note: document.getElementById('as-note').value.trim(),
            tip: document.getElementById('as-tip').value.trim(),
            groupIds: gids
          });
          ui.snap.tasks = r.tasks;
          ui.assignDraft = { expId: '', timeText: '', place: '', note: '', tip: '' };
          await refreshSession();
          await showAlert('已向 ' + gids.length + ' 个小组下发任务');
          showPage('t-assign');
        } catch (e) { await showAlert(e.message); }
      };
    }

    if (p === 't-grade') {
      document.querySelectorAll('[data-grade]').forEach(function (btn) {
        btn.onclick = function () { openGradeModal(btn.getAttribute('data-grade')); };
      });
    }

    if (p === 's-home') {
      var b1 = document.getElementById('btn-start-lab');
      if (b1) b1.onclick = async function () {
        try {
          var r = await api.labStart();
          ui.snap.student = r.student;
          showPage('s-lab');
        } catch (e) { await showAlert(e.message); }
      };
      var b2 = document.getElementById('btn-go-lab');
      if (b2) b2.onclick = function () { showPage('s-lab'); };
      var b3 = document.getElementById('btn-go-report');
      if (b3) b3.onclick = function () { showPage('s-report'); };
    }

    if (p === 's-lab') {
      var goR = document.getElementById('btn-go-report');
      if (goR) goR.onclick = function () { showPage('s-report'); };

      function switchLabView(view) {
        var s = stu();
        if (!s) return;
        var order = ['wait_door', 'acquiring', 'post_break', 'done_viz'];
        var ci = order.indexOf(s.expFlow || 'wait_door');
        if (view === 'door' && ci >= 0) { ui.labView = 'door'; showPage('s-lab'); return; }
        if (view === 'acq' && ci >= 1) { ui.labView = 'acq'; showPage('s-lab'); return; }
        if (view === 'frac' && ci >= 2) { ui.labView = 'frac'; showPage('s-lab'); return; }
      }
      document.querySelectorAll('[data-lab-view]').forEach(function (el) {
        el.onclick = function () { switchLabView(el.getAttribute('data-lab-view')); };
      });

      var door = document.getElementById('lab-door');
      if (door) door.onchange = async function () {
        try {
          var r = await api.labDoor(door.checked);
          ui.snap.student = r.student;
        } catch (e) { await showAlert(e.message); }
      };

      var startM = document.getElementById('btn-start-machine');
      if (startM) startM.onclick = async function () {
        try {
          ui.acqStartedAt = Date.now();
          ui.ruptureHinted = false;
          ui.labView = 'acq';
          ui.localPoints = [];
          ui.localMaxF = 0;
          ui.localMaxD = 0;
          ui.curvePlayIdx = 0;
          var r = await api.labStartMachine();
          ui.snap.student = r.student;
          showPage('s-lab');
        } catch (e) { await showAlert(e.message); }
      };

      var stop = document.getElementById('btn-acq-stop');
      if (stop) stop.onclick = async function () {
        stopAcq();
        try {
          await api.labAcq({ points: ui.localPoints || [], maxF: ui.localMaxF, maxD: ui.localMaxD, paused: true });
          var r = await api.labPause(true);
          ui.snap.student = r.student;
          showPage('s-lab');
        } catch (e) { await showAlert(e.message); }
      };
      var resume = document.getElementById('btn-acq-resume');
      if (resume) resume.onclick = async function () {
        try {
          var r = await api.labPause(false);
          ui.snap.student = r.student;
          showPage('s-lab');
        } catch (e) { await showAlert(e.message); }
      };

      var rup = document.getElementById('btn-rupture');
      if (rup) rup.onclick = async function () {
        stopAcq();
        try {
          var r = await api.labRupture({ points: ui.localPoints || [], maxF: ui.localMaxF, maxD: ui.localMaxD });
          ui.snap.student = r.student;
          ui.labView = 'frac';
          showPage('s-lab');
        } catch (e) { await showAlert(e.message); }
      };

      async function runFractureAnalyze(img) {
        if (ui.fracBusy) return;
        if (!img || typeof img !== 'string' || img.indexOf('data:image') !== 0) {
          await showAlert('请先拍摄或上传真实断口图像');
          return;
        }
        ui.fracBusy = true;
        showPage('s-lab');
        try {
          var s = stu();
          var points = (s && s.acq && s.acq.points) || ui.localPoints || [];
          /* 后台生成断口标准答案基准（对学生不可见），失败也不影响学生流程 */
          var analysis = {};
          try {
            var ar = await api.labFractureAnalyze({ imageBase64: img, points: points });
            analysis = ar.analysis || {};
          } catch (_) { analysis = {}; }
          var r = await api.labFracture({
            fractureImg: img,
            fractureType: analysis.fractureType,
            fractureConf: analysis.fractureConf,
            fractureAnalysis: analysis
          });
          ui.snap.student = r.student;
        } catch (e) {
          await showAlert(e.message || '断口图像保存失败');
        } finally {
          ui.fracBusy = false;
          showPage('s-lab');
        }
      }

      function stopFracCamera() {
        if (ui.fracCamStream) {
          try {
            ui.fracCamStream.getTracks().forEach(function (t) { t.stop(); });
          } catch (e) {}
          ui.fracCamStream = null;
        }
      }

      function openFractureCamera() {
        stopFracCamera();
        openModal(
          '拍摄断口',
          '<div class="frac-cam">'
            + '<video id="frac-cam-video" class="frac-cam-video" autoplay playsinline muted></video>'
            + '<canvas id="frac-cam-canvas" class="hidden"></canvas>'
            + '<p class="sub" id="frac-cam-tip" style="margin:.55rem 0 0">请将断口置于画面中央后点击「拍照并分析」</p>'
            + '</div>',
          '<button type="button" class="btn btn-ghost" id="frac-cam-cancel">取消</button>'
            + '<button type="button" class="btn btn-primary" id="frac-cam-shot" disabled>拍照并分析</button>',
          true
        );
        var video = document.getElementById('frac-cam-video');
        var tip = document.getElementById('frac-cam-tip');
        var shot = document.getElementById('frac-cam-shot');
        var cancel = document.getElementById('frac-cam-cancel');
        if (cancel) cancel.onclick = function () {
          stopFracCamera();
          closeModal(false);
        };
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
          if (tip) tip.textContent = '当前浏览器不支持摄像头，请改用「上传断口图分析」';
          return;
        }
        navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false
        }).then(function (stream) {
          ui.fracCamStream = stream;
          if (video) {
            video.srcObject = stream;
            video.play().catch(function () {});
          }
          if (shot) shot.disabled = false;
        }).catch(function (err) {
          console.error(err);
          if (tip) tip.textContent = '无法打开摄像头，请检查权限，或改用「上传断口图分析」';
          showAlert('无法打开摄像头：' + ((err && err.message) || '请改用上传图片'));
        });
        if (shot) shot.onclick = function () {
          if (!video || !video.videoWidth) {
            showAlert('摄像头尚未就绪，请稍候再试');
            return;
          }
          var canvas = document.getElementById('frac-cam-canvas');
          if (!canvas) return;
          canvas.width = video.videoWidth;
          canvas.height = video.videoHeight;
          var ctx = canvas.getContext('2d');
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          var dataUrl = canvas.toDataURL('image/jpeg', 0.92);
          stopFracCamera();
          closeModal(false);
          runFractureAnalyze(dataUrl);
        };
        modalCloser = function () { stopFracCamera(); };
      }

      var rerun = document.getElementById('btn-frac-rerun');
      if (rerun) rerun.onclick = function () {
        var cur = stu();
        if (!cur || !cur.fractureImg) {
          showAlert('当前没有断口图像可重新分析');
          return;
        }
        runFractureAnalyze(cur.fractureImg);
      };
      document.querySelectorAll('[data-frac-step]').forEach(function (btn) {
        btn.onclick = function () {
          ui.fracStepId = btn.getAttribute('data-frac-step');
          showPage('s-lab');
        };
      });

      var cap = document.getElementById('btn-cap-ok');
      if (cap) cap.onclick = function () {
        openFractureCamera();
      };
      var ff = document.getElementById('frac-file');
      if (ff) ff.onchange = function () {
        var file = ff.files && ff.files[0];
        if (!file) return;
        if (!/^image\//.test(file.type || '')) {
          showAlert('请选择图片文件（jpg / png 等）');
          ff.value = '';
          return;
        }
        var reader = new FileReader();
        reader.onload = function () { runFractureAnalyze(reader.result); };
        reader.readAsDataURL(file);
        ff.value = '';
      };

      var fin = document.getElementById('lab-finish');
      if (fin) fin.onclick = async function () {
        try {
          var r = await api.labFinish();
          ui.snap.student = r.student;
          ui.snap.groupReport = r.groupReport;
          ui.reportTab = 'group';
          localStorage.setItem(TAB_KEY, 'group');
          await showAlert('组报告已生成，请组内确认后提交。');
          await refreshSession();
          showPage('s-report');
        } catch (e) { await showAlert(e.message); }
      };

      var s0 = stu();
      if (s0 && s0.status === 'in_lab' && s0.expFlow === 'acquiring' && !s0.acqPaused) startAcqLoop();
    }

    if (p === 's-report') {
      var tabs = document.getElementById('report-tabs');
      if (tabs) tabs.onclick = function (e) {
        var b = e.target.closest('[data-rtab]');
        if (!b) return;
        ui.reportTab = b.getAttribute('data-rtab');
        localStorage.setItem(TAB_KEY, ui.reportTab);
        showPage('s-report');
      };

      var conf = document.getElementById('btn-confirm-group');
      if (conf) conf.onclick = async function () {
        try {
          var r = await api.confirmGroup();
          ui.snap.student = r.student;
          ui.snap.groupReport = r.groupReport;
          showPage('s-report');
        } catch (e) { await showAlert(e.message); }
      };
      var subG = document.getElementById('btn-submit-group');
      if (subG) subG.onclick = async function () {
        try {
          var r = await api.submitGroup();
          ui.snap.groupReport = r.groupReport;
          ui.reportTab = 'personal';
          localStorage.setItem(TAB_KEY, 'personal');
          await showAlert('组报告已提交');
          showPage('s-report');
        } catch (e) { await showAlert(e.message); }
      };

      function readPersonal() {
        var rtes = ui.personalRtes || {};
        return {
          steps: (rtes.steps && rtes.steps.getHtml()) || '',
          analysis: (rtes.analysis && rtes.analysis.getHtml()) || '',
          reflection: (rtes.reflection && rtes.reflection.getHtml()) || ''
        };
      }
      var s0 = stu();
      var p0 = (s0 && s0.personal) || {};
      var readOnlyPersonal = !!(s0 && s0.personalSubmitted);
      ui.personalRtes = {};
      if (window.LabRichText) {
        if (!readOnlyPersonal) {
          window.LabRichText.mountRibbon(document.getElementById('personal-rte-ribbon'));
        }
        [['steps', 'rte-steps'], ['analysis', 'rte-analysis'], ['reflection', 'rte-reflection']].forEach(function (pair) {
          var host = document.getElementById(pair[1]);
          if (!host) return;
          ui.personalRtes[pair[0]] = window.LabRichText.mount(host, {
            value: p0[pair[0]] || '',
            readOnly: readOnlyPersonal,
            sharedRibbon: !readOnlyPersonal
          });
        });
      }
      var saveP = document.getElementById('btn-save-personal');
      if (saveP) saveP.onclick = async function () {
        try {
          var r = await api.savePersonal(readPersonal());
          ui.snap.student = r.student;
          await showAlert('个人报告草稿已保存');
        } catch (e) { await showAlert(e.message); }
      };
      var subP = document.getElementById('btn-submit-personal');
      if (subP) subP.onclick = async function () {
        try {
          var payload = readPersonal();
          var s = stu();
          payload.fileName = (s.personal && s.personal.fileName) || '';
          payload.fileDataUrl = (s.personal && s.personal.fileDataUrl) || '';
          var r = await api.submitPersonal(payload);
          ui.snap.student = r.student;
          await showAlert('个人报告已提交');
          showPage('s-report');
        } catch (e) { await showAlert(e.message); }
      };
      var pf = document.getElementById('pr-file');
      if (pf) pf.onchange = function () {
        var f = pf.files && pf.files[0];
        if (!f) return;
        var reader = new FileReader();
        reader.onload = async function () {
          try {
            var payload = Object.assign(readPersonal(), { fileName: f.name, fileDataUrl: reader.result });
            var r = await api.savePersonal(payload);
            ui.snap.student = r.student;
            showPage('s-report');
          } catch (e) { await showAlert(e.message); }
        };
        reader.readAsDataURL(f);
      };
    }
  }

  function fractureLabel(t) {
    return t === 'ductile' ? '塑性断裂' : (t === 'brittle' ? '脆性断裂' : '—');
  }



  async function openGradeModal(sid) {
    try {
      var detail = await api.gradingDetail(sid);
      var s = detail.student;
      var gr = detail.groupReport;
      var p = s.personal || {};
      var body = ''
        + '<p class="sub" style="margin:0 0 .7rem">' + esc(s.name) + '（' + esc(sid) + '）· ' + esc(s.groupName) + ' · ' + esc(s.expName) + '</p>'
        + '<div class="grid-2">'
        +   '<div><div class="row" style="justify-content:space-between;gap:.4rem;flex-wrap:wrap">'
        +     '<strong>组报告</strong> ' + (gr && gr.submitted ? '<span class="tag ok">已提交</span>' : '<span class="tag muted">未提交</span>')
        +     '<button type="button" class="btn btn-ghost btn-sm" id="btn-ai-group"' + (!(gr && gr.submitted) ? ' disabled' : '') + '>AI 评阅组报告</button>'
        +   '</div>'
        +     '<div class="report-body" style="margin-top:.5rem;max-height:220px;overflow:auto">' + ((gr && gr.html) || '<p class="sub">无</p>') + '</div>'
        +     '<div class="score-box" style="margin-top:.55rem">'
        +       '<label>组报告分数<input id="g-score" class="inp" type="number" min="0" max="100" ' + (!(gr && gr.submitted) ? 'disabled' : '') + ' value="' + (gr && gr.score != null ? gr.score : '') + '" /></label>'
        +       '<label class="score-comment" style="flex:1">组报告评语<textarea id="g-comment" class="inp" rows="3" ' + (!(gr && gr.submitted) ? 'disabled' : '') + '>' + esc((gr && gr.comment) || '') + '</textarea></label>'
        +     '</div>'
        +     '<p class="sub ai-hint" id="ai-hint-group" style="margin:.35rem 0 0"></p>'
        +   '</div>'
        +   '<div><div class="row" style="justify-content:space-between;gap:.4rem;flex-wrap:wrap">'
        +     '<strong>个人报告</strong> ' + (s.personalSubmitted ? '<span class="tag ok">已提交</span>' : '<span class="tag muted">未提交</span>')
        +     '<button type="button" class="btn btn-ghost btn-sm" id="btn-ai-personal"' + (!s.personalSubmitted ? ' disabled' : '') + '>AI 评阅个人报告</button>'
        +   '</div>'
        +     '<div class="report-body" style="margin-top:.5rem;max-height:220px;overflow:auto">'
        +       '<p><strong>步骤</strong></p><div class="rte-view">' + formatPersonalHtml(p.steps) + '</div>'
        +       '<p style="margin-top:.55rem"><strong>分析</strong></p><div class="rte-view">' + formatPersonalHtml(p.analysis) + '</div>'
        +       '<p style="margin-top:.55rem"><strong>反思</strong></p><div class="rte-view">' + formatPersonalHtml(p.reflection) + '</div>'
        +       (p.fileName ? '<p style="margin-top:.55rem">附件：' + esc(p.fileName) + '</p>' : '')
        +     '</div>'
        +     '<div class="score-box" style="margin-top:.55rem">'
        +       '<label>个人分数<input id="p-score" class="inp" type="number" min="0" max="100" ' + (!s.personalSubmitted ? 'disabled' : '') + ' value="' + (s.personalScore != null ? s.personalScore : '') + '" /></label>'
        +       '<label class="score-comment" style="flex:1">个人评语<textarea id="p-comment" class="inp" rows="3" ' + (!s.personalSubmitted ? 'disabled' : '') + '>' + esc(s.personalComment || '') + '</textarea></label>'
        +     '</div>'
        +     '<p class="sub ai-hint" id="ai-hint-personal" style="margin:.35rem 0 0"></p>'
        +   '</div>'
        + '</div>';
      openModal('评阅打分', body,
        '<button type="button" class="btn btn-ghost" data-close>取消</button>'
        + '<button type="button" class="btn btn-primary" id="btn-save-grade">保存评分</button>', true);

      async function runAiReview(reportType) {
        var hint = document.getElementById(reportType === 'group' ? 'ai-hint-group' : 'ai-hint-personal');
        var btn = document.getElementById(reportType === 'group' ? 'btn-ai-group' : 'btn-ai-personal');
        if (btn) btn.disabled = true;
        if (hint) hint.textContent = 'AI 评阅中…';
        try {
          var r = await api.gradingAiReview(sid, reportType);
          if (reportType === 'group') {
            var gs = document.getElementById('g-score');
            var gc = document.getElementById('g-comment');
            if (gs) gs.value = r.score;
            if (gc) gc.value = r.comment || '';
          } else {
            var ps = document.getElementById('p-score');
            var pc = document.getElementById('p-comment');
            if (ps) ps.value = r.score;
            if (pc) pc.value = r.comment || '';
          }
          /* AI 结果立即落库，再次打开仍可见 */
          await api.gradingSave(sid, {
            groupScore: document.getElementById('g-score').value === '' ? null : Number(document.getElementById('g-score').value),
            groupComment: document.getElementById('g-comment').value,
            personalScore: document.getElementById('p-score').value === '' ? null : Number(document.getElementById('p-score').value),
            personalComment: document.getElementById('p-comment').value
          });
          await refreshSession();
          if (hint) {
            hint.textContent = (r.source === 'dify' ? '已自动保存 AI 建议分与评语，再次打开仍会保留；可继续修改后点保存' : '本地演示评阅已自动保存，可继续修改后点保存');
          }
        } catch (e) {
          if (hint) hint.textContent = '';
          await showAlert(e.message || 'AI 评阅失败');
        } finally {
          if (btn) btn.disabled = false;
        }
      }

      var aiG = document.getElementById('btn-ai-group');
      if (aiG) aiG.onclick = function () { runAiReview('group'); };
      var aiP = document.getElementById('btn-ai-personal');
      if (aiP) aiP.onclick = function () { runAiReview('personal'); };

      document.getElementById('btn-save-grade').onclick = async function () {
        try {
          await api.gradingSave(sid, {
            groupScore: document.getElementById('g-score').value === '' ? null : Number(document.getElementById('g-score').value),
            groupComment: document.getElementById('g-comment').value,
            personalScore: document.getElementById('p-score').value === '' ? null : Number(document.getElementById('p-score').value),
            personalComment: document.getElementById('p-comment').value
          });
          closeModal();
          await refreshSession();
          showPage('t-grade');
        } catch (e) { await showAlert(e.message); }
      };
    } catch (e) { await showAlert(e.message); }
  }


  async function enterApp() {
    await refreshSession();
    var isTea = ui.snap.role === 'teacher';
    document.getElementById('screen-login').classList.add('hidden');
    document.getElementById('screen-app').classList.remove('hidden');
    document.getElementById('nav-teacher').classList.toggle('hidden', !isTea);
    document.getElementById('nav-student').classList.toggle('hidden', isTea);
    window.LabAssistant.show(true);
    if (window.LabAssistant.refresh) window.LabAssistant.refresh();

    document.getElementById('top-who').textContent = isTea
      ? ('教师 · ' + ui.snap.teacherName)
      : (ui.snap.student.name + ' · ' + ui.snap.student.sid + ' · ' + ui.snap.student.groupName);

    var page = ui.page || (isTea ? 't-assign' : 's-home');
    if (isTea && page.indexOf('s-') === 0) page = 't-assign';
    if (!isTea && page.indexOf('t-') === 0) page = 's-home';
    await showPage(page);
  }

  async function fillLogin() {
    // 账号密码登录，无需预加载角色列表
  }

  document.getElementById('btn-login').onclick = async function () {
    var account = document.getElementById('login-account').value.trim();
    var password = document.getElementById('login-password').value;
    if (!account || !password) { await showAlert('请输入账号和密码'); return; }
    try {
      var r = await api.login({ account: account, password: password });
      api.setToken(r.token);
      setPage(r.role === 'teacher' ? 't-assign' : 's-home');
      await enterApp();
    } catch (e) { await showAlert(e.message); }
  };

  document.getElementById('login-password').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') document.getElementById('btn-login').click();
  });
  document.getElementById('login-account').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') document.getElementById('login-password').focus();
  });

  document.getElementById('btn-logout').onclick = async function () {
    stopAcq();
    try { await api.logout(); } catch (_) {}
    api.setToken('');
    setPage(null);
    document.getElementById('screen-app').classList.add('hidden');
    document.getElementById('screen-login').classList.remove('hidden');
    window.LabAssistant.show(false);
  };

  document.querySelectorAll('.nav-item').forEach(function (btn) {
    btn.addEventListener('click', function () { showPage(btn.getAttribute('data-p')); });
  });

  document.getElementById('modal-mask').addEventListener('click', function (e) {
    if (e.target.id === 'modal-mask') closeModal();
  });

  window.LabAssistant.setContext(function () {
    var snap = ui.snap || {};
    return {
      role: snap.role || 'student',
      teacherName: snap.teacherName || '',
      student: snap.student || null,
      groupReport: snap.groupReport || null,
      grading: snap.grading || [],
      applyPersonalDraft: function (draft) {
        if (!snap.student || snap.student.personalSubmitted) return;
        var toHtml = function (t) {
          return (window.LabRichText && window.LabRichText.plainToHtml)
            ? window.LabRichText.plainToHtml(t || '')
            : (t || '');
        };
        var payload = {
          steps: toHtml(draft.steps || ''),
          analysis: toHtml(draft.analysis || ''),
          reflection: toHtml(draft.reflection || ''),
          fileName: (snap.student.personal && snap.student.personal.fileName) || '',
          fileDataUrl: (snap.student.personal && snap.student.personal.fileDataUrl) || ''
        };
        api.savePersonal(payload).then(function (r) {
          if (r.student) ui.snap.student = r.student;
          else {
            ui.snap.student.personal = payload;
          }
          ui.reportTab = 'personal';
          localStorage.setItem(TAB_KEY, 'personal');
          showPage('s-report');
        }).catch(function () {
          ui.snap.student.personal = payload;
          ui.reportTab = 'personal';
          localStorage.setItem(TAB_KEY, 'personal');
          showPage('s-report');
        });
      }
    };
  });

  window.LabAssistant.init();

  fillLogin();
  if (api.getToken()) {
    enterApp().catch(function () {
      api.setToken('');
    });
  }
})();
