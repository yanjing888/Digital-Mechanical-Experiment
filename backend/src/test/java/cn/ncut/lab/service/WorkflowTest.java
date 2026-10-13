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
import java.nio.file.Files;
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
        @Bean WanceDataService wance() throws IOException {
            var dir=Files.createTempDirectory("workflow-wance-");
            var file=dir.resolve("2024-04-25-09-24-49.mdb");
            dir.toFile().deleteOnExit();file.toFile().deleteOnExit();
            try(var database=com.healthmarketscience.jackcess.DatabaseBuilder.create(
                    com.healthmarketscience.jackcess.Database.FileFormat.V2000,file.toFile())) {
                var table=new com.healthmarketscience.jackcess.TableBuilder("OriginalData")
                        .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("ID",com.healthmarketscience.jackcess.DataType.LONG))
                        .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("TestNo",com.healthmarketscience.jackcess.DataType.LONG))
                        .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("PlayTime",com.healthmarketscience.jackcess.DataType.DOUBLE))
                        .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("LoadValue",com.healthmarketscience.jackcess.DataType.DOUBLE))
                        .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("PositionValue",com.healthmarketscience.jackcess.DataType.DOUBLE))
                        .toTable(database);
                table.addRow(1,1,0.0,0.0,0.0);
                table.addRow(2,1,1.0,2000.0,0.5);
                table.addRow(3,1,2.0,3000.0,1.0);
            }
            return new WanceDataService(dir.toString(),"");
        }
        @Bean RunService runs(JdbcTemplate db,WanceDataService wance){return new RunService(db,wance);}
        @Bean ClassroomService classroom(RunService r){return new ClassroomService(r);}
        @Bean ReportDeskService desk(RunService r,TeachingService t){return new ReportDeskService(r,t);}
        @Bean ReportParser parser(){return new ReportParser();}
        @Bean TeachingService teaching(RunService r,ReportParser p){return new TeachingService(r,p);}
        @Bean ReviewService review(RunService r,TeachingService t){return new ReviewService(r,t,new DifyService(),new FractureService());}
    }
    @Autowired ClassroomService classroom;@Autowired ReportDeskService desk;@Autowired RunService runs;@Autowired TeachingService teaching;@Autowired ReviewService review;@Autowired JdbcTemplate db;
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
        db.execute("ALTER TABLE students ADD cls VARCHAR(64) DEFAULT '土木一班'");
        runs.provision("task");runId=str(runs.runs(student).get(0).get("id"));
    }
    @Test void scoreConfirmationBelongsToEachStudentAndExpiresAfterCorrection() throws Exception {
        archive();configure();
        assertEquals(409,assertThrows(ApiException.class,()->classroom.confirm(runId,student,Map.of("scoreVersion",0))).getStatus());
        teaching.control(runId,teacher,body("action","close"));
        classroom.confirm(runId,student,Map.of("scoreVersion",0));
        assertEquals(true,runs.detail(runId,student).get("myScoreConfirmed"));
        assertEquals(false,runs.detail(runId,mate).get("myScoreConfirmed"));
        classroom.confirm(runId,mate,Map.of("scoreVersion",0,"action","dispute","reason","扣分项目有误"));
        assertEquals("disputed",map(runs.detail(runId,mate).get("myConfirmation")).get("status"));
        assertFalse(runs.detail(runId,teacher).containsKey("confirmationMembers"));
        teaching.control(runId,teacher,body("action","open"));
        var deductionBody=body("items",List.of("door"));deductionBody.put("reason","操作核查");teaching.deduction(runId,teacher,deductionBody);
        teaching.control(runId,teacher,body("action","close"));
        assertEquals(false,runs.detail(runId,student).get("myScoreConfirmed"));
        assertThrows(ApiException.class,()->classroom.confirm(runId,student,Map.of("scoreVersion",0)));
        classroom.confirm(runId,student,Map.of("scoreVersion",runs.detail(runId,student).get("scoreVersion")));
        assertEquals(85.0,map(runs.detail(runId,student).get("myConfirmation")).get("score"));
    }
    @Test void personalReportSubmissionWaitsForTeacherAndStudentScoreConfirmation() throws Exception {
        archive();
        assertEquals(false,runs.detail(runId,student).get("myScoreConfirmed"));
        uploadReport(student);
        assertEquals(409,assertThrows(ApiException.class,()->teaching.submit(runId,student,Map.of("requestId","before-teacher"))).getStatus());
        teaching.control(runId,teacher,body("action","close"));
        assertEquals(409,assertThrows(ApiException.class,()->teaching.submit(runId,student,Map.of("requestId","before-student"))).getStatus());
        classroom.confirm(runId,student,Map.of("scoreVersion",runs.detail(runId,student).get("scoreVersion")));
        teaching.submit(runId,student,Map.of("requestId","report-before-score-confirmation"));
        var detail=runs.detail(runId,student);
        assertEquals(1,list(detail.get("reports")).size());
        assertEquals(true,detail.get("myScoreConfirmed"));
    }
    @Test void gradebookListsAllExperimentsAndPublishedStudentScores() throws Exception {
        archive();submit(student,"gradebook-report");
        var report=list(runs.detail(runId,teacher).get("reports")).get(0);
        var gradeBody=body("action","grade");gradeBody.put("score",91);gradeBody.put("comment","报告完整");
        teaching.review(runId,str(report.get("id")),teacher,gradeBody);
        var gradebook=desk.gradebook(teacher);
        assertEquals(3,list(gradebook.get("experiments")).size());
        var row=list(gradebook.get("students")).stream().filter(s->"s1".equals(s.get("sid"))).findFirst().orElseThrow();
        var score=map(map(row.get("scores")).get(ExperimentCatalog.COMBINED));
        assertEquals(100.0,score.get("operationScore"));assertEquals(91.0,score.get("reportScore"));assertEquals("已完成",score.get("status"));
        assertThrows(ApiException.class,()->desk.gradebook(student));
    }
    @Test void completedGroupAppearsInTeacherQueueBeforePublishedScoreDecision() throws Exception {
        configure();
        var beforeArchiveBody=body("items",List.of("door"));beforeArchiveBody.put("reason","未按规范操作");
        teaching.deduction(runId,teacher,beforeArchiveBody);
        assertEquals(85.0,runs.detail(runId,student).get("operationScore"));
        archive();
        var finalRecord=runs.detail(runId,student);
        assertEquals(85.0,finalRecord.get("operationScore"));assertEquals(1,list(finalRecord.get("deductions")).size());
        var summary=runs.runs(teacher).stream().filter(item->runId.equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals(true,summary.get("collectionComplete"));assertEquals(1,summary.get("completedTrials"));assertEquals(1,summary.get("trialTotal"));
        teaching.control(runId,teacher,body("action","close"));
        assertEquals(85.0,runs.detail(runId,student).get("operationScore"));
        classroom.confirm(runId,student,Map.of("scoreVersion",runs.detail(runId,student).get("scoreVersion")));
        assertEquals(409,assertThrows(ApiException.class,()->review.requestRetake(runId,student,Map.of("reason","申请重新操作"))).getStatus());
        review.requestRetake(runId,mate,Map.of("reason","操作结果异常，申请重做"));
        assertEquals("pending",map(runs.detail(runId,teacher).get("retakeRequest")).get("status"));
        assertEquals(409,assertThrows(ApiException.class,()->classroom.confirm(runId,mate,Map.of("scoreVersion",runs.detail(runId,mate).get("scoreVersion")))).getStatus());
    }
    @Test void selectedTrialsAndMeasuredGeometrySurviveReload() throws Exception {
        var original=runs.load(runId,student,false);original.put("expId",ExperimentCatalog.COMBINED);original.put("requiredTrials",List.of("STEEL_TENS"));
        db.update("UPDATE experiment_runs SET payload=? WHERE id=?",JsonUtil.write(original),runId);
        assertEquals(1,list(runs.detail(runId,student).get("trialItems")).size());
        assertThrows(ApiException.class,()->runs.update(runId,student,body("geometry",Map.of("area",0,"gaugeLength",50))));
        runs.update(runId,student,body("geometry",Map.of("area",80,"gaugeLength",50)));
        archive();
        assertEquals(80.0,map(runs.detail(runId,student).get("geometry")).get("area"));
        assertEquals(true,runs.detail(runId,student).get("archived"));
        assertThrows(ApiException.class,()->runs.validateTrials(List.of()));
    }
    @Test void automaticAcquisitionReadsLatestFileAndRejectsReuse() {
        var original=runs.load(runId,student,false);original.put("expId",ExperimentCatalog.COMBINED);
        db.update("UPDATE experiment_runs SET payload=? WHERE id=?",JsonUtil.write(original),runId);
        var first=runs.importWanceData(runId,student);
        assertEquals("2024-04-25-09-24-49.mdb",map(first.get("data")).get("fileName"));
        assertEquals("device_file",map(first.get("data")).get("sourceMode"));
        try {
            var request=new org.springframework.mock.web.MockHttpServletRequest();request.setParameter("trial","CAST_TENS");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
            assertEquals(409,assertThrows(ApiException.class,()->runs.importWanceData(runId,student)).getStatus());
        } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
        String other=str(runs.runs(outsider).get(0).get("id"));
        assertEquals(409,assertThrows(ApiException.class,()->runs.importWanceData(other,outsider)).getStatus());
    }
    @Test void dataFileCannotBeReusedByAnotherGroup() {
        runs.importData(runId,student,csv(),2,1,0,"N");
        String other=str(runs.runs(outsider).get(0).get("id"));
        assertEquals(409,assertThrows(ApiException.class,()->runs.importData(other,outsider,csv(),2,1,0,"N")).getStatus());
    }
    @Test void reportDeskIncludesWholeTaskAndLocatesOriginalText() throws Exception {
        archive();submit(student,"desk-1");submit(mate,"desk-2");
        var roster=desk.roster("task",teacher);assertEquals(3,roster.size());
        var reports=list(runs.detail(runId,teacher).get("reports"));String left=str(reports.get(0).get("id")),right=str(reports.get(1).get("id"));
        desk.begin(left,teacher);assertEquals("reviewing",list(runs.detail(runId,teacher).get("reports")).get(0).get("reviewStatus"));
        var comparison=desk.compare(left,right,teacher);assertEquals(true,comparison.get("available"));
        assertEquals(true,comparison.get("sameGroup"));
        assertFalse(list(comparison.get("segments")).isEmpty());
        var segment=list(comparison.get("segments")).get(0);
        assertEquals(segment.get("text"),str(comparison.get("leftText")).substring((Integer)segment.get("leftStart"),(Integer)segment.get("leftEnd")));
        var check=map(desk.check(left,teacher));assertEquals(true,map(check.get("dataConsistency")).get("sameGroupData"));
        assertTrue(check.containsKey("writingAssessment"));assertTrue(check.containsKey("decision"));
        review.ai(runId,left,teacher);
        var aiReport=list(runs.detail(runId,teacher).get("reports")).stream().filter(x->left.equals(x.get("id"))).findFirst().orElseThrow();
        assertEquals("local-fallback",map(aiReport.get("ai")).get("source"));
        assertEquals(runId,map(aiReport.get("runSnapshot")).get("runId"));
        assertEquals("g1",map(aiReport.get("runSnapshot")).get("groupId"));
        var b=body("action","grade");b.put("score",88);b.put("comment","计算过程完整");teaching.review(runId,left,teacher,b);
        desk.check(left,teacher);assertEquals(88.0,list(runs.detail(runId,teacher).get("reports")).get(0).get("finalScore"));
        assertTrue(desk.gradedReport(left,teacher).length>0);
        assertThrows(ApiException.class,()->desk.roster("task",student));
    }
    @Test void studentHomeFollowsWorkflowInsteadOfLegacyStatus() throws Exception {
        Map<String,Object> studentInfo=new LinkedHashMap<>(Map.of("sid","s1","taskId","task","status","assigned"));
        Map<String,Object> session=new LinkedHashMap<>();session.put("student",studentInfo);
        archive();runs.enrichStudentSession(session);assertEquals("lab_done",studentInfo.get("status"));
        submit(student,"home-report");runs.enrichStudentSession(session);assertEquals("submitted",studentInfo.get("status"));
        assertEquals(2,list(session.get("mates")).size());
        var report=list(runs.detail(runId,student).get("reports")).get(0);
        assertEquals(100.0,map(report.get("runSnapshot")).get("operationScore"));
        assertEquals("confirmed",map(map(report.get("runSnapshot")).get("scoreConfirmation")).get("status"));
        var b=body("action","return");b.put("comment","补充分析");teaching.review(runId,str(report.get("id")),teacher,b);
        runs.enrichStudentSession(session);assertEquals("lab_done",studentInfo.get("status"));
    }
    @Test void newTaskUsesSelectedTrialsAndPreservesPreviousLesson() {
        db.update("INSERT INTO tasks VALUES('next-task',?,'拉伸压缩实验','第二节')",ExperimentCatalog.COMBINED);
        db.update("INSERT INTO task_groups VALUES('next-task','g1')");
        db.update("UPDATE students SET task_id='next-task' WHERE group_id='g1'");
        runs.configureTask("next-task",runs.validateTrials(List.of("CAST_TENS")));
        runs.provision("next-task");
        assertEquals(2,runs.runs(student).size());
        String nextId=RunService.runIdFor("next-task","g1");
        assertEquals(List.of("CAST_TENS"),runs.detail(nextId,student).get("requiredTrials"));
        assertEquals("task",runs.detail(runId,student).get("taskId"));
    }
    @Test void dispatchHistoryKeepsOriginalStudentsAfterRegrouping() {
        db.execute("ALTER TABLE tasks ADD created_at VARCHAR(40)");
        db.update("UPDATE students SET group_id='new-group',task_id='new-task' WHERE sid='s1'");
        db.update("INSERT INTO experiment_runs(id,task_id,group_id,parent_id,payload,created_at) VALUES('retake','task','g1',?,'{}','2026-09-28')",runId);
        db.update("INSERT INTO run_members(run_id,sid,name) VALUES('retake','extra','重做新增学生')");
        var task = new StoreService(db).listTasks().get(0);
        @SuppressWarnings("unchecked") var members = (List<Map<String,Object>>) task.get("students");
        assertEquals(List.of("s1","s2","s3"), members.stream().map(m -> m.get("sid")).toList());
        assertEquals("学生甲", members.get(0).get("name"));
    }

    Map<String,Object> body(String key,Object value) {var b=new LinkedHashMap<String,Object>();b.put("revision",runs.detail(runId,teacher).get("revision"));b.put(key,value);return b;}
    MockMultipartFile csv() {return new MockMultipartFile("file","data.csv","text/csv","位移,力\n0,0\n1,1000\n2,2000".getBytes(StandardCharsets.UTF_8));}
    MockMultipartFile photo() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),"png",out);return new MockMultipartFile("file","fracture.png","image/png",out.toByteArray());}
    void archive() throws Exception {
        runs.photo(runId,student,photo(),"正面");runs.importData(runId,student,csv(),2,1,0,"N");runs.archive(runId,student,body("unused",0));
    }
    void configure() {var s=new LinkedHashMap<>(teaching.settings());s.put("catalogConfirmed",true);s.put("catalog",List.of(Map.of("id","door","label","开门","points",15),Map.of("id","order","label","纪律","points",10)));teaching.settings(teacher,s);}
    void submit(Actor a,String key) throws Exception {
        if(Boolean.TRUE.equals(runs.detail(runId,a).get("classOpen"))) teaching.control(runId,teacher,body("action","close"));
        classroom.confirm(runId,a,Map.of("scoreVersion",runs.detail(runId,a).get("scoreVersion")));
        uploadReport(a);
        teaching.submit(runId,a,Map.of("requestId",key));
    }
    void uploadReport(Actor a) throws Exception {
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("实验分析与结论："+"根据实验数据分析材料受力过程并结合试件断口的宏观特征说明实验观察与结果。".repeat(8));doc.write(out);
            teaching.uploadReport(runId,a,new MockMultipartFile("file","报告.docx","application/octet-stream",out.toByteArray()));
        }
    }
    @Test void photoBeforeDataAndArchiveGuards() throws Exception {
        runs.photo(runId,student,photo(),"正面");assertEquals(1,list(runs.detail(runId,mate).get("photos")).size());
        assertThrows(ApiException.class,()->runs.archive(runId,student,body("unused",0)));
        assertEquals("pending",runs.runs(outsider).get(0).get("status"));
        archive();var r=runs.detail(runId,student);assertEquals(2.0,map(r.get("data")).get("maxF"));assertTrue((Boolean)r.get("archived"));
        assertThrows(ApiException.class,()->runs.importData(runId,student,csv(),2,1,0,"N"));
    }
    @Test void combinedTaskKeepsFourTrialsSeparateAndSubmitsOneReport() throws Exception {
        Map<String,Object> original=runs.load(runId,student,false);
        original.put("expId",ExperimentCatalog.COMBINED);original.put("expName","拉伸压缩实验");
        db.update("UPDATE experiment_runs SET payload=? WHERE id=?",JsonUtil.write(original),runId);
        try {
            for(String trial:ExperimentCatalog.TRIAL_IDS) {
                var request=new org.springframework.mock.web.MockHttpServletRequest();request.setParameter("trial",trial);
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
                assertNull(runs.detail(runId,student).get("data"));
                runs.importData(runId,student,csv(),2,1,0,"N");runs.photo(runId,student,photo(),"正面");
                runs.update(runId,student,Map.of("revision",runs.detail(runId,student).get("revision"),"specimenId",trial));
                if(!trial.equals(ExperimentCatalog.TRIAL_IDS.get(3)))assertThrows(ApiException.class,()->runs.archive(runId,student,body("unused",0)));
            }
            var detail=runs.detail(runId,student);
            assertEquals(4,list(detail.get("trialItems")).size());
            for(var item:list(detail.get("trialItems")))assertEquals(item.get("id"),item.get("specimenId"));
            assertTrue(list(detail.get("trialItems")).stream().noneMatch(item->item.containsKey("operationScore")));
            assertEquals(4,list(detail.get("trialItems")).stream().map(t->map(t.get("data")).get("fileId")).distinct().count());
            runs.archive(runId,student,body("unused",0));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM run_archives WHERE run_id=?",Integer.class,runId));
            assertEquals(100.0,runs.detail(runId,student).get("operationScore"));
            assertThrows(ApiException.class,()->runs.archive(runId,student,body("unused",0)));
            submit(student,"combined-report");
            var reports=list(runs.detail(runId,student).get("reports"));assertEquals(1,reports.size());
            assertEquals(4,map(map(reports.get(0).get("runSnapshot")).get("trials")).size());
            assertThrows(ApiException.class,()->runs.importData(runId,student,csv(),2,1,0,"N"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void foreignRunAndReportFilesAreProtected() throws Exception {
        assertEquals(403,assertThrows(ApiException.class,()->runs.detail(runId,outsider)).getStatus());
        archive();submit(student,"request-1");var r=runs.detail(runId,student);String fid=str(list(r.get("reports")).get(0).get("fileId"));
        assertEquals(403,assertThrows(ApiException.class,()->runs.getFile(fid,mate)).getStatus());assertEquals(0,list(runs.detail(runId,mate).get("reports")).size());
        assertNotNull(runs.getFile(fid,teacher).get("contents"));
    }
    @Test void classroomScoreAlwaysFollowsRecordedDeductions() throws Exception {
        archive();configure();var b=body("items",List.of("door"));b.put("reason","实验运行时尝试打开保护罩");teaching.deduction(runId,teacher,b);
        assertEquals(85.0,runs.detail(runId,student).get("operationScore"));
        b=body("action","grade");b.put("score",92);b.put("comment","复核后调整");b.put("reason","现场复核");teaching.control(runId,teacher,b);
        b=body("items",List.of("order"));b.put("reason","打闹");teaching.deduction(runId,teacher,b);
        var r=runs.detail(runId,student);assertEquals(75.0,r.get("operationScore"));assertEquals(75.0,r.get("calculatedScore"));
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
    @Test void retakeRosterIncludesClassmatesOutsideTaskAndRejectsOtherClasses() {
        db.update("INSERT INTO students(sid,name,task_id,group_id,cls) VALUES('s4','同班学生','other-task','g2','土木一班'),('s5','其他班学生','task','g2','土木二班')");
        var roster=review.retakeStudents(runId,teacher);
        assertTrue(roster.stream().anyMatch(s->"s4".equals(s.get("sid"))));
        assertFalse(roster.stream().anyMatch(s->"s5".equals(s.get("sid"))));
        assertEquals(403,assertThrows(ApiException.class,()->review.retakeStudents(runId,student)).getStatus());
        var b=body("name","同班临时组");b.put("reason","补做");b.put("members",List.of("s5"));
        assertEquals(400,assertThrows(ApiException.class,()->review.createRetake(runId,teacher,b)).getStatus());
        b.put("members",List.of("s1","s4"));
        var child=(Map<String,Object>)review.createRetake(runId,teacher,b);
        assertEquals(2,list(child.get("members")).size());
        assertEquals("other-task",db.queryForObject("SELECT task_id FROM students WHERE sid='s4'",String.class));
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
