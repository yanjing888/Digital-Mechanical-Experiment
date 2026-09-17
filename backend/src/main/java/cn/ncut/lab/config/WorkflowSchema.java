package cn.ncut.lab.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Additive schema: legacy records are retained and never reset. */
@Component
public class WorkflowSchema implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public WorkflowSchema(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void run(String... args) { create(jdbc); }
    public static void create(JdbcTemplate db) {
        db.execute("CREATE TABLE IF NOT EXISTS experiment_runs (id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL, group_id VARCHAR(64) NOT NULL, parent_id VARCHAR(64), payload LONGTEXT NOT NULL, revision INT NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS run_members (run_id VARCHAR(64) NOT NULL, sid VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL, PRIMARY KEY(run_id,sid))");
        db.execute("CREATE TABLE IF NOT EXISTS run_files (id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, owner_sid VARCHAR(64) NOT NULL, kind VARCHAR(24) NOT NULL, name VARCHAR(255) NOT NULL, mime VARCHAR(128) NOT NULL, sha256 VARCHAR(64) NOT NULL, contents LONGBLOB NOT NULL, extracted LONGTEXT, parse_status VARCHAR(32), created_at VARCHAR(40) NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS report_versions (id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, sid VARCHAR(32) NOT NULL, version INT NOT NULL, payload LONGTEXT NOT NULL, submitted_at VARCHAR(40) NOT NULL, UNIQUE(run_id,sid,version))");
        db.execute("CREATE TABLE IF NOT EXISTS workflow_settings (id VARCHAR(32) PRIMARY KEY, payload LONGTEXT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS adopted_runs (task_id VARCHAR(64) NOT NULL, sid VARCHAR(32) NOT NULL, run_id VARCHAR(64) NOT NULL, PRIMARY KEY(task_id,sid))");
        db.execute("CREATE TABLE IF NOT EXISTS legacy_records (run_id VARCHAR(64) PRIMARY KEY,payload LONGTEXT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS run_archives (id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64) NOT NULL,payload LONGTEXT NOT NULL,created_at VARCHAR(40) NOT NULL)");
    }
}
