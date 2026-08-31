'use strict';

/**
 * 物小智：按角色展示不同功能入口（本地规则回复；后续可换 Dify）
 */
window.LabAssistant = (function () {
  var open = false;
  var view = 'home'; // home | chat
  var skillId = null;
  var msgs = [];
  var getContext = function () { return { role: 'student' }; };

  var SKILLS = {
    teacher: [
      {
        id: 't-analyze',
        ico: '📊',
        title: '实验报告分析',
        desc: '根据已提交的组/个人报告，给出结构完整性、数据引用与评阅要点。',
        hello: '已进入「实验报告分析」。可点下方快捷项，或直接描述你想分析的学生/小组。',
        chips: ['分析当前已提交报告概况', '指出常见写作问题', '给出评阅检查清单'],
        reply: function (t, ctx) {
          var rows = (ctx && ctx.grading) || [];
          var submitted = rows.filter(function (r) { return r.groupSubmitted; }).length;
          var personal = rows.filter(function (r) { return r.personalSubmitted; }).length;
          if (/概况|当前|提交/.test(t)) {
            return '【报告分析】\n· 组报告已提交：' + submitted + ' 条\n· 个人报告已提交：' + personal + ' 份\n建议优先抽查：数据与曲线是否对应、断口结论是否与照片一致、个人报告是否出现“复制组报告”痕迹。';
          }
          if (/问题|常见/.test(t)) {
            return '常见问题：①只写步骤无数据分析；②σb 计算未写试样尺寸；③断口类型与曲线韧性特征矛盾；④误差分析空泛。评阅时可对照组现场数据快速核验。';
          }
          return '评阅检查清单：目的是否明确 → 关键数据是否齐全 → 曲线/断口是否引用 → 误差是否具体 → 结论是否呼应数据。';
        }
      },
      {
        id: 't-plag',
        ico: '🔎',
        title: '报告查重',
        desc: '对比同组/同班文本相似度，提示疑似雷同段落。',
        hello: '已进入「报告查重」。可点下方快捷项查看相似度提示。',
        chips: ['查同组个人报告相似度', '说明查重判定规则', '如何处理高度雷同'],
        reply: function (t) {
          if (/同组|相似/.test(t)) {
            return '【查重】抽检同组个人报告：步骤描述相似度约 62%，数据分析约 28%。若步骤大段雷同、分析也高度一致，建议要求重写“数据分析与误差”一节并当面抽问。';
          }
          if (/规则/.test(t)) {
            return '查重规则：同组共享组报告不算抄袭；个人报告的步骤/分析/反思按段落比对。课程模板句可忽略，连续 40 字以上重合将标红。';
          }
          return '处理建议：相似度>50% 先提醒修改；>70% 且核心分析雷同可退回重交。查重结果仅作辅助，最终由教师认定。';
        }
      },
      {
        id: 't-score',
        ico: '✏️',
        title: '评分建议',
        desc: '结合提交状态与报告完整度，给出参考分与评语草稿。',
        hello: '已进入「评分建议」。告诉我学生姓名/学号，或点快捷项生成参考评语。',
        chips: ['生成一条参考评语', '组报告建议给多少分', '个人报告扣分点'],
        reply: function (t) {
          if (/评语/.test(t)) {
            return '参考评语：组报告数据完整、断口结论清楚；个人报告分析尚可，误差来源可再具体。建议组报告 86、个人报告 80，请按实际调整。';
          }
          if (/组报告|多少分/.test(t)) {
            return '组报告若现场数据齐全、已有组员确认且结论自洽，建议 82–90；缺断口图或关键量未写，建议 70–80。';
          }
          return '个人报告常见扣分：无定量比较（-5）、误差空泛（-5）、未结合本组数据（-8）。可在「报告评阅打分」中落分。';
        }
      },
      {
        id: 't-chat',
        ico: '💬',
        title: '自由问答',
        desc: '实验教学、平台使用与布置任务相关问题。',
        hello: '可以问我：如何按组下发、学生流程、智能化能力边界等。',
        chips: ['教师端怎么用', '学生交报告后我做什么', '物小智能做什么'],
        reply: function (t) {
          if (/教师|怎么用/.test(t)) return '教师端：上传名单并分组 → 填写实验安排并下发 → 学生提交后在「报告评阅打分」给分。';
          if (/交报告|做什么/.test(t)) return '学生提交后：打开评阅 → 看组报告与个人报告 → 打分写评语。也可先用「报告分析/查重」辅助。';
          return '物小智在教师端提供报告分析、查重与评分建议；在学生端提供实验指导与报告生成辅助。';
        }
      }
    ],
    student: [
      {
        id: 's-guide',
        ico: '🧭',
        title: '实验指导',
        desc: '安全事项、操作步骤、曲线与断口判读实时答疑。',
        hello: '已进入「实验指导」。现场操作时可随时问我安全、步骤或读数问题。',
        chips: ['拉伸实验要注意什么安全事项？', '屈服强度怎么从曲线上看？', '断口塑性/脆性怎么区分？'],
        reply: function (t) {
          if (/安全|急停|门/.test(t)) return '启动前确认安全门关闭、试样夹持可靠、急停位置明确；过程中手勿靠近加载区；异常立刻急停并报告教师。';
          if (/屈服|强度|曲线/.test(t)) return '低碳钢常见屈服平台或上/下屈服点，报告可取下屈服应力；无明显平台时可用 Rp0.2 思路说明。';
          if (/断口|塑性|脆性/.test(t)) return '塑性断口常见杯锥状、纤维区与剪切唇；脆性断口较平整。AI 识别仅供参考，请结合照片与曲线判断。';
          if (/步骤|怎么做|流程/.test(t)) return '现场流程：联锁确认 → 启动采集 → 标记断裂 → 断口拍摄/识别 → 结束并生成组报告 → 课后写个人报告。';
          return '可以继续问安全、曲线读数、断口判别或平台操作。建议结合你正在做的步骤提问。';
        }
      },
      {
        id: 's-gen',
        ico: '📝',
        title: '生成实验报告',
        desc: '按当前实验内容与现场数据，生成个人报告草稿（可再编辑）。',
        hello: '已进入「生成实验报告」。可一键根据本组数据生成个人报告三节草稿。',
        chips: ['根据实验数据生成报告草稿', '只要数据分析段落', '写入我的个人报告编辑框'],
        reply: function (t, ctx) {
          var s = ctx && ctx.student;
          if (!s || s.status === 'none' || s.status === 'assigned') {
            return '你还没有完成现场实验。请先做完实验并生成组数据，我才能按真实结果起草个人报告。';
          }
          var gr = ctx.groupReport || {};
          var d = gr.data || { fMax: s.maxF, dMax: s.maxD, fractureType: s.fractureType };
          var fmax = Number(d.fMax || s.maxF || 0).toFixed(2);
          var dmax = Number(d.dMax || s.maxD || 0).toFixed(2);
          var A0 = 78.54;
          var sigb = (Number(d.fMax || s.maxF || 0) * 1000 / A0).toFixed(1);
          var ft = d.fractureType === 'ductile' ? '塑性断裂' : (d.fractureType === 'brittle' ? '脆性断裂' : '待判定');
          var steps = '本组完成' + (s.expName || '力学') + '实验：确认安全门联锁后启动试验机，实时采集力—位移曲线，试样断裂后拍摄断口并由 AI 辅助识别，组员现场确认组报告。';
          var analysis = '测得 Fmax≈' + fmax + ' kN，ΔLmax≈' + dmax + ' mm；按 L0=50 mm、A0≈78.54 mm²估算 σb≈' + sigb + ' MPa。断口类型倾向' + ft + '。误差可能来自夹持对中、标距测量与采样波动，需与理论/手册值对照讨论。';
          var reflection = '通过实验理解了弹性、屈服与强化阶段特征，认识到组数据共享与个人独立分析的分工。后续将加强误差定量分析，并更仔细核对断口形貌与曲线一致性。';
          if (/只要|数据分析/.test(t) && !/写入|草稿|生成报告/.test(t)) {
            return analysis + '\n\n（可复制到个人报告第二节；需要全文草稿请点「根据实验数据生成报告草稿」。）';
          }
          if (/写入/.test(t) && typeof ctx.applyPersonalDraft === 'function') {
            ctx.applyPersonalDraft({ steps: steps, analysis: analysis, reflection: reflection });
            return '已将三节草稿写入「实验报告」个人报告编辑框，请打开该页检查并改成你自己的表述后再提交。';
          }
          if (/写入/.test(t)) {
            return '草稿如下，请复制到个人报告编辑框：\n\n一、步骤\n' + steps + '\n\n二、数据分析与误差\n' + analysis + '\n\n三、总结反思\n' + reflection;
          }
          return '【个人报告草稿】\n\n一、步骤\n' + steps + '\n\n二、数据分析与误差\n' + analysis + '\n\n三、总结反思\n' + reflection + '\n\n点「写入我的个人报告编辑框」可直接填入页面（请务必个性化修改）。';
        }
      },
      {
        id: 's-data',
        ico: '📈',
        title: '数据解读',
        desc: '解读 F–ΔL 曲线、强度估算与断口结论是否自洽。',
        hello: '已进入「数据解读」。可结合你本组的 Fmax、曲线与断口结果提问。',
        chips: ['解读我组当前关键结果', 'σb 怎么估算', '曲线和断口是否一致'],
        reply: function (t, ctx) {
          var s = ctx && ctx.student;
          var gr = ctx && ctx.groupReport;
          var d = gr && gr.data;
          if (/关键|当前|我组/.test(t)) {
            if (!d && !(s && (s.maxF || s.maxD))) return '暂无组现场数据。请先完成「现场做实验」并结束实验。';
            var fMax = d ? d.fMax : s.maxF;
            var dMax = d ? d.dMax : s.maxD;
            var ftRaw = d ? d.fractureType : s.fractureType;
            var ft = ftRaw === 'ductile' ? '塑性' : (ftRaw === 'brittle' ? '脆性' : '未知');
            return '本组 Fmax=' + Number(fMax || 0).toFixed(2) + ' kN，ΔLmax=' + Number(dMax || 0).toFixed(2) + ' mm，断口倾向' + ft + '。建议在报告中写清试样尺寸，并计算 σb 与延伸相关量。';
          }
          if (/σb|强度|估算/.test(t)) return '估算：σb≈Fmax(N)/A0(mm²)。圆截面 A0=πd²/4；默认 d=10 mm → A0≈78.54 mm²。注意单位：kN 需×1000 化为 N。';
          return '若曲线有明显屈服平台且断口呈杯锥状，通常与塑性断裂一致；若突然脆性跌落且断口平齐，则更支持脆性结论。不一致时优先复核照片与标记断裂时机。';
        }
      },
      {
        id: 's-chat',
        ico: '💬',
        title: '自由问答',
        desc: '组报告与个人报告区别、平台使用等问题。',
        hello: '可以问组/个人报告区别、课后如何提交、物小智能帮什么等。',
        chips: ['组报告和个人报告有什么区别？', '个人报告可以回家写吗？', '物小智能帮我做什么'],
        reply: function (t) {
          if (/区别|组报告|个人报告/.test(t)) return '组报告由平台根据现场数据自动生成，同组共享并现场确认提交；个人报告课后独立撰写，可附件上传，教师分别打分。';
          if (/回家|课后/.test(t)) return '可以。现场完成组报告确认即可离开；个人报告支持保存草稿，回家填写或上传附件后再提交。';
          return '我能做：实验指导、按数据生成报告草稿、解读曲线与断口。请勿直接粘贴草稿代替独立思考。';
        }
      }
    ]
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function ctx() {
    try { return getContext() || { role: 'student' }; } catch (e) { return { role: 'student' }; }
  }

  function skillList() {
    var role = ctx().role === 'teacher' ? 'teacher' : 'student';
    return SKILLS[role] || [];
  }

  function findSkill(id) {
    var list = skillList();
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function reply(text) {
    var skill = findSkill(skillId);
    if (!skill) return '请先选择一项功能。';
    try { return skill.reply(text, ctx()) || '……'; }
    catch (e) { return '暂时无法回答，请稍后再试。'; }
  }

  function goHome() {
    view = 'home';
    skillId = null;
    msgs = [];
    render();
  }

  function openSkill(id, presetText) {
    var skill = findSkill(id);
    if (!skill) return;
    skillId = id;
    view = 'chat';
    open = true;
    msgs = [{ role: 'bot', text: skill.hello }];
    render();
    if (presetText) send(presetText);
  }

  function send(text) {
    var t = String(text || '').trim();
    if (!t) return;
    if (view !== 'chat' || !skillId) {
      open = true;
      view = 'home';
      render();
      return;
    }
    msgs.push({ role: 'me', text: t });
    msgs.push({ role: 'bot', text: reply(t) });
    render();
    var input = document.getElementById('wxz-input');
    if (input) input.value = '';
  }

  function render() {
    var fab = document.getElementById('wxz-fab');
    var panel = document.getElementById('wxz-panel');
    var home = document.getElementById('wxz-home');
    var chat = document.getElementById('wxz-chat');
    var back = document.getElementById('wxz-back');
    if (!fab || !panel || !home || !chat) return;

    fab.classList.toggle('is-open', open);
    panel.classList.toggle('hidden', !open);
    if (!open) return;

    var isHome = view !== 'chat';
    home.classList.toggle('hidden', !isHome);
    chat.classList.toggle('hidden', isHome);
    if (back) back.classList.toggle('hidden', isHome);

    var c = ctx();
    var roleLabel = c.role === 'teacher' ? '教师端' : '学生端';
    var titleEl = document.getElementById('wxz-title');
    var subEl = document.getElementById('wxz-subtitle');
    var avatar = document.getElementById('wxz-avatar');

    if (isHome) {
      if (titleEl) titleEl.textContent = '物小智';
      if (subEl) subEl.textContent = roleLabel + ' · 选择一项能力开始';
      if (avatar) avatar.src = '/assets/wxz-fab-hover.png';
      var who = c.role === 'teacher'
        ? ('你好，' + (c.teacherName || '老师') + '。')
        : ('你好，' + ((c.student && c.student.name) || '同学') + '。');
      home.innerHTML = ''
        + '<p class="wxz-home-hi">' + esc(who) + '我是物小智。</p>'
        + skillList().map(function (sk) {
            return '<button type="button" class="wxz-skill" data-skill="' + esc(sk.id) + '">'
              + '<div class="wxz-skill-ico">' + sk.ico + '</div>'
              + '<div><strong>' + esc(sk.title) + '</strong><span>' + esc(sk.desc) + '</span></div>'
              + '</button>';
          }).join('');
    } else {
      var skill = findSkill(skillId);
      if (titleEl) titleEl.textContent = skill ? skill.title : '物小智';
      if (subEl) subEl.textContent = '物小智 · ' + roleLabel;
      if (avatar) avatar.src = '/assets/wxz-fab-idle.png';
      var chips = document.getElementById('wxz-chips');
      var box = document.getElementById('wxz-msgs');
      if (chips) {
        chips.innerHTML = ((skill && skill.chips) || []).map(function (chip, i) {
          return '<button type="button" data-chip="' + i + '">' + esc(chip) + '</button>';
        }).join('');
      }
      if (!msgs.length && skill) msgs = [{ role: 'bot', text: skill.hello }];
      if (box) {
        box.innerHTML = msgs.map(function (m) {
          return '<div class="wxz-msg ' + (m.role === 'me' ? 'me' : 'bot') + '">' + esc(m.text) + '</div>';
        }).join('');
        box.scrollTop = box.scrollHeight;
      }
      var ph = document.getElementById('wxz-input');
      if (ph) ph.placeholder = skill && skill.id === 's-gen' ? '描述需求，或点快捷生成草稿…' : '输入你的问题…';
    }
  }

  function init() {
    var fab = document.getElementById('wxz-fab');
    var closeBtn = document.getElementById('wxz-close');
    var backBtn = document.getElementById('wxz-back');
    var home = document.getElementById('wxz-home');
    var sendBtn = document.getElementById('wxz-send');
    var input = document.getElementById('wxz-input');
    var chips = document.getElementById('wxz-chips');

    if (fab) {
      fab.onclick = function () {
        if (open) {
          open = false;
        } else {
          open = true;
          view = 'home';
          skillId = null;
          msgs = [];
        }
        render();
      };
    }
    if (closeBtn) {
      closeBtn.onclick = function () {
        open = false;
        render();
      };
    }
    if (backBtn) backBtn.onclick = goHome;
    if (home) {
      home.addEventListener('click', function (e) {
        var btn = e.target.closest('[data-skill]');
        if (!btn) return;
        openSkill(btn.getAttribute('data-skill'));
      });
    }
    if (sendBtn) {
      sendBtn.onclick = function () {
        send((document.getElementById('wxz-input') || {}).value);
      };
    }
    if (input) {
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') send(input.value);
      });
    }
    if (chips) {
      chips.addEventListener('click', function (e) {
        var btn = e.target.closest('[data-chip]');
        if (!btn) return;
        var skill = findSkill(skillId);
        var i = Number(btn.getAttribute('data-chip'));
        if (skill && skill.chips && skill.chips[i]) send(skill.chips[i]);
      });
    }
    render();
  }

  function show(visible) {
    var fab = document.getElementById('wxz-fab');
    if (!fab) return;
    fab.classList.toggle('hidden', !visible);
    if (!visible) {
      open = false;
      view = 'home';
      skillId = null;
      msgs = [];
      render();
    }
  }

  function setContext(fn) {
    if (typeof fn === 'function') getContext = fn;
  }

  function openSkillFromOutside(id, text) {
    open = true;
    openSkill(id, text);
  }

  return {
    init: init,
    show: show,
    setContext: setContext,
    openSkill: openSkillFromOutside,
    refresh: render
  };
})();
