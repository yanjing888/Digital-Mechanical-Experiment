'use strict';

const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');

function loadEnv() {
  const envPath = path.join(__dirname, '..', '.env');
  if (!fs.existsSync(envPath)) return;
  const text = fs.readFileSync(envPath, 'utf8');
  text.split(/\r?\n/).forEach((line) => {
    const s = line.trim();
    if (!s || s.startsWith('#')) return;
    const i = s.indexOf('=');
    if (i < 0) return;
    const key = s.slice(0, i).trim();
    let val = s.slice(i + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = val;
  });
}

loadEnv();

const config = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || '',
  database: process.env.DB_NAME || 'lab_digital_platform',
  waitForConnections: true,
  connectionLimit: 10,
  charset: 'utf8mb4'
};

let pool = null;

async function getPool() {
  if (!pool) {
    pool = mysql.createPool(config);
  }
  return pool;
}

async function query(sql, params) {
  const p = await getPool();
  const [rows] = await p.execute(sql, params || []);
  return rows;
}

async function withTransaction(fn) {
  const p = await getPool();
  const conn = await p.getConnection();
  try {
    await conn.beginTransaction();
    const result = await fn(conn);
    await conn.commit();
    return result;
  } catch (e) {
    await conn.rollback();
    throw e;
  } finally {
    conn.release();
  }
}

async function migrate() {
  await query(`
    CREATE TABLE IF NOT EXISTS teachers (
      id VARCHAR(64) PRIMARY KEY,
      name VARCHAR(64) NOT NULL UNIQUE,
      username VARCHAR(64) NULL UNIQUE,
      password_hash VARCHAR(255) NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS experiments (
      id VARCHAR(32) PRIMARY KEY,
      name VARCHAR(128) NOT NULL,
      hours INT NOT NULL,
      tip TEXT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS lab_groups (
      id VARCHAR(32) PRIMARY KEY,
      name VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS students (
      sid VARCHAR(32) PRIMARY KEY,
      name VARCHAR(64) NOT NULL,
      cls VARCHAR(64) NOT NULL,
      group_id VARCHAR(32) NULL,
      password_hash VARCHAR(255) NULL,
      status VARCHAR(32) NOT NULL DEFAULT 'none',
      task_id VARCHAR(64) NULL,
      exp_id VARCHAR(32) NULL,
      exp_name VARCHAR(128) NULL,
      note TEXT NULL,
      place VARCHAR(128) NULL,
      time_text VARCHAR(128) NULL,
      door_closed TINYINT NOT NULL DEFAULT 0,
      exp_flow VARCHAR(32) NOT NULL DEFAULT 'wait_door',
      acq_paused TINYINT NOT NULL DEFAULT 0,
      acq_json LONGTEXT NULL,
      max_f DOUBLE NOT NULL DEFAULT 0,
      max_d DOUBLE NOT NULL DEFAULT 0,
      fracture_img LONGTEXT NULL,
      fracture_type VARCHAR(32) NULL,
      fracture_conf DOUBLE NULL DEFAULT 0,
      group_confirmed TINYINT NOT NULL DEFAULT 0,
      personal_json LONGTEXT NULL,
      personal_submitted TINYINT NOT NULL DEFAULT 0,
      personal_submitted_at VARCHAR(64) NULL,
      personal_score DOUBLE NULL,
      personal_comment TEXT NULL,
      INDEX idx_students_group (group_id),
      INDEX idx_students_task (task_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS tasks (
      id VARCHAR(64) PRIMARY KEY,
      exp_id VARCHAR(32) NOT NULL,
      exp_name VARCHAR(128) NOT NULL,
      place VARCHAR(128) NULL,
      time_text VARCHAR(128) NULL,
      note TEXT NULL,
      teacher VARCHAR(64) NOT NULL,
      created_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS task_groups (
      task_id VARCHAR(64) NOT NULL,
      group_id VARCHAR(32) NOT NULL,
      PRIMARY KEY (task_id, group_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS group_reports (
      task_id VARCHAR(64) NOT NULL,
      group_id VARCHAR(32) NOT NULL,
      data_json LONGTEXT NULL,
      html LONGTEXT NULL,
      confirmed_by_json TEXT NULL,
      submitted TINYINT NOT NULL DEFAULT 0,
      submitted_at VARCHAR(64) NULL,
      score DOUBLE NULL,
      comment TEXT NULL,
      source_sid VARCHAR(32) NULL,
      PRIMARY KEY (task_id, group_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);
  await query(`
    CREATE TABLE IF NOT EXISTS sessions (
      token VARCHAR(64) PRIMARY KEY,
      role VARCHAR(16) NOT NULL,
      student_id VARCHAR(32) NULL,
      teacher_name VARCHAR(64) NULL,
      created_at VARCHAR(64) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  `);

  // 兼容旧库：补齐账号密码字段
  await ensureColumn('teachers', 'username', 'VARCHAR(64) NULL');
  await ensureColumn('teachers', 'password_hash', 'VARCHAR(255) NULL');
  await ensureColumn('students', 'password_hash', 'VARCHAR(255) NULL');
  await ensureColumn('students', 'tip', 'TEXT NULL');
  await ensureColumn('students', 'fracture_analysis_json', 'LONGTEXT NULL');
  await ensureColumn('tasks', 'tip', 'TEXT NULL');
  // 断口学生自评（特征描述 + 塑性/脆性自主判断）
  await ensureColumn('students', 'fracture_self_json', 'LONGTEXT NULL');
  // 实验操作记录：扣分制（基础分 100，扣分明细）+ 教师材质录入兜底
  await ensureColumn('group_reports', 'deductions_json', 'LONGTEXT NULL');
  await ensureColumn('group_reports', 'material_type', "VARCHAR(16) NULL");
  await ensureUniqueIndex('teachers', 'uk_teachers_username', 'username');
  // 允许学生暂未分组
  try {
    await query('ALTER TABLE students MODIFY group_id VARCHAR(32) NULL');
  } catch (_) {}
}

async function ensureColumn(table, column, definition) {
  const rows = await query(
    `SELECT COUNT(*) AS n FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?`,
    [table, column]
  );
  if (Number(rows[0].n) === 0) {
    await query(`ALTER TABLE \`${table}\` ADD COLUMN \`${column}\` ${definition}`);
  }
}

async function ensureUniqueIndex(table, indexName, column) {
  const rows = await query(
    `SELECT COUNT(*) AS n FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?`,
    [table, indexName]
  );
  if (Number(rows[0].n) === 0) {
    await query(`CREATE UNIQUE INDEX \`${indexName}\` ON \`${table}\` (\`${column}\`)`);
  }
}

async function initDb() {
  // ensure database exists using connection without database
  const bootstrap = await mysql.createConnection({
    host: config.host,
    port: config.port,
    user: config.user,
    password: config.password,
    multipleStatements: true
  });
  await bootstrap.query(
    `CREATE DATABASE IF NOT EXISTS \`${config.database}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`
  );
  await bootstrap.end();
  await getPool();
  await migrate();
  return config;
}

module.exports = { getPool, query, withTransaction, initDb, migrate, config };
