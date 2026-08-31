'use strict';

const XLSX = require('xlsx');

const HEADER_MAP = {
  '学号': 'sid',
  '学生学号': 'sid',
  'sid': 'sid',
  '姓名': 'name',
  '学生姓名': 'name',
  'name': 'name',
  '班级': 'cls',
  '行政班': 'cls',
  'cls': 'cls',
  'class': 'cls'
};

function normalizeHeader(h) {
  return String(h || '').trim().replace(/\s+/g, '');
}

function parseRosterBuffer(buffer, filename) {
  const wb = XLSX.read(buffer, { type: 'buffer' });
  const sheet = wb.Sheets[wb.SheetNames[0]];
  const raw = XLSX.utils.sheet_to_json(sheet, { defval: '' });
  if (!raw.length) return [];

  const rows = [];
  raw.forEach((item) => {
    const mapped = {};
    Object.keys(item).forEach((key) => {
      const field = HEADER_MAP[normalizeHeader(key)];
      if (field) mapped[field] = String(item[key]).trim();
    });
    if (mapped.sid && mapped.name) {
      rows.push({
        sid: mapped.sid,
        name: mapped.name,
        cls: mapped.cls || '—'
      });
    }
  });
  return rows;
}

function buildTemplateBuffer() {
  const data = [
    { '学号': '', '姓名': '', '班级': '' }
  ];
  const ws = XLSX.utils.json_to_sheet(data);
  ws['!cols'] = [{ wch: 14 }, { wch: 10 }, { wch: 12 }];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, '学生名单');
  return XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' });
}

module.exports = { parseRosterBuffer, buildTemplateBuffer };
