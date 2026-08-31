'use strict';

const { initDb, query, withTransaction } = require('../server/db');

async function main() {
  await initDb();
  await withTransaction(async (conn) => {
    await conn.execute(`
      UPDATE students SET
        status = IF(task_id IS NULL OR task_id = '', 'none', 'assigned'),
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
        group_confirmed = 0,
        personal_json = '{}',
        personal_submitted = 0,
        personal_submitted_at = NULL,
        personal_score = NULL,
        personal_comment = ''
    `);
    await conn.execute(`
      UPDATE group_reports SET
        data_json = NULL,
        html = '',
        confirmed_by_json = '[]',
        submitted = 0,
        submitted_at = NULL,
        score = NULL,
        comment = '',
        source_sid = NULL
    `);
  });

  const students = await query(
    'SELECT sid, name, status, exp_flow, max_f, max_d, fracture_type FROM students ORDER BY sid'
  );
  const reports = await query(
    'SELECT task_id, group_id, submitted, source_sid FROM group_reports'
  );
  console.log(JSON.stringify({ students, reports }, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
}).then(() => process.exit(0));
