package cn.ncut.lab.service;

import cn.ncut.lab.config.WorkflowSchema;
import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.ncut.lab.service.RunService.*;

@SpringJUnitConfig(WorkflowTest.Config.class)
class WorkflowTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate db(DataSource d){return new JdbcTemplate(d);}
        @Bean DataSourceTransactionManager transactionManager(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean RunService runs(JdbcTemplate db){return new RunService(db);}
        @Bean ReportParser parser(){return new ReportParser();}
        @Bean TeachingService teaching(RunService r,ReportParser p){return new TeachingService(r,p);}
        @Bean ReviewService review(RunService r,TeachingService t){return new ReviewService(r,t,new DifyService(),new FractureService());}
    }
    @Autowired RunService runs;@Autowired TeachingService teaching;@Autowired ReviewService review;@Autowired JdbcTemplate db;
    final Actor teacher=new Actor(true,"教师"),student=new Actor(false,"s1"),mate=new Actor(false,"s2"),outsider=new Actor(false,"s3");
    String runId;
    @BeforeEach void setup() {
        db.execute("DROP ALL OBJECTS");WorkflowSchema.create(db);
        db.execute("CREATE TABLE tasks(id VARCHAR(64),exp_id VARCHAR(32),exp_name VARCHAR(64),time_text VARCHAR(128))");
        db.execute("CREATE TABLE students(sid VARCHAR(32),name VARCHAR(64),task_id VARCHAR(64),group_id VARCHAR(32),personal_json LONGTEXT,personal_submitted_at VARCHAR(64),personal_score DOUBLE,personal_comment LONGTEXT)");
        db.execute("CREATE TABLE task_groups(task_id VARCHAR(64),group_id VARCHAR(32))");
        db.execute("CREATE TABLE lab_groups(id VARCHAR(32),name VARCHAR(64))");
        db.execute("CREATE TABLE group_reports(task_id VARCHAR(64),group_id VARCHAR(32),data_json LONGTEXT,score DOUBLE,comment LONGTEXT,deductions_json LONGTEXT)");
        db.update("INSERT INTO tasks VALUES('task','TENS','材料拉伸','第一节')");
        db.update("INSERT INTO lab_groups VALUES('g1','第一组'),('g2','第二组')");
        db.update("INSERT INTO task_groups VALUES('task','g1'),('task','g2')");
        db.update("INSERT INTO students(sid,name,task_id,group_id) VALUES('s1','学生甲','task','g1'),('s2','学生乙','task','g1'),('s3','学生丙','task','g2')");
        runs.provision("task");runId=str(runs.runs(student).get(0).get("id"));
    }
    Map<String,Object> body(String key,Object value) {var b=new LinkedHashMap<String,Object>();b.put("revision",runs.detail(runId,teacher).get("revision"));b.put(key,value);return b;}
    MockMultipartFile csv() {return new MockMultipartFile("file","data.csv","text/csv","位移,力\n0,0\n1,1000\n2,2000".getBytes(StandardCharsets.UTF_8));}
    MockMultipartFile photo() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),"png",out);return new MockMultipartFile("file","fracture.png","image/png",out.toByteArray());}
    void archive() throws Exception {
        var b=body("specimenId","T001");b.put("deviceId","机01");b.put("experimentAt","2026-09-15T02:30:00Z");runs.update(runId,student,b);
        runs.photo(runId,student,photo(),"正面");runs.importData(runId,student,csv(),2,1,0,"N");runs.archive(runId,student,body("unused",0));
    }
    void configure() {var s=new LinkedHashMap<>(teaching.settings());s.put("catalogConfirmed",true);s.put("catalog",List.of(Map.of("id","door","label","开门","points",15),Map.of("id","order","label","纪律","points",10)));teaching.settings(teacher,s);}
    void submit(Actor a,String key) throws Exception {
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("实验分析与结论："+"根据实验数据分析材料受力过程并结合试件断口的宏观特征说明实验观察与结果。".repeat(8));doc.write(out);
            teaching.uploadReport(runId,a,new MockMultipartFile("file","报告.docx","application/octet-stream",out.toByteArray()));
        }
        teaching.observation(runId,a,Map.of("features","试件局部变细，需结合侧面照片核实","judgment","uncertain"));
        teaching.submit(runId,a,Map.of("requestId",key));
    }
    @Test void photoBeforeDataAndArchiveGuards() throws Exception {
        runs.photo(runId,student,photo(),"正面");assertEquals(1,list(runs.detail(runId,mate).get("photos")).size());
        assertThrows(ApiException.class,()->runs.archive(runId,student,body("unused",0)));
        assertEquals("pending",runs.runs(outsider).get(0).get("status"));
        archive();var r=runs.detail(runId,student);assertEquals(2.0,map(r.get("data")).get("maxF"));assertTrue((Boolean)r.get("archived"));
        assertThrows(ApiException.class,()->runs.importData(runId,student,csv(),2,1,0,"N"));
    }
    @Test void foreignRunAndReportFilesAreProtected() throws Exception {
        assertEquals(403,assertThrows(ApiException.class,()->runs.detail(runId,outsider)).getStatus());
        archive();submit(student,"request-1");var r=runs.detail(runId,student);String fid=str(list(r.get("reports")).get(0).get("fileId"));
        assertEquals(403,assertThrows(ApiException.class,()->runs.getFile(fid,mate)).getStatus());assertEquals(0,list(runs.detail(runId,mate).get("reports")).size());
        assertNotNull(runs.getFile(fid,teacher).get("contents"));
    }
    @Test void classroomScoreOverrideAndAuditSurviveReload() {
        configure();var b=body("items",List.of("door"));b.put("reason","实验运行时尝试打开保护罩");teaching.deduction(runId,teacher,b);
        assertEquals(85.0,runs.detail(runId,student).get("operationScore"));
        b=body("action","grade");b.put("score",92);b.put("comment","复核后调整");b.put("reason","现场复核");teaching.control(runId,teacher,b);
        b=body("items",List.of("order"));b.put("reason","打闹");teaching.deduction(runId,teacher,b);
        var r=runs.detail(runId,student);assertEquals(92.0,r.get("operationScore"));assertEquals(75.0,r.get("calculatedScore"));
        b=body("action","close");b.put("reason","本节课结束");teaching.control(runId,teacher,b);
        var close=body("items",List.of("door"));close.put("reason","新扣分");assertThrows(ApiException.class,()->teaching.deduction(runId,teacher,close));
        assertFalse(runs.detail(runId,student).containsKey("audit"));assertTrue(list(runs.detail(runId,teacher).get("audit")).size()>=4);
    }
    @Test void invalidBatchIsAtomicAndStaleWritesRejected() {
        configure();var bad=body("items",List.of("door","missing"));bad.put("reason","操作");assertThrows(ApiException.class,()->teaching.deduction(runId,teacher,bad));
        assertTrue(list(runs.detail(runId,teacher).get("deductions")).isEmpty());
        var b=body("specimenId","A");runs.update(runId,student,b);b.put("specimenId","B");assertEquals(409,assertThrows(ApiException.class,()->runs.update(runId,mate,b)).getStatus());
    }
    @Test void duplicateSubmissionProducesOneReceiptAndReturnsCreateNewVersions() throws Exception {
        archive();submit(student,"same-request");teaching.submit(runId,student,Map.of("requestId","same-request"));
        var reports=list(runs.detail(runId,student).get("reports"));assertEquals(1,reports.size());assertNotNull(reports.get(0).get("sha256"));
        var b=body("action","return");b.put("comment","补充分析");teaching.review(runId,str(reports.get(0).get("id")),teacher,b);submit(student,"next-request");
        reports=list(runs.detail(runId,student).get("reports"));assertEquals(2,reports.size());assertEquals(1,reports.get(0).get("version"));assertEquals(2,reports.get(1).get("version"));
    }
    @Test void retakesPreserveHistoryAndDoNotRegroupStudents() throws Exception {
        archive();var b=body("name","临时组");b.put("reason","现场重新实验");b.put("members",List.of("s1","s3"));
        var child=(Map<String,Object>)review.createRetake(runId,teacher,b);String cid=str(child.get("id"));
        assertEquals(2,child.get("attempt"));assertEquals("g1",db.queryForObject("SELECT group_id FROM students WHERE sid='s1'",String.class));
        assertTrue((Boolean)runs.detail(runId,teacher).get("archived"));assertTrue(list(child.get("photos")).isEmpty());assertEquals(403,assertThrows(ApiException.class,()->runs.detail(cid,mate)).getStatus());
        assertEquals(2,runs.runs(student).size());assertFalse(((List<?>)child.get("adoptedFor")).contains("s1"));
    }
    @Test void similarityFlagsOnlyLaterStudentAndDoesNotChangeScores() throws Exception {
        archive();submit(student,"one");Thread.sleep(5);submit(mate,"two");review.similarity(runId,teacher);
        var reports=list(runs.detail(runId,teacher).get("reports"));assertFalse((Boolean)map(reports.get(0).get("similarity")).get("flagged"));assertTrue((Boolean)map(reports.get(1).get("similarity")).get("flagged"));
        assertNull(reports.get(1).get("finalScore"));assertFalse(list(runs.detail(runId,mate).get("reports")).get(0).containsKey("similarity"));
    }
    @Test void studentsCannotReadTeacherReference() {
        var b=body("type","ductile");b.put("features","整体颈缩");review.reference(runId,teacher,b);
        assertFalse(runs.detail(runId,student).containsKey("reference"));assertTrue(runs.detail(runId,teacher).containsKey("reference"));
    }
}
