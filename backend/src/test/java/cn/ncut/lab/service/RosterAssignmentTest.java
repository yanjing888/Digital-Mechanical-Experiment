package cn.ncut.lab.service;

import cn.ncut.lab.config.WorkflowSchema;
import cn.ncut.lab.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterAssignmentTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions {
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate db; StoreService store; RunService runs; RosterAssignmentService assignments; TransactionTemplate tx;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));WorkflowSchema.create(db);
        db.execute("CREATE TABLE students(sid VARCHAR(32) PRIMARY KEY,name VARCHAR(64),cls VARCHAR(64),major VARCHAR(128),group_id VARCHAR(32),password_hash VARCHAR(255),status VARCHAR(32),personal_json LONGTEXT,acq_json LONGTEXT)");
        for(String field:List.of("task_id","exp_id","exp_name","note","tip","place","time_text","exp_flow","fracture_img","fracture_type","fracture_analysis_json","fracture_self_json","personal_submitted_at","personal_comment")) db.execute("ALTER TABLE students ADD "+field+" LONGTEXT");
        for(String field:List.of("door_closed","acq_paused","max_f","max_d","fracture_conf","group_confirmed","personal_submitted","personal_score")) db.execute("ALTER TABLE students ADD "+field+" DOUBLE DEFAULT 0");
        db.execute("CREATE TABLE lab_groups(id VARCHAR(32) PRIMARY KEY,name VARCHAR(64))");
        db.execute("CREATE TABLE tasks(id VARCHAR(64) PRIMARY KEY,exp_id VARCHAR(32),exp_name VARCHAR(64),place VARCHAR(255),time_text VARCHAR(128),note LONGTEXT,tip LONGTEXT,teacher VARCHAR(64),created_at VARCHAR(64))");
        db.execute("CREATE TABLE task_groups(task_id VARCHAR(64),group_id VARCHAR(32))");
        db.execute("CREATE TABLE group_reports(task_id VARCHAR(64),group_id VARCHAR(32),data_json LONGTEXT,html LONGTEXT,confirmed_by_json LONGTEXT,submitted INT,submitted_at VARCHAR(64),score DOUBLE,comment LONGTEXT,source_sid VARCHAR(32),deductions_json LONGTEXT,material_type VARCHAR(32),PRIMARY KEY(task_id,group_id))");
        db.execute("CREATE TABLE teachers(id VARCHAR(64),name VARCHAR(64),username VARCHAR(64),password_hash VARCHAR(255),active INT)");
        db.execute("CREATE TABLE admins(name VARCHAR(64),username VARCHAR(64),password_hash VARCHAR(255))");
        db.execute("CREATE TABLE sessions(token VARCHAR(64),role VARCHAR(16),student_id VARCHAR(32),teacher_name VARCHAR(64),created_at VARCHAR(64))");
        context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->ds);
        context.registerBean(JdbcTemplate.class,()->db);
        context.register(Transactions.class);
        context.registerBean(StoreService.class,()->new StoreService(db));
        context.registerBean(RunService.class,()->new RunService(db,null));
        context.registerBean(RosterAssignmentService.class);
        context.refresh();
        store=context.getBean(StoreService.class);runs=context.getBean(RunService.class);assignments=context.getBean(RosterAssignmentService.class);
        store.importRoster(List.of(row("100001","A"),row("100002","A"),row("100003","B")),"Teacher");
    }
    @AfterEach void closeContext() { if(context!=null)context.close(); }
    Map<String,String> row(String sid,String cls) { return Map.of("sid",sid,"name",sid,"cls",cls,"major","Civil"); }
    Map<String,Object> body(List<List<String>> groups) { return Map.of("expId",ExperimentCatalog.COMBINED,"requiredTrials",List.of("STEEL_TENS"),"studentGroups",groups,"timeText",java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))); }
    @SuppressWarnings("unchecked") String assign(List<List<String>> groups) { return tx.execute(s -> (String)((Map<String,Object>)assignments.assign(body(groups),"Teacher").get("task")).get("id")); }

    @Test void springManagedDispatchOpensPastLessonAndPreservesTeacherInstructions() {
        assertTrue(AopUtils.isCglibProxy(runs));assertTrue(AopUtils.isCglibProxy(assignments));
        var request=new LinkedHashMap<>(body(List.of(List.of("100001","100002"))));
        request.put("timeText",java.time.Instant.now().minusSeconds(3600).toString());
        request.put("note","保留原始数据");request.put("tip","核对试件编号\n采集断口图像");
        var result=assignments.assign(request,"Teacher");
        var task=(Map<?,?>)result.get("task");
        assertEquals("active",task.get("lessonState"));
        assertEquals("保留原始数据",store.getStudent("100001").get("note"));
        assertEquals("核对试件编号\n采集断口图像",store.getStudent("100001").get("tip"));
        var member=new RunService.Actor(false,"100001");
        var summary=runs.runs(member).get(0);
        assertEquals("active",summary.get("lessonState"));assertEquals(true,runs.detail((String)summary.get("id"),member).get("classOpen"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class));
    }
    @Test void springManagedDispatchWithoutTimeStillWaitsForTeacher() {
        var request=new LinkedHashMap<>(body(List.of(List.of("100001"))));request.put("timeText","");
        assertEquals("scheduled",((Map<?,?>)assignments.assign(request,"Teacher").get("task")).get("lessonState"));
    }
    @Test void importedAccountLogsInBeforeAnyAssignmentAndOldTemplatePreservesMajor() {
        var login=store.loginWithPassword("100001","100001");
        assertEquals("student",login.get("role"));
        var student=store.getStudent("100001");assertNull(student.get("taskId"));assertEquals("none",student.get("status"));assertNull(student.get("groupId"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class));
        store.importRoster(List.of(Map.of("sid","100001","name","Updated","cls","A")),"Teacher");
        assertEquals("Civil",store.getStudent("100001").get("major"));
        assertEquals(3,store.importedStudentIds("Teacher").size());
    }
    @Test void selectedStudentsOnlyReceiveScheduledTaskAndClassmatesStayUnassigned() {
        String task=assign(List.of(List.of("100001"),List.of("100003")));
        assertEquals(task,store.getStudent("100001").get("taskId"));assertEquals(task,store.getStudent("100003").get("taskId"));
        assertNull(store.getStudent("100002").get("taskId"));assertNull(store.getStudent("100002").get("groupId"));
        assertNotEquals(store.getStudent("100001").get("groupId"),store.getStudent("100003").get("groupId"));
        var run=runs.runs(new RunService.Actor(false,"100001")).get(0);
        assertEquals("scheduled",run.get("lessonState"));
        var detail=runs.detail((String)run.get("id"),new RunService.Actor(false,"100001"));assertEquals(false,detail.get("classOpen"));
        assertEquals(false,detail.get("teacherScoreConfirmationRequired"));
        assertEquals(2,((List<?>)store.listTasks().get(0).get("students")).size());
    }
    @Test void confirmationPolicyPersistsPerTaskAndStartsWithoutChangingIt() {
        var request=new LinkedHashMap<>(body(List.of(List.of("100001"))));
        request.put("teacherScoreConfirmationRequired",true);
        var result=tx.execute(s->assignments.assign(request,"Teacher"));
        var task=(Map<?,?>)result.get("task");String taskId=(String)task.get("id");
        assertEquals(true,task.get("teacherScoreConfirmationRequired"));
        assertEquals(true,store.listTasks().get(0).get("teacherScoreConfirmationRequired"));
        var student=new RunService.Actor(false,"100001");
        String runId=(String)runs.runs(student).get(0).get("id");
        assertEquals(true,runs.detail(runId,student).get("teacherScoreConfirmationRequired"));
        assertEquals(false,runs.classroom(runId,student).get("scoreReady"));
        tx.execute(s->runs.startTask(taskId,new RunService.Actor(true,"Teacher")));
        assertEquals(true,runs.detail(runId,student).get("classOpen"));
        assertEquals(true,runs.detail(runId,student).get("teacherScoreConfirmationRequired"));
        assign(List.of(List.of("100002")));
        assertEquals(true,runs.detail(runId,student).get("teacherScoreConfirmationRequired"));
        assertTrue(store.listTasks().stream().anyMatch(t->Boolean.FALSE.equals(t.get("teacherScoreConfirmationRequired"))));
    }
    @Test void invalidConfirmationPolicyCannotCreateTasksOrGroups() {
        var request=new LinkedHashMap<>(body(List.of(List.of("100001"))));
        request.put("teacherScoreConfirmationRequired","false");
        assertEquals(400,assertThrows(ApiException.class,()->tx.execute(s->assignments.assign(request,"Teacher"))).getStatus());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tasks",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM lab_groups",Integer.class));
    }
    @Test void reassigningStudentPreservesHistoricalMembershipAndOtherStudents() {
        String previous=assign(List.of(List.of("100001","100002")));
        String next=assign(List.of(List.of("100001","100003")));
        runs.provision(previous);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM run_members m JOIN experiment_runs r ON r.id=m.run_id WHERE r.task_id=?",Integer.class,previous));
        assertEquals(previous,store.getStudent("100002").get("taskId"));
        assertEquals(next,store.getStudent("100001").get("taskId"));
        assertEquals(2,runs.runs(new RunService.Actor(false,"100001")).size());
        var history = store.listTasks().stream().filter(task -> previous.equals(task.get("id"))).findFirst().orElseThrow();
        var groups = (List<?>) history.get("groups");
        assertEquals(1, groups.size());
        var group = (Map<?, ?>) groups.get(0);
        assertEquals("第1组", group.get("name"));
        var members = (List<?>) group.get("members");
        assertEquals(List.of("100001", "100002"), members.stream().map(member -> ((Map<?, ?>) member).get("sid")).toList());
    }
    @Test void duplicateAndOtherTeachersStudentsRejectedWithoutCreatingGroups() {
        assertEquals(400,assertThrows(ApiException.class,()->assign(List.of(List.of("100001"),List.of("100001")))).getStatus());
        assertEquals(403,assertThrows(ApiException.class,()->tx.execute(s->assignments.assign(body(List.of(List.of("100001"))),"Other"))).getStatus());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM lab_groups",Integer.class));
        assertNull(store.getStudent("100001").get("groupId"));
    }
    @Test void failedTaskCreationRollsBackNewGroupsAndStudentAssignment() {
        db.execute("ALTER TABLE tasks ADD CONSTRAINT reject_tasks CHECK (teacher <> 'Teacher')");
        assertThrows(Exception.class,()->assign(List.of(List.of("100001"))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM lab_groups",Integer.class));
        assertNull(store.getStudent("100001").get("groupId"));
    }
}
