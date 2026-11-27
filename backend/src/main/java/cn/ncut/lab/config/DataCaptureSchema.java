package cn.ncut.lab.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(55)
public class DataCaptureSchema implements CommandLineRunner {
    private final JdbcTemplate db;
    public DataCaptureSchema(JdbcTemplate db) { this.db = db; }
    public static void create(JdbcTemplate db) {
        db.execute("CREATE TABLE IF NOT EXISTS experiment_data_claims (fingerprint VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64) NOT NULL,trial_id VARCHAR(32) NOT NULL,created_at VARCHAR(40) NOT NULL)");
    }
    @Override public void run(String... args) { create(db); }
}
