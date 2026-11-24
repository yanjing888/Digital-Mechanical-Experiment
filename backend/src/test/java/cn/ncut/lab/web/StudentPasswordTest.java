package cn.ncut.lab.web;

import cn.ncut.lab.util.PasswordUtil;
import cn.ncut.lab.service.StudentPasswordVault;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StudentPasswordTest {
    JdbcTemplate db;
    UserManagementController controller;
    MockHttpServletRequest request;
    StudentPasswordVault vault;
    @TempDir Path directory;
    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE students(sid VARCHAR(32) PRIMARY KEY,password_hash VARCHAR(255))");
        db.execute("CREATE TABLE sessions(token VARCHAR(64) PRIMARY KEY,role VARCHAR(16),student_id VARCHAR(32))");
        db.update("INSERT INTO students VALUES('2026010001',?),('2026010002',?)",PasswordUtil.hash("2026010001"),PasswordUtil.hash("2026010002"));
        db.update("INSERT INTO sessions VALUES('current','student','2026010001'),('other','student','2026010001'),('classmate','student','2026010002')");
        controller=new UserManagementController(db);
        vault=new StudentPasswordVault(db,directory.resolve("student-password.key").toString());
        controller.setPasswordVault(vault);
        request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer current");request.setAttribute(Sessions.ATTR,Map.of("role","student","student_id","2026010001"));
    }
    Map<String,Object> body(String old,String next,String confirm) {return Map.of("currentPassword",old,"newPassword",next,"confirmPassword",confirm,"sid","2026010002");}
    String stored(String sid) {return db.queryForObject("SELECT password_hash FROM students WHERE sid=?",String.class,sid);}
    @Test void changeOwnPasswordKeepsCurrentSessionAndDoesNotChangeAnotherStudent() {
        String next=" NewPassword123 ";
        controller.changeOwnPassword(request,body("2026010001",next,next));
        assertTrue(PasswordUtil.verify(next,stored("2026010001")));
        assertEquals(next,vault.reveal("2026010001",stored("2026010001")).orElseThrow());
        assertNotEquals(next,db.queryForObject("SELECT ciphertext FROM student_password_secrets WHERE sid='2026010001'",String.class));
        assertFalse(PasswordUtil.verify("2026010001",stored("2026010001")));
        assertTrue(PasswordUtil.verify("2026010002",stored("2026010002")));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sessions WHERE token='current'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sessions WHERE token='other'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sessions WHERE token='classmate'",Integer.class));
    }
    @Test void rejectsInvalidChangesWithoutChangingPasswordOrSessions() {
        for(var b:java.util.List.of(body("wrong","NewPass123","NewPass123"),body("2026010001","short","short"),body("2026010001","NewPass123","Mismatch123"),body("2026010001","2026010001","2026010001"),body("2026010001"," ".repeat(8)," ".repeat(8)))) {
            assertEquals(400,assertThrows(ApiException.class,()->controller.changeOwnPassword(request,b)).getStatus());
        }
        assertTrue(PasswordUtil.verify("2026010001",stored("2026010001")));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM sessions",Integer.class));
    }
    @Test void rejectsTeacherAndUnauthenticatedRequests() {
        request.setAttribute(Sessions.ATTR,Map.of("role","teacher","teacher_name","Teacher"));
        assertEquals(403,assertThrows(ApiException.class,()->controller.changeOwnPassword(request,body("2026010001","NewPass123","NewPass123"))).getStatus());
        request.removeAttribute(Sessions.ATTR);
        assertEquals(401,assertThrows(ApiException.class,()->controller.changeOwnPassword(request,body("2026010001","NewPass123","NewPass123"))).getStatus());
    }
    @Test void onlyTeachersCanRevealCurrentPasswordAndResponsesAreNotCached() {
        assertEquals(403,assertThrows(ApiException.class,()->controller.revealStudentPassword(request,"2026010001")).getStatus());
        controller.changeOwnPassword(request,body("2026010001","ChangedPass123","ChangedPass123"));
        request.setAttribute(Sessions.ATTR,Map.of("role","teacher","teacher_name","Teacher"));
        var response=controller.revealStudentPassword(request,"2026010001");
        assertEquals("ChangedPass123",response.getBody().get("password"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("2026010002",controller.revealStudentPassword(request,"2026010002").getBody().get("password"));
        db.update("UPDATE students SET password_hash=? WHERE sid='2026010002'",PasswordUtil.hash("UnknownLegacyPassword"));
        var unavailable=controller.revealStudentPassword(request,"2026010002").getBody();
        assertEquals(false,unavailable.get("available"));assertFalse(unavailable.containsKey("password"));
        controller.resetStudentPassword(request,"2026010002",Map.of("password","TeacherReset123"));
        assertEquals("TeacherReset123",controller.revealStudentPassword(request,"2026010002").getBody().get("password"));
    }
    @Test void encryptionSurvivesRestartAndMissingKeyCannotSilentlyReplaceOldKey() {
        controller.changeOwnPassword(request,body("2026010001","ChangedPass123","ChangedPass123"));
        var restarted=new StudentPasswordVault(db,directory.resolve("student-password.key").toString());
        assertEquals("ChangedPass123",restarted.reveal("2026010001",stored("2026010001")).orElseThrow());
        assertThrows(IllegalStateException.class,()->new StudentPasswordVault(db,directory.resolve("missing.key").toString()));
        db.update("UPDATE students SET password_hash=? WHERE sid='2026010001'",PasswordUtil.hash("ChangedElsewhere123"));
        assertTrue(restarted.reveal("2026010001",stored("2026010001")).isEmpty());
    }
}
