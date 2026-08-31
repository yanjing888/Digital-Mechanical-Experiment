'use strict';

const { initDb, query, withTransaction } = require('../server/db');

async function main() {
  await initDb();
  await withTransaction(async (conn) => {
    await conn.execute(`
      UPDATE students SET
        fracture_img = NULL,
        fracture_type = NULL,
        fracture_conf = 0,
        fracture_analysis_json = NULL,
        group_confirmed = 0,
        exp_flow = CASE
          WHEN exp_flow IN ('done_viz', 'post_break') THEN 'post_break'
          ELSE exp_flow
        END,
        status = CASE
          WHEN status IN ('lab_done', 'submitted') THEN 'in_lab'
          ELSE status
        END
      WHERE fracture_img IS NOT NULL OR fracture_type IS NOT NULL OR fracture_analysis_json IS NOT NULL
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
  const rows = await query(
    'SELECT sid, name, status, exp_flow, fracture_img IS NOT NULL AS has_img, max_f, max_d FROM students ORDER BY sid'
  );
  console.log(JSON.stringify(rows, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
}).then(() => process.exit(0));
