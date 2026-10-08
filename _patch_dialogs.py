# -*- coding: utf-8 -*-
from pathlib import Path
import re

p = Path(r'd:\yanjing\97 projects\北方工大力学实验数字化升级\public\js\app.js')
t = p.read_text(encoding='utf-8')

t = t.replace(
    "if (!confirm('删除该小组？组内学生将变为未分组。')) return;",
    "if (!(await showConfirm('删除该小组？组内学生将变为未分组。'))) return;"
)

t = re.sub(r'(?<![.\w])alert\(', 'await showAlert(', t)
t = t.replace(
    '.catch(function (e) { await showAlert(e.message); })',
    '.catch(function (e) { showAlert(e.message); })'
)

p.write_text(t, encoding='utf-8')
print('alert leftover', len(re.findall(r'(?<![.\w])alert\(', t)))
print('confirm leftover', t.count('confirm('))
print('showAlert', t.count('showAlert('))
print('showConfirm', t.count('showConfirm('))
