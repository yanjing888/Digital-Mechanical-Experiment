package cn.ncut.lab.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 兼容学校已有名单，新增可选的专业信息。 */
@Component
@Order(50)
public class RosterSchema implements CommandLineRunner {
    private final JdbcTemplate db;
    public RosterSchema(JdbcTemplate db) { this.db = db; }
    @Override public void run(String... args) {
        Boolean exists = db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "students", "major")) {
                return columns.next();
            }
        });
        if (!Boolean.TRUE.equals(exists)) db.execute("ALTER TABLE students ADD COLUMN major VARCHAR(128) NULL");
    }
}
