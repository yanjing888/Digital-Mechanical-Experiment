package cn.ncut.lab.config;

import cn.ncut.lab.util.PasswordUtil;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 建表 + 兼容旧库补列 + 空库时植入默认教师/实验。
 * 与 Node 版 db.js migrate() / seed.js 保持一致，可直接复用同一个数据库。
 */
@Component
public class DbInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    public DbInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        migrate();
        seedIfEmpty();
    }

    private void migrate() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS teachers (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(64) NOT NULL UNIQUE,
              username VARCHAR(64) NULL UNIQUE,
              password_hash VARCHAR(255) NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS experiments (
              id VARCHAR(32) PRIMARY KEY,
              name VARCHAR(128) NOT NULL,
              hours INT NOT NULL,
              tip TEXT NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lab_groups (
              id VARCHAR(32) PRIMARY KEY,
              name VARCHAR(64) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
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
        """);
        jdbc.execute("""
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
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS task_groups (
              task_id VARCHAR(64) NOT NULL,
              group_id VARCHAR(32) NOT NULL,
              PRIMARY KEY (task_id, group_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
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
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
              token VARCHAR(64) PRIMARY KEY,
              role VARCHAR(16) NOT NULL,
              student_id VARCHAR(32) NULL,
              teacher_name VARCHAR(64) NULL,
              created_at VARCHAR(64) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);

        ensureColumn("teachers", "username", "VARCHAR(64) NULL");
        ensureColumn("teachers", "password_hash", "VARCHAR(255) NULL");
        ensureColumn("students", "password_hash", "VARCHAR(255) NULL");
        ensureColumn("students", "tip", "TEXT NULL");
        ensureColumn("students", "fracture_analysis_json", "LONGTEXT NULL");
        ensureColumn("students", "fracture_self_json", "LONGTEXT NULL");
        ensureColumn("tasks", "tip", "TEXT NULL");
        ensureColumn("group_reports", "deductions_json", "LONGTEXT NULL");
        ensureColumn("group_reports", "material_type", "VARCHAR(16) NULL");
        ensureUniqueIndex("teachers", "uk_teachers_username", "username");
        try {
            jdbc.execute("ALTER TABLE students MODIFY group_id VARCHAR(32) NULL");
        } catch (Exception ignored) {}
    }

    private void seedIfEmpty() {
        Integer teachers = jdbc.queryForObject("SELECT COUNT(*) FROM teachers", Integer.class);
        if (teachers != null && teachers == 0) {
            jdbc.update("INSERT INTO teachers (id, name, username, password_hash) VALUES (?, ?, ?, ?)",
                    "T001", "王老师", "teacher", PasswordUtil.hash("123456"));
        }
        Integer exps = jdbc.queryForObject("SELECT COUNT(*) FROM experiments", Integer.class);
        if (exps != null && exps == 0) {
            jdbc.update("INSERT INTO experiments (id, name, hours, tip) VALUES (?, ?, ?, ?)", "TENS", "材料拉伸", 2, "");
            jdbc.update("INSERT INTO experiments (id, name, hours, tip) VALUES (?, ?, ?, ?)", "COMP", "材料压缩", 2, "");
        }
    }

    private void ensureColumn(String table, String column, String def) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (n != null && n == 0) {
            jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + def);
        }
    }

    private void ensureUniqueIndex(String table, String indexName, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (n != null && n == 0) {
            try {
                jdbc.execute("CREATE UNIQUE INDEX `" + indexName + "` ON `" + table + "` (`" + column + "`)");
            } catch (Exception ignored) {}
        }
    }
}
