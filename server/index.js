'use strict';

const path = require('path');
const express = require('express');
const cors = require('cors');
const { initDb, query } = require('./db');
const { seed } = require('./seed');
const api = require('./routes/api');

const PORT = Number(process.env.PORT || 3780);
const PUBLIC = path.join(__dirname, '..', 'public');

async function boot() {
  const cfg = await initDb();
  const teachers = await query('SELECT COUNT(*) AS n FROM teachers');
  if (!teachers[0] || Number(teachers[0].n) === 0) {
    console.log('Empty database, seeding demo data...');
    await seed();
  }

  const app = express();
  app.use(cors());
  app.use(express.json({ limit: '15mb' }));
  app.use(express.urlencoded({ extended: true, limit: '15mb' }));

  app.use('/api', api);
  app.use(express.static(PUBLIC));

  app.get('*', (req, res, next) => {
    if (req.path.startsWith('/api')) return next();
    res.sendFile(path.join(PUBLIC, 'index.html'));
  });

  app.use((err, _req, res, _next) => {
    console.error(err);
    res.status(err.status || 500).json({ error: err.message || '服务器错误' });
  });

  app.listen(PORT, () => {
    console.log(`力学实验数字化平台运行中: http://localhost:${PORT}`);
    console.log(`MySQL: ${cfg.user}@${cfg.host}:${cfg.port}/${cfg.database}`);
  });
}

boot().catch((e) => {
  console.error('启动失败:', e.message || e);
  process.exit(1);
});
