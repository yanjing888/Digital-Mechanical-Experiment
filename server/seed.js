'use strict';

const { initDb, query, withTransaction } = require('./db');
const { hashPassword } = require('./services/auth');

const EXPS = [
  { id: 'TENS', name: '材料拉伸', hours: 2, tip: '' },
  { id: 'COMP', name: '材料压缩', hours: 2, tip: '' }
];

/** 系统固定教师账号 */
const TEACHER = {
  id: 'T001',
  name: '王老师',
  username: 'teacher',
  password: '123456'
};

/** 学生默认密码（账号为学号） */
const STUDENT_DEFAULT_PASSWORD = '123456';

async function seed() {
  await initDb();
  const teacherHash = hashPassword(TEACHER.password);

  await withTransaction(async (conn) => {
    await conn.execute('SET FOREIGN_KEY_CHECKS = 0');
    for (const table of [
      'sessions', 'group_reports', 'task_groups', 'tasks',
      'students', 'lab_groups', 'experiments', 'teachers'
    ]) {
      await conn.execute('TRUNCATE TABLE `' + table + '`');
    }
    await conn.execute('SET FOREIGN_KEY_CHECKS = 1');

    await conn.execute(
      'INSERT INTO teachers (id, name, username, password_hash) VALUES (?, ?, ?, ?)',
      [TEACHER.id, TEACHER.name, TEACHER.username, teacherHash]
    );

    for (const e of EXPS) {
      await conn.execute(
        'INSERT INTO experiments (id, name, hours, tip) VALUES (?, ?, ?, ?)',
        [e.id, e.name, e.hours, e.tip]
      );
    }
  });

  console.log('MySQL seeded:', process.env.DB_NAME || 'lab_digital_platform');
  console.log('教师账号:', TEACHER.username, '/', TEACHER.password, '(' + TEACHER.name + ')');
  console.log('学生名单：请在「下发实验任务」页上传；默认密码', STUDENT_DEFAULT_PASSWORD);
}

if (require.main === module) {
  seed().then(() => process.exit(0)).catch((e) => {
    console.error(e);
    process.exit(1);
  });
}

module.exports = { seed, EXPS, TEACHER, STUDENT_DEFAULT_PASSWORD };
