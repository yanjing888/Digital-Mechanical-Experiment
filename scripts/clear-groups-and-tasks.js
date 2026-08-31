'use strict';

/**
 * 清空分组与下发任务，保留教师、实验、学生名单。
 * 便于演示：分组 → 下发任务 → 实验 → 报告 → 评阅。
 */
const { initDb, query, withTransaction } = require('../server/db');

async function main() {
  await initDb();
  await withTransaction(async (conn) => {
    await conn.execute('DELETE FROM sessions');
    await conn.execute('DELETE FROM group_reports');
    await conn.execute('DELETE FROM task_groups');
    await conn.execute('DELETE FROM tasks');
    await conn.execute('DELETE FROM lab_groups');
    await conn.execute(`
      UPDATE students SET
        group_id = NULL,
        task_id = NULL,
        exp_id = NULL,
        exp_name = NULL,
        tip = NULL,
        status = 'none',
        door_closed = 0,
        exp_flow = 'wait_door',
        acq_paused = 0,
        acq_json = '{"points":[],"t0":null}',
        max_f = 0,
        max_d = 0,
        fracture_img = NULL,
        fracture_type = NULL,
        fracture_conf = 0,
        fracture_analysis_json = NULL,
        fracture_self_json = NULL,
        group_confirmed = 0,
        personal_json = '{}',
        personal_submitted = 0,
        personal_submitted_at = NULL,
        personal_score = NULL,
        personal_comment = ''
    `);
  });

  const students = await query(
    'SELECT sid, name, cls, group_id, task_id, status FROM students ORDER BY sid'
  );
  const counts = {
    lab_groups: (await query('SELECT COUNT(*) AS n FROM lab_groups'))[0].n,
    tasks: (await query('SELECT COUNT(*) AS n FROM tasks'))[0].n,
    group_reports: (await query('SELECT COUNT(*) AS n FROM group_reports'))[0].n
  };
  console.log(JSON.stringify({ ok: true, counts, students }, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
}).then(() => process.exit(0));
