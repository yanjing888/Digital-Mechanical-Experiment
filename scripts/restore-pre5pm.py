# -*- coding: utf-8 -*-
"""Restore public/js/app.js look+logic to before Aug 24 17:00, keep login page."""
import re
from pathlib import Path

root = Path(r'd:\yanjing\97 projects\北方工大力学实验数字化升级')
p = root / 'public' / 'js' / 'app.js'
text = p.read_text(encoding='utf-8')

# 1) wrap back to IIFE
text = text.replace("import { api } from '../api.js'\n\nexport function bootApp() {",
                    "'use strict';\n\n(function () {\n  var api = window.LabAPI;")
if text.rstrip().endswith('}'):
    text = text.rstrip()
    if not text.endswith('})();'):
        text = text[:-1] + '})();\n'

# remove window.setGradeFilter / switchReportTab globals if present at top of ui
text = re.sub(
    r"\n  window\.switchReportTab = function \(tab\) \{[\s\S]*?\n  \};\n\n  window\.setGradeFilter = function \(filter\) \{[\s\S]*?\n  \};\n\n",
    "\n",
    text,
    count=1,
)

# remove helper functions pageHero through updateAppFoot
text = re.sub(
    r"\n  function pageHero\([\s\S]*?\n  function calcWork\(",
    "\n  function calcWork(",
    text,
    count=1,
)


def replace_function(src, name, new_body):
    """Replace `function name(...) { ... }` at top-level (2-space indent)."""
    token = '  function ' + name + '('
    i = src.find(token)
    if i < 0:
        token = '  async function ' + name + '('
        i = src.find(token)
        if i < 0:
            raise SystemExit('missing function ' + name)
    # find opening brace of function
    brace = src.find('{', i)
    depth = 0
    j = brace
    while j < len(src):
        ch = src[j]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                j += 1
                break
        j += 1
    return src[:i] + new_body.rstrip() + '\n' + src[j:]


restore = root / '_restore'
frac = (restore / 'renderFracturePanel.js').read_text(encoding='utf-8')
modal = (restore / 'openGradeModal_old.js').read_text(encoding='utf-8')

text = replace_function(text, 'renderFracturePanel', frac)
text = replace_function(text, 'openGradeModal', modal)

# pageTeacherAssign: replace from `return ''` dashboard version with classic layout
ASSIGN_RETURN = r'''
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
'''

m = re.search(r"    var asideTaskCards = tasks\.length \? tasks\.map", text)
if m:
    # cut from asideTaskCards through end of pageTeacherAssign
    start = m.start()
    # find `  function pageTeacherGrade`
    end = text.find('  function pageTeacherGrade', start)
    if end < 0:
        raise SystemExit('pageTeacherGrade not found')
    # keep taskRows builder, drop asideTaskCards
    # find line start of asideTaskCards - we already have start
    # Need taskRows still. asideTaskCards starts after taskRows join.
    text = text[:start] + ASSIGN_RETURN.lstrip('\n') + '\n' + text[end:]
else:
    # maybe already restored
    print('asideTaskCards not found, assign page maybe already classic')

GRADE = r'''
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
'''
text = replace_function(text, 'pageTeacherGrade', GRADE)

HOME = r'''
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
'''
text = replace_function(text, 'pageStudentHome', HOME)

# pageStudentLab: replace pageHero heads and studentJourney and dashboard done-state
text = text.replace(
    "    var head = pageHero('学生 · 步骤 2', '现场做实验',\n      (s.expName ? esc(s.expName) + ' · ' : '') + '按步骤完成联锁启动、实时采集与断口观察。');\n\n    if (s.status === 'none' || s.status === 'assigned') {\n      return head + emptyPanel('🔒', '实验尚未开始', '请先在「任务」页点击<strong>开始现场实验</strong>。');\n    }",
    "    var head = '<div class=\"page-head\"><div><div class=\"page-kicker\">学生 · 步骤 2</div><h2>现场做实验</h2>'\n      + (s.expName ? '<div class=\"page-meta\">' + esc(s.expName) + '</div>' : '') + '</div></div>';\n\n    if (s.status === 'none' || s.status === 'assigned') {\n      return head + '<div class=\"card empty\">尚无进行中的实验</div>';\n    }",
)

# done lab view — replace the big dashboard block with classic
done_start = text.find("    if (s.status === 'lab_done' || s.status === 'submitted') {")
done_end = text.find("    var f = s.expFlow || 'wait_door';")
if done_start > 0 and done_end > done_start:
    DONE = r'''    if (s.status === 'lab_done' || s.status === 'submitted') {
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

'''
    text = text[:done_start] + DONE + text[done_end:]

text = text.replace("    var html = head + studentJourney(s.status)\n", "    var html = head\n")
text = text.replace("      +   stp(2, '断口观察', 'frac')", "      +   stp(2, '断口确认', 'frac')")

REPORT = r'''
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
'''
text = replace_function(text, 'pageStudentReport', REPORT)

# drop renderDeductionDetail if still present
if 'function renderDeductionDetail' in text:
    text = replace_function(text, 'renderDeductionDetail', '  function renderDeductionDetail() { return ""; }')
    text = text.replace('  function renderDeductionDetail() { return ""; }\n', '')

if 'function renderGradeFracture' in text:
    text = replace_function(text, 'renderGradeFracture', '  function renderGradeFracture() { return ""; }')
    text = text.replace('  function renderGradeFracture() { return ""; }\n', '')
if 'function fractureLabel' in text:
    # keep fractureLabel if used by algorithm panel? original might not have it at grade modal
    pass

# remove auto-group handler
text = re.sub(
    r"\n      var autoG = document\.getElementById\('btn-auto-group'\);[\s\S]*?      \};\n\n      var poolToggle",
    "\n      var poolToggle",
    text,
    count=1,
)

# remove fracture self handlers
text = re.sub(
    r"\n      document\.querySelectorAll\('input\[name=\"frac-judge\"\]'\)[\s\S]*?      var selfSave = document\.getElementById\('btn-frac-self-save'\);[\s\S]*?\n      var fin = document\.getElementById\('lab-finish'\);",
    "\n      var fin = document.getElementById('lab-finish');",
    text,
    count=1,
)

text = text.replace('    updateAppFoot(isTea);\n', '')
text = text.replace(
    """    var foot = document.getElementById('app-foot');
    if (foot) foot.classList.add('hidden');
    window.LabAssistant.show(false);""",
    "    window.LabAssistant.show(false);",
)

p.write_text(text, encoding='utf-8')
print('wrote app.js', len(text))
for n in ['pageHero', 'dashboard-stats', 'btn-auto-group', 'fractureSelf', 'emptyPanel', 'export function']:
    print(n, text.count(n))
