package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Opens a whole scheduled lesson atomically, including when a student arrives after its start time. */
final class TaskSchedule {
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate transaction;

    TaskSchedule(JdbcTemplate db) { this(db, Clock.systemUTC()); }
    TaskSchedule(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
    }

    static Optional<Instant> startTime(Object value) {
        String text = RunService.str(value);
        if (text.isEmpty()) return Optional.empty();
        String normalized = text.replace(' ', 'T');
        try { return Optional.of(OffsetDateTime.parse(normalized).toInstant()); }
        catch (DateTimeParseException ignored) { }
        try { return Optional.of(LocalDateTime.parse(normalized).atZone(ZoneId.of("Asia/Shanghai")).toInstant()); }
        catch (DateTimeParseException ignored) { return Optional.empty(); }
    }

    boolean openIfDue(String taskId) {
        var rows = db.queryForList("SELECT time_text FROM tasks WHERE id=?", taskId);
        if (rows.isEmpty() || startTime(rows.get(0).get("time_text")).filter(t -> !clock.instant().isBefore(t)).isEmpty()) return false;
        open(taskId, "system", true);
        return true; // The caller must reread the current row, including after another request opened it.
    }

    boolean openManually(String taskId, String teacher) { return open(taskId, teacher, false); }

    private boolean open(String taskId, String actor, boolean automatic) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var rows = db.queryForList("SELECT payload FROM task_configs WHERE task_id=? FOR UPDATE", taskId);
            if (rows.isEmpty()) return false;
            Map<String,Object> config = JsonUtil.readMap(RunService.str(rows.get(0).get("payload")), new LinkedHashMap<>());
            if (!"scheduled".equals(config.get("lessonState"))) return false;
            if (automatic) {
                var tasks = db.queryForList("SELECT time_text FROM tasks WHERE id=?", taskId);
                if (tasks.isEmpty() || startTime(tasks.get(0).get("time_text")).filter(t -> !clock.instant().isBefore(t)).isEmpty()) return false;
            }
            String startedAt = clock.instant().toString();
            String mode = automatic ? "automatic" : "manual";
            for (var row : db.queryForList("SELECT id,payload,revision FROM experiment_runs WHERE task_id=? AND parent_id IS NULL ORDER BY id FOR UPDATE", taskId)) {
                Map<String,Object> run = JsonUtil.readMap(RunService.str(row.get("payload")), new LinkedHashMap<>());
                if (!"scheduled".equals(run.get("lessonState")) || Boolean.TRUE.equals(run.get("archived"))) continue;
                run.put("lessonState", "active"); run.put("classOpen", true);
                run.put("startedAt", startedAt); run.put("startMode", mode);
                var audit = new ArrayList<>(RunService.list(run.get("audit")));
                audit.add(Map.of("at", startedAt, "by", actor, "action", automatic ? "按课次时间自动开课" : "开始本课次实验"));
                run.put("audit", audit);
                int changed = db.update("UPDATE experiment_runs SET payload=?,revision=revision+1 WHERE id=? AND revision=?", JsonUtil.write(run), row.get("id"), row.get("revision"));
                if (changed != 1) throw new ApiException(409, "课次状态已更新，请重试");
            }
            config.put("lessonState", "active"); config.put("startedAt", startedAt); config.put("startMode", mode);
            db.update("UPDATE task_configs SET payload=? WHERE task_id=?", JsonUtil.write(config), taskId);
            return true;
        }));
    }
}
