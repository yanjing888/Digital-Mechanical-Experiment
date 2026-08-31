/* Personal-report rich text: ribbon toolbar matching 开始 / 插入 layout */
(function (global) {
  var ALLOWED = {
    P: 1, BR: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, S: 1, STRIKE: 1,
    UL: 1, OL: 1, LI: 1, DIV: 1, SPAN: 1, FONT: 1,
    H1: 1, H2: 1, H3: 1, HR: 1, SUB: 1, SUP: 1, MARK: 1,
    TABLE: 1, THEAD: 1, TBODY: 1, TR: 1, TH: 1, TD: 1,
    IMG: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1, A: 1
  };
  var STYLE_OK = /^(color|background-color|font-size|font-family|font-weight|font-style|text-decoration|text-align|letter-spacing|line-height|border|border-collapse|width|height|max-width|padding|margin|display|vertical-align)$/i;

  var FONTS = [
    ['微软雅黑', 'Microsoft YaHei, sans-serif'],
    ['宋体', 'SimSun, serif'],
    ['黑体', 'SimHei, sans-serif'],
    ['楷体', 'KaiTi, serif'],
    ['仿宋', 'FangSong, serif'],
    ['Arial', 'Arial, sans-serif'],
    ['Times New Roman', 'Times New Roman, serif']
  ];
  var SIZES = [
    ['12', '12px'], ['14', '14px'], ['16', '16px'], ['18', '18px'],
    ['20', '20px'], ['24', '24px'], ['28', '28px'], ['32', '32px']
  ];
  var SYMBOLS = ['·', '—', '…', '°', '℃', '±', '×', '÷', '≤', '≥', '≠', '≈', '∞', '√', '∑', 'π', 'α', 'β', 'γ', 'Δ', 'σ', 'ε', 'μ', 'Ω', '‰', '①', '②', '③', '※', '★', '→', '←', '↑', '↓'];
  var FORMULAS = [
    ['应力 σ = F / A', 'σ = F / A'],
    ['应变 ε = ΔL / L₀', 'ε = ΔL / L₀'],
    ['弹性模量 E = σ / ε', 'E = σ / ε'],
    ['伸长率 δ', 'δ = (Lᵤ − L₀) / L₀ × 100%'],
    ['断面收缩率 ψ', 'ψ = (A₀ − Aᵤ) / A₀ × 100%']
  ];

  var activeEditor = null;
  var savedRange = null;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function looksLikeHtml(s) {
    return /<[a-z][\s\S]*>/i.test(String(s || ''));
  }

  function plainToHtml(text) {
    var t = String(text || '').trim();
    if (!t) return '';
    if (looksLikeHtml(t)) return sanitize(t);
    return t.split(/\n{2,}/).map(function (block) {
      return '<p>' + esc(block).replace(/\n/g, '<br>') + '</p>';
    }).join('');
  }

  function cleanStyle(style) {
    if (!style) return '';
    return String(style).split(';').map(function (part) {
      var i = part.indexOf(':');
      if (i < 0) return '';
      var k = part.slice(0, i).trim();
      var v = part.slice(i + 1).trim();
      if (!STYLE_OK.test(k)) return '';
      if (/expression|javascript|url\s*\(\s*['"]?\s*data:/i.test(v)) return '';
      return k + ':' + v;
    }).filter(Boolean).join(';');
  }

  function sanitize(html) {
    var wrap = document.createElement('div');
    wrap.innerHTML = String(html || '');
    (function walk(node) {
      Array.from(node.childNodes).forEach(function (child) {
        if (child.nodeType === 1) {
          if (!ALLOWED[child.tagName]) {
            while (child.firstChild) node.insertBefore(child.firstChild, child);
            node.removeChild(child);
            return;
          }
          Array.from(child.attributes).forEach(function (attr) {
            var name = attr.name.toLowerCase();
            var val = attr.value;
            if (name === 'style') {
              var cleaned = cleanStyle(val);
              if (cleaned) child.setAttribute('style', cleaned);
              else child.removeAttribute('style');
              return;
            }
            if (child.tagName === 'IMG' && (name === 'src' || name === 'alt' || name === 'width' || name === 'height')) {
              if (name === 'src' && !(val.indexOf('data:image/') === 0 || val.indexOf('http://') === 0 || val.indexOf('https://') === 0 || val.indexOf('/') === 0)) {
                child.removeAttribute(name);
              }
              return;
            }
            if (child.tagName === 'A' && name === 'href') {
              if (/^(https?:|mailto:|#)/i.test(val)) return;
              child.removeAttribute(name);
              return;
            }
            if (child.tagName === 'FONT' && (name === 'color' || name === 'face' || name === 'size')) return;
            if (name === 'colspan' || name === 'rowspan') return;
            child.removeAttribute(name);
          });
          walk(child);
        } else if (child.nodeType === 8) {
          node.removeChild(child);
        }
      });
    })(wrap);
    return wrap.innerHTML;
  }

  function stripText(html) {
    var d = document.createElement('div');
    d.innerHTML = String(html || '');
    return (d.textContent || '').replace(/\u00a0/g, ' ').trim();
  }

  function isEmpty(html) {
    var d = document.createElement('div');
    d.innerHTML = String(html || '');
    if (d.querySelector('img,table,hr')) return false;
    return !stripText(html);
  }

  function saveSelection() {
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    var range = sel.getRangeAt(0);
    if (activeEditor && activeEditor.contains(range.commonAncestorContainer)) {
      savedRange = range.cloneRange();
    }
  }

  function restoreSelection() {
    if (!savedRange || !activeEditor) return;
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(savedRange);
  }

  function focusEditor() {
    if (!activeEditor) {
      activeEditor = document.querySelector('.rte-editor:not([data-readonly="1"])');
    }
    if (!activeEditor) return false;
    activeEditor.focus();
    restoreSelection();
    return true;
  }

  function exec(cmd, value) {
    if (!focusEditor()) return;
    try { document.execCommand(cmd, false, value == null ? null : value); } catch (_) { /* ignore */ }
    saveSelection();
  }

  function surroundInlineStyle(styles) {
    if (!focusEditor()) return;
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed) {
      exec('insertHTML', '<span style="' + styles + '">\u200b</span>');
      return;
    }
    try {
      document.execCommand('styleWithCSS', false, true);
    } catch (_) { /* ignore */ }
    var span = document.createElement('span');
    span.setAttribute('style', styles);
    try {
      var range = sel.getRangeAt(0);
      span.appendChild(range.extractContents());
      range.insertNode(span);
      sel.removeAllRanges();
      var r = document.createRange();
      r.selectNodeContents(span);
      sel.addRange(r);
      saveSelection();
    } catch (_) {
      exec('insertHTML', '<span style="' + styles + '">' + esc(sel.toString()) + '</span>');
    }
  }

  function applyBlockStyle(styles) {
    if (!focusEditor()) return;
    exec('formatBlock', 'p');
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    var node = sel.anchorNode;
    if (node && node.nodeType === 3) node = node.parentElement;
    while (node && node !== activeEditor && node.nodeType === 1) {
      if (/^(P|DIV|H1|H2|H3|LI|BLOCKQUOTE)$/i.test(node.tagName)) {
        node.style.cssText = (node.getAttribute('style') || '') + ';' + styles;
        break;
      }
      node = node.parentElement;
    }
    saveSelection();
  }

  function insertHtml(html) {
    if (!focusEditor()) return;
    exec('insertHTML', html);
  }

  function changeFontSize(delta) {
    if (!focusEditor()) return;
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    var node = sel.anchorNode;
    if (node && node.nodeType === 3) node = node.parentElement;
    while (node && node !== activeEditor) {
      if (node.nodeType === 1 && node.style && node.style.fontSize) break;
      node = node.parentElement;
    }
    var cur = 16;
    if (node && node !== activeEditor && node.style.fontSize) {
      cur = parseFloat(node.style.fontSize) || 16;
    }
    var next = Math.max(10, Math.min(48, cur + delta));
    surroundInlineStyle('font-size:' + next + 'px');
  }

  function pickFile(accept, cb) {
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = function () {
      var f = input.files && input.files[0];
      if (!f) return;
      var reader = new FileReader();
      reader.onload = function () { cb(f, reader.result); };
      reader.readAsDataURL(f);
    };
    input.click();
  }

  function insertTable(rows, cols) {
    rows = Math.max(1, Math.min(12, rows || 3));
    cols = Math.max(1, Math.min(8, cols || 3));
    var html = '<table style="border-collapse:collapse;width:100%"><tbody>';
    for (var r = 0; r < rows; r++) {
      html += '<tr>';
      for (var c = 0; c < cols; c++) {
        html += '<td style="border:1px solid #cbd5e1;padding:4px 8px;min-width:48px"><br></td>';
      }
      html += '</tr>';
    }
    html += '</tbody></table><p><br></p>';
    insertHtml(html);
  }

  function ribbonHtml() {
    var fontOpts = FONTS.map(function (f) {
      return '<option value="' + esc(f[1]) + '">' + esc(f[0]) + '</option>';
    }).join('');
    var sizeOpts = SIZES.map(function (s) {
      return '<option value="' + esc(s[1]) + '">' + esc(s[0]) + '</option>';
    }).join('');
    var symOpts = SYMBOLS.map(function (s) {
      return '<option value="' + esc(s) + '">' + esc(s) + '</option>';
    }).join('');
    var formulaOpts = FORMULAS.map(function (f) {
      return '<option value="' + esc(f[1]) + '">' + esc(f[0]) + '</option>';
    }).join('');

    return ''
      + '<div class="rte-ribbon" role="toolbar">'
      +   '<div class="rte-tabs">'
      +     '<button type="button" class="rte-tab on" data-tab="start">开始</button>'
      +     '<button type="button" class="rte-tab" data-tab="insert">插入</button>'
      +   '</div>'
      +   '<div class="rte-pane" data-pane="start">'
      +     '<div class="rte-row">'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">字体</div>'
      +         '<select class="rte-select" data-act="fontName" title="字体"><option value="">字体</option>' + fontOpts + '</select>'
      +       '</div>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">字号</div>'
      +         '<select class="rte-select" data-act="fontSizePx" title="字号"><option value="">字号</option>' + sizeOpts + '</select>'
      +       '</div>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">缩放</div>'
      +         '<div class="rte-btns">'
      +           '<button type="button" class="rte-btn" data-act="fontInc" title="增大字号">A+</button>'
      +           '<button type="button" class="rte-btn" data-act="fontDec" title="减小字号">A-</button>'
      +         '</div>'
      +       '</div>'
      +       '<span class="rte-vsep"></span>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">&nbsp;</div>'
      +         '<div class="rte-btns">'
      +           '<button type="button" class="rte-btn" data-act="bold" title="加粗"><b>B</b></button>'
      +           '<button type="button" class="rte-btn" data-act="italic" title="斜体"><i>I</i></button>'
      +           '<button type="button" class="rte-btn" data-act="underline" title="下划线"><u>U</u></button>'
      +           '<button type="button" class="rte-btn" data-act="strikeThrough" title="删除线"><s>Ab</s></button>'
      +           '<button type="button" class="rte-btn" data-act="superscript" title="上标">X²</button>'
      +           '<button type="button" class="rte-btn" data-act="subscript" title="下标">X₂</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="removeFormat" title="清除格式">清除</button>'
      +         '</div>'
      +       '</div>'
      +       '<span class="rte-vsep"></span>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">&nbsp;</div>'
      +         '<div class="rte-btns rte-color-btns">'
      +           '<label class="rte-btn rte-color" title="字色">字色<input type="color" data-act="foreColor" value="#1e3a5f" /></label>'
      +           '<label class="rte-btn rte-color" title="突出">突出<input type="color" data-act="hiliteColor" value="#fde047" /></label>'
      +           '<label class="rte-btn rte-color" title="底纹">底纹<input type="color" data-act="backColor" value="#e5e7eb" /></label>'
      +         '</div>'
      +       '</div>'
      +     '</div>'
      +     '<div class="rte-hsep"></div>'
      +     '<div class="rte-row">'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">&nbsp;</div>'
      +         '<div class="rte-btns">'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="insertUnorderedList">项目</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="insertOrderedList">编号</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="outdent">减缩</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="indent">加缩</button>'
      +         '</div>'
      +       '</div>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">字距</div>'
      +         '<select class="rte-select" data-act="letterSpacing" title="字距">'
      +           '<option value="">字距...</option>'
      +           '<option value="0">标准</option>'
      +           '<option value="0.05em">加宽</option>'
      +           '<option value="0.12em">较宽</option>'
      +           '<option value="-0.03em">紧缩</option>'
      +         '</select>'
      +       '</div>'
      +       '<span class="rte-vsep"></span>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">&nbsp;</div>'
      +         '<div class="rte-btns">'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="justifyLeft">左</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="justifyCenter">中</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="justifyRight">右</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="justifyFull">两端</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="justifyDistribute">分散</button>'
      +         '</div>'
      +       '</div>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">行距</div>'
      +         '<select class="rte-select" data-act="lineHeight" title="行距">'
      +           '<option value="">行距...</option>'
      +           '<option value="1">单倍</option>'
      +           '<option value="1.15">1.15</option>'
      +           '<option value="1.5">1.5</option>'
      +           '<option value="2">双倍</option>'
      +         '</select>'
      +       '</div>'
      +       '<span class="rte-vsep"></span>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">&nbsp;</div>'
      +         '<div class="rte-btns">'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="borderBox">框线</button>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="mark">标记</button>'
      +         '</div>'
      +       '</div>'
      +     '</div>'
      +     '<div class="rte-row rte-row-styles">'
      +       '<div class="rte-btns">'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="h1">标题1</button>'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="h2">标题2</button>'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="body">正文</button>'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="hr">分隔线</button>'
      +       '</div>'
      +     '</div>'
      +   '</div>'
      +   '<div class="rte-pane hidden" data-pane="insert">'
      +     '<div class="rte-row rte-row-insert">'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">表格</div>'
      +         '<select class="rte-select" data-act="table">'
      +           '<option value="">表格...</option>'
      +           '<option value="2x2">2×2</option>'
      +           '<option value="3x3">3×3</option>'
      +           '<option value="4x3">4×3</option>'
      +           '<option value="5x4">5×4</option>'
      +         '</select>'
      +       '</div>'
      +       '<div class="rte-insert-grid">'
        +         '<button type="button" class="rte-insert-btn" data-act="image"><span class="rte-ico rte-ico-img"></span><span>图片</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="doc"><span class="rte-ico rte-ico-doc"></span><span>文档</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="screenshot"><span class="rte-ico rte-ico-shot"></span><span>截屏</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="shape"><span class="rte-ico rte-ico-shape"></span><span>形状</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="icon"><span class="rte-ico rte-ico-icon"></span><span>图标</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="textbox"><span class="rte-ico rte-ico-text"></span><span>文本框</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="block"><span class="rte-ico rte-ico-block"></span><span>内容块</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="wordart"><span class="rte-ico rte-ico-art">A</span><span>艺术字</span></button>'
        +         '<button type="button" class="rte-insert-btn" data-act="chart"><span class="rte-ico rte-ico-chart"></span><span>图表</span></button>'
      +       '</div>'
      +       '<span class="rte-vsep"></span>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">符号</div>'
      +         '<select class="rte-select" data-act="symbol"><option value="">符号...</option>' + symOpts + '</select>'
      +       '</div>'
      +       '<div class="rte-group">'
      +         '<div class="rte-glabel">公式</div>'
      +         '<div class="rte-btns" style="flex-wrap:wrap">'
      +           '<select class="rte-select" data-act="formula"><option value="">公式...</option>' + formulaOpts + '</select>'
      +           '<button type="button" class="rte-btn rte-btn-text" data-act="formulaEditor">公式编辑器...</button>'
      +         '</div>'
      +       '</div>'
      +     '</div>'
      +     '<div class="rte-row">'
      +       '<div class="rte-btns">'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="outline">提纲</button>'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="sentence">句式</button>'
      +         '<button type="button" class="rte-btn rte-btn-text" data-act="device">设备</button>'
      +       '</div>'
      +     '</div>'
      +   '</div>'
      + '</div>';
  }

  function handleAct(act, el) {
    if (!act) return;
    if (act === 'bold' || act === 'italic' || act === 'underline' || act === 'strikeThrough'
      || act === 'superscript' || act === 'subscript' || act === 'removeFormat'
      || act === 'insertUnorderedList' || act === 'insertOrderedList'
      || act === 'outdent' || act === 'indent'
      || act === 'justifyLeft' || act === 'justifyCenter' || act === 'justifyRight' || act === 'justifyFull') {
      exec(act);
      return;
    }
    if (act === 'justifyDistribute') {
      applyBlockStyle('text-align:justify;letter-spacing:0.08em');
      return;
    }
    if (act === 'fontName') {
      if (el.value) exec('fontName', el.value);
      el.selectedIndex = 0;
      return;
    }
    if (act === 'fontSizePx') {
      if (el.value) surroundInlineStyle('font-size:' + el.value);
      el.selectedIndex = 0;
      return;
    }
    if (act === 'fontInc') { changeFontSize(2); return; }
    if (act === 'fontDec') { changeFontSize(-2); return; }
    if (act === 'foreColor') {
      try { document.execCommand('styleWithCSS', false, true); } catch (_) { /* ignore */ }
      exec('foreColor', el.value);
      return;
    }
    if (act === 'hiliteColor') {
      try { document.execCommand('styleWithCSS', false, true); } catch (_) { /* ignore */ }
      if (!document.execCommand('hiliteColor', false, el.value)) exec('backColor', el.value);
      return;
    }
    if (act === 'backColor') {
      applyBlockStyle('background-color:' + el.value);
      return;
    }
    if (act === 'letterSpacing') {
      if (el.value !== '') surroundInlineStyle('letter-spacing:' + el.value);
      el.selectedIndex = 0;
      return;
    }
    if (act === 'lineHeight') {
      if (el.value) applyBlockStyle('line-height:' + el.value);
      el.selectedIndex = 0;
      return;
    }
    if (act === 'borderBox') {
      applyBlockStyle('border:1px solid #94a3b8;padding:6px 8px');
      return;
    }
    if (act === 'mark') {
      surroundInlineStyle('background-color:#fef08a');
      return;
    }
    if (act === 'h1') { exec('formatBlock', 'h1'); return; }
    if (act === 'h2') { exec('formatBlock', 'h2'); return; }
    if (act === 'body') { exec('formatBlock', 'p'); return; }
    if (act === 'hr') { insertHtml('<hr><p><br></p>'); return; }
    if (act === 'table') {
      if (!el.value) return;
      var parts = el.value.split('x');
      insertTable(Number(parts[0]), Number(parts[1]));
      el.selectedIndex = 0;
      return;
    }
    if (act === 'image') {
      pickFile('image/*', function (_f, dataUrl) {
        insertHtml('<img src="' + dataUrl + '" alt="图片" style="max-width:100%;height:auto" />');
      });
      return;
    }
    if (act === 'doc') {
      pickFile('.doc,.docx,.pdf,.txt,.md', function (f, dataUrl) {
        insertHtml('<p><a href="' + dataUrl + '" download="' + esc(f.name) + '">📎 ' + esc(f.name) + '</a></p>');
      });
      return;
    }
    if (act === 'screenshot') {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
        window.alert('当前浏览器不支持截屏，请使用“图片”上传，或粘贴截图到编辑区。');
        return;
      }
      navigator.mediaDevices.getDisplayMedia({ video: true }).then(function (stream) {
        var video = document.createElement('video');
        video.srcObject = stream;
        video.onloadedmetadata = function () {
          video.play();
          setTimeout(function () {
            var canvas = document.createElement('canvas');
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            canvas.getContext('2d').drawImage(video, 0, 0);
            stream.getTracks().forEach(function (t) { t.stop(); });
            insertHtml('<img src="' + canvas.toDataURL('image/png') + '" alt="截屏" style="max-width:100%;height:auto" />');
          }, 200);
        };
      }).catch(function () {
        window.alert('截屏已取消或失败，可改用图片上传 / 粘贴。');
      });
      return;
    }
    if (act === 'shape') {
      insertHtml('<div style="display:inline-block;border:2px solid #3d62e8;border-radius:8px;padding:12px 18px;min-width:80px;text-align:center">形状</div>');
      return;
    }
    if (act === 'icon') {
      insertHtml('<span style="color:#3d62e8;font-size:1.2em">◆</span> ');
      return;
    }
    if (act === 'textbox') {
      insertHtml('<div style="border:1px dashed #94a3b8;padding:10px 12px;margin:6px 0;background:#f8fafc"><p>文本框内容</p></div>');
      return;
    }
    if (act === 'block') {
      insertHtml('<blockquote style="margin:8px 0;padding:8px 12px;border-left:4px solid #3d62e8;background:#eef2ff"><p>内容块</p></blockquote>');
      return;
    }
    if (act === 'wordart') {
      insertHtml('<p style="font-size:28px;font-weight:800;color:#2f4fc9;letter-spacing:0.08em;margin:8px 0">艺术字</p>');
      return;
    }
    if (act === 'chart') {
      insertHtml('<table style="border-collapse:collapse;margin:8px 0"><tr>'
        + '<td style="vertical-align:bottom;padding:0 4px"><div style="width:28px;height:40px;background:#93c5fd"></div></td>'
        + '<td style="vertical-align:bottom;padding:0 4px"><div style="width:28px;height:70px;background:#3d62e8"></div></td>'
        + '<td style="vertical-align:bottom;padding:0 4px"><div style="width:28px;height:55px;background:#60a5fa"></div></td>'
        + '</tr><tr><td colspan="3" style="text-align:center;font-size:12px;color:#64748b;padding-top:4px">示意图表</td></tr></table>');
      return;
    }
    if (act === 'symbol') {
      if (el.value) insertHtml(esc(el.value));
      el.selectedIndex = 0;
      return;
    }
    if (act === 'formula') {
      if (el.value) insertHtml('<code style="background:#f1f5f9;padding:2px 6px;border-radius:4px">' + esc(el.value) + '</code> ');
      el.selectedIndex = 0;
      return;
    }
    if (act === 'formulaEditor') {
      var text = window.prompt('输入公式内容（将以等宽文本插入）', 'σ = F / A');
      if (text) insertHtml('<code style="background:#f1f5f9;padding:2px 6px;border-radius:4px">' + esc(text) + '</code> ');
      return;
    }
    if (act === 'outline') {
      insertHtml('<ol><li>实验目的</li><li>实验步骤</li><li>数据分析</li><li>结论与反思</li></ol>');
      return;
    }
    if (act === 'sentence') {
      insertHtml('<p>本次实验测得最大力 F<sub>max</sub> 为 _____ N，对应位移 ΔL 为 _____ mm；断口形态呈 _____ 特征。</p>');
      return;
    }
    if (act === 'device') {
      insertHtml('<p><strong>实验设备：</strong>万能试验机、引伸计、游标卡尺、断口观察装置。</p>');
      return;
    }
  }

  function bindRibbon(ribbon) {
    ribbon.addEventListener('mousedown', function (e) {
      var tab = e.target.closest('.rte-tab');
      if (tab) {
        e.preventDefault();
        ribbon.querySelectorAll('.rte-tab').forEach(function (t) {
          t.classList.toggle('on', t === tab);
        });
        var name = tab.getAttribute('data-tab');
        ribbon.querySelectorAll('.rte-pane').forEach(function (pane) {
          pane.classList.toggle('hidden', pane.getAttribute('data-pane') !== name);
        });
        return;
      }
      var btn = e.target.closest('[data-act]');
      if (!btn) return;
      if (btn.tagName === 'SELECT' || btn.tagName === 'INPUT') return;
      e.preventDefault();
      handleAct(btn.getAttribute('data-act'), btn);
    });
    ribbon.addEventListener('change', function (e) {
      var el = e.target.closest('[data-act]');
      if (!el) return;
      handleAct(el.getAttribute('data-act'), el);
    });
    ribbon.addEventListener('input', function (e) {
      var el = e.target;
      if (el && el.matches('input[type="color"][data-act]')) {
        handleAct(el.getAttribute('data-act'), el);
      }
    });
  }

  function mountRibbon(host) {
    if (!host) return null;
    host.innerHTML = ribbonHtml();
    var ribbon = host.querySelector('.rte-ribbon');
    bindRibbon(ribbon);
    return ribbon;
  }

  function mount(root, opts) {
    opts = opts || {};
    var readOnly = !!opts.readOnly;
    var initial = plainToHtml(opts.value || '');
    var shared = !!opts.sharedRibbon;

    root.classList.add('rte');
    if (shared || readOnly) root.classList.add('rte-bare');
    root.innerHTML = ''
      + ((readOnly || shared) ? '' : ribbonHtml())
      + '<div class="rte-editor" contenteditable="' + (readOnly ? 'false' : 'true') + '"'
      + (readOnly ? ' data-readonly="1"' : '') + '></div>';

    var editor = root.querySelector('.rte-editor');
    editor.innerHTML = initial || (readOnly ? '<p class="sub">—</p>' : '<p><br></p>');

    if (!readOnly) {
      if (!shared) {
        var ribbon = root.querySelector('.rte-ribbon');
        if (ribbon) bindRibbon(ribbon);
      }
      editor.addEventListener('focus', function () {
        activeEditor = editor;
        document.querySelectorAll('.rte-editor').forEach(function (el) {
          el.classList.toggle('rte-focused', el === editor);
        });
      });
      editor.addEventListener('mouseup', saveSelection);
      editor.addEventListener('keyup', saveSelection);
      editor.addEventListener('blur', saveSelection);
      editor.addEventListener('keydown', function (e) {
        if (e.key === 'Tab') e.preventDefault();
      });
      editor.addEventListener('paste', function () {
        setTimeout(function () {
          editor.innerHTML = sanitize(editor.innerHTML) || '<p><br></p>';
          saveSelection();
        }, 0);
      });
      if (!activeEditor) activeEditor = editor;
    }

    return {
      el: editor,
      getHtml: function () {
        var html = sanitize(editor.innerHTML);
        return isEmpty(html) ? '' : html;
      },
      setHtml: function (html) {
        editor.innerHTML = plainToHtml(html) || '<p><br></p>';
      },
      isEmpty: function () {
        return isEmpty(editor.innerHTML);
      }
    };
  }

  global.LabRichText = {
    mount: mount,
    mountRibbon: mountRibbon,
    plainToHtml: plainToHtml,
    sanitize: sanitize,
    stripText: stripText,
    isEmpty: isEmpty
  };
})(window);
