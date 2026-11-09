package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterGroupingTest {
    private JdbcTemplate db;
    private StoreService store;

    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE students(sid VARCHAR(32) PRIMARY KEY,name VARCHAR(64),cls VARCHAR(64),group_id VARCHAR(64),password_hash VARCHAR(255),status VARCHAR(32),personal_json LONGTEXT,acq_json LONGTEXT)");
        db.execute("CREATE TABLE lab_groups(id VARCHAR(64) PRIMARY KEY,name VARCHAR(64))");
        db.execute("CREATE TABLE task_groups(task_id VARCHAR(64),group_id VARCHAR(64))");
        db.execute("CREATE TABLE workflow_settings(id VARCHAR(32) PRIMARY KEY,payload LONGTEXT)");
        store = new StoreService(db);
        ReflectionTestUtils.setField(store, "studentDefaultPassword", "123456");
        db.update("INSERT INTO lab_groups VALUES('old','Historical'),('issued','Issued'),('shared','Shared')");
        db.update("INSERT INTO students(sid,name,cls,group_id) VALUES('old','Old','A','old'),('issued','Issued','A','issued'),('outside','Outside','A','shared'),('current','Current','A','shared')");
        db.update("INSERT INTO task_groups VALUES('task','issued')");
    }

    private Map<String,String> row(String sid) { return Map.of("sid", sid, "name", sid, "cls", "A"); }
    private String group(String sid) { return db.queryForObject("SELECT group_id FROM students WHERE sid=?", String.class, sid); }
    private void groupBatch(String teacher) { store.autoGroup(4, null, "even", "Test", teacher); }

    @Test void singleStudentImportDoesNotGroupHistoricalStudents() {
        store.importRoster(List.of(row("new")), "teacher");
        groupBatch("teacher");
        String gid = group("new");
        assertNotNull(gid);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM students WHERE group_id=?", Integer.class, gid));
        assertEquals("old", group("old"));
        assertEquals("issued", group("issued"));
        assertEquals("shared", group("outside"));
        assertEquals("shared", group("current"));
    }

    @Test void latestSuccessfulImportReplacesScopeAndSurvivesServiceRestart() {
        store.importRoster(List.of(row("first")), "teacher");
        store.importRoster(List.of(row("second"), row("second")), "teacher");
        assertEquals(List.of("second"), new StoreService(db).importedStudentIds("teacher"));
        groupBatch("teacher");
        assertNull(group("first"));
        assertNotNull(group("second"));
    }

    @Test void teacherWithoutImportCannotFallBackToAllStudents() {
        store.importRoster(List.of(row("new")), "teacher-one");
        assertThrows(ApiException.class, () -> groupBatch("teacher-two"));
        assertNull(group("new"));
        assertEquals(List.of("new"), store.importedStudentIds("teacher-one"));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM lab_groups", Integer.class));
    }

    @Test void issuedStudentsAreNeverMovedEvenWhenImported() {
        store.importRoster(List.of(row("issued"), row("new")), "teacher");
        groupBatch("teacher");
        assertEquals("issued", group("issued"));
        assertNotEquals("issued", group("new"));
        store.importRoster(List.of(row("issued")), "teacher");
        assertThrows(ApiException.class, () -> groupBatch("teacher"));
    }

    @Test void regroupingOnlyMovesImportedMembersAndPreservesSharedGroup() {
        store.importRoster(List.of(row("current")), "teacher");
        groupBatch("teacher");
        String firstGroup = group("current");
        assertNotEquals("shared", firstGroup);
        assertEquals("shared", group("outside"));
        groupBatch("teacher");
        assertNotEquals(firstGroup, group("current"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM lab_groups WHERE id=?", Integer.class, firstGroup));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM lab_groups WHERE id='shared'", Integer.class));
    }

    @Test void rejectedImportKeepsPreviousScope() {
        store.importRoster(List.of(row("current")), "teacher");
        assertThrows(ApiException.class, () -> store.importRoster(List.of(), "teacher"));
        assertEquals(List.of("current"), store.importedStudentIds("teacher"));
    }
}
