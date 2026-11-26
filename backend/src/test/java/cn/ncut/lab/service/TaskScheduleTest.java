package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskScheduleTest {
    final Instant start = Instant.parse("2026-12-01T06:00:00Z");
    JdbcTemplate db;
    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE tasks(id VARCHAR(64) PRIMARY KEY,time_text VARCHAR(128))");
        db.execute("CREATE TABLE task_configs(task_id VARCHAR(64) PRIMARY KEY,payload LONGTEXT)");
        db.execute("CREATE TABLE experiment_runs(id VARCHAR(64) PRIMARY KEY,task_id VARCHAR(64),parent_id VARCHAR(64),payload LONGTEXT,revision INT)");
        db.update("INSERT INTO tasks VALUES('task','2026-12-01 14:00')");
        db.update("INSERT INTO task_configs VALUES('task',?)",JsonUtil.write(Map.of("lessonState","scheduled","teacherScoreConfirmationRequired",false)));
        for(String id:List.of("a","b"))db.update("INSERT INTO experiment_runs VALUES(?,'task',NULL,?,0)",id,JsonUtil.write(Map.of("lessonState","scheduled","classOpen",false,"scoreVersion",5,"photos",List.of(Map.of("id","photo")),"audit",List.of())));
    }
    TaskSchedule at(Instant instant) { return new TaskSchedule(db,Clock.fixed(instant,ZoneOffset.UTC)); }
    Map<String,Object> run(String id) { return JsonUtil.readMap(db.queryForObject("SELECT payload FROM experiment_runs WHERE id=?",String.class,id),Map.of()); }
    int revision(String id) { return db.queryForObject("SELECT revision FROM experiment_runs WHERE id=?",Integer.class,id); }

    @Test void opensAllGroupsAtExactBeijingStartAndPreservesEvidence() {
        assertFalse(at(start.minusNanos(1)).openIfDue("task"));
        assertEquals("scheduled",run("a").get("lessonState"));
        assertTrue(at(start).openIfDue("task"));
        for(String id:List.of("a","b")) {
            assertEquals("active",run(id).get("lessonState")); assertEquals(true,run(id).get("classOpen"));
            assertEquals("automatic",run(id).get("startMode")); assertEquals(start.toString(),run(id).get("startedAt"));
            assertEquals(5,run(id).get("scoreVersion")); assertEquals(List.of(Map.of("id","photo")),run(id).get("photos"));
            assertEquals(1,revision(id));
        }
        at(start.plusSeconds(3600)).openIfDue("task"); assertEquals(1,revision("a"));
    }
    @Test void acceptsBothDateInputsAndExplicitOffsets() {
        for(String time:List.of("2026-12-01 14:00","2026-12-01T14:00:00","2026-12-01T14:00:00+08:00","2026-12-01T06:00:00Z"))assertEquals(start,TaskSchedule.startTime(time).orElseThrow());
        for(String time:List.of("","第一节","2026-99-99 14:00"))assertTrue(TaskSchedule.startTime(time).isEmpty());
    }
    @Test void undatedOrLegacyTimesStillWaitForTeacher() {
        for(String time:List.of("","下周")) {
            db.update("UPDATE tasks SET time_text=?",time);
            assertFalse(at(start).openIfDue("task")); assertEquals("scheduled",run("a").get("lessonState"));
        }
        assertTrue(at(start).openManually("task","Teacher")); assertEquals("manual",run("a").get("startMode"));
    }
    @Test void teacherCanStartEarlyAndTimerNeverReopensPublishedOrArchivedRecords() {
        at(start.minusSeconds(3600)).openManually("task","Teacher");
        Map<String,Object> closed=new LinkedHashMap<>(run("a"));closed.put("classOpen",false);closed.put("archived",true);
        db.update("UPDATE experiment_runs SET payload=? WHERE id='a'",JsonUtil.write(closed));
        at(start.plusSeconds(3600)).openIfDue("task");
        assertEquals(false,run("a").get("classOpen"));assertEquals(true,run("a").get("archived"));assertEquals(1,revision("a"));
    }
    @Test void concurrentArrivalsOnlyOpenTheLessonOnce() throws Exception {
        try(var executor=Executors.newFixedThreadPool(6)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++)futures.add(executor.submit(()->{gate.await();at(start).openIfDue("task");return null;}));
            gate.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,revision("a"));assertEquals(1,revision("b"));assertEquals(1,RunService.list(run("a").get("audit")).size());
    }
    @Test void failureRollsBackTheWholeLesson() {
        db.execute("ALTER TABLE experiment_runs ADD CONSTRAINT reject_b CHECK (id<>'b' OR revision=0)");
        assertThrows(RuntimeException.class,()->at(start).openIfDue("task"));
        assertEquals("scheduled",run("a").get("lessonState"));assertEquals(0,revision("a"));
        assertEquals("scheduled",JsonUtil.readMap(db.queryForObject("SELECT payload FROM task_configs",String.class),Map.of()).get("lessonState"));
    }
}
