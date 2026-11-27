package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@SuppressWarnings("unchecked")
public class RunService {
    final JdbcTemplate db;
    final WanceDataService wance;
    private final TaskSchedule schedule;
    public RunService(JdbcTemplate db, WanceDataService wance) { this.db=db; this.wance=wance; this.schedule=new TaskSchedule(db); }
    public JdbcTemplate jdbc() { return db; }
    public boolean openTaskIfDue(String taskId) { return schedule.openIfDue(taskId); }
    public List<String> validateTrials(Object value) {
        if(value==null)return ExperimentCatalog.TRIAL_IDS;
        if(!(value instanceof List<?> ids)||ids.isEmpty())throw new ApiException(400,"请选择至少一个试验子项");
        List<String> selected=ids.stream().map(RunService::str).distinct().toList();
        if(!ExperimentCatalog.TRIAL_IDS.containsAll(selected))throw new ApiException(400,"试验子项无效");
        return selected;
    }
    public void configureTask(String taskId,List<String> trials) {
        db.update("INSERT INTO task_configs(task_id,payload) VALUES(?,?)",taskId,JsonUtil.write(Map.of("requiredTrials",trials)));
    }
    public void scheduleTask(String taskId,List<String> trials) {
        scheduleTask(taskId,trials,false);
    }
    public boolean validateTeacherScoreConfirmation(Object value) {
        if(value==null)return false;
        if(!(value instanceof Boolean required))throw new ApiException(400,"教师确认开关须为开启或关闭");
        return required;
    }
    public void scheduleTask(String taskId,List<String> trials,boolean teacherScoreConfirmationRequired) {
        db.update("INSERT INTO task_configs(task_id,payload) VALUES(?,?)",taskId,JsonUtil.write(Map.of("requiredTrials",trials,"lessonState","scheduled","teacherScoreConfirmationRequired",teacherScoreConfirmationRequired)));
    }
    @Transactional
    public Map<String,Object> startTask(String taskId,Actor actor) {
        teacher(actor);
        var tasks=db.queryForList("SELECT teacher FROM tasks WHERE id=?",taskId);
        if(tasks.isEmpty())throw new ApiException(404,"课次不存在");
        if(!actor.id().equals(str(tasks.get(0).get("teacher"))))throw new ApiException(403,"仅下发教师可开始本课次");
        provision(taskId);
        schedule.openManually(taskId,actor.id());
        return Map.of("ok",true);
    }
    private void initializeClassroom(Map<String,Object> r,Map<String,Object> task) {
        var rows=db.queryForList("SELECT payload FROM task_configs WHERE task_id=?",r.get("taskId"));
        Map<String,Object> config=rows.isEmpty()?Map.of():JsonUtil.readMap(str(rows.get(0).get("payload")),Map.of());
        r.put("requiredTrials",config.getOrDefault("requiredTrials",ExperimentCatalog.TRIAL_IDS));
        r.put("lessonState",config.getOrDefault("lessonState","active"));
        r.put("teacherScoreConfirmationRequired",ClassroomService.teacherConfirmationRequired(config));
        if("scheduled".equals(r.get("lessonState")))r.put("classOpen",false);
        r.put("place",str(task.get("place")));r.put("scoreVersion",0);r.put("confirmationRequired",true);
    }
    public record Actor(boolean teacher, String id) {}
    public void enrichStudentSession(Map<String,Object> snapshot) {
        Map<String,Object> student=map(snapshot.get("student"));
        if(student.isEmpty())return;
        Actor actor=new Actor(false,str(student.get("sid")));
        var current=runs(actor).stream().filter(r->Objects.equals(r.get("taskId"),student.get("taskId"))).findFirst();
        if(current.isEmpty())return;
        var r=detail(str(current.get().get("id")),actor);
        var reports=list(r.get("reports"));
        var latest=reports.stream().max(Comparator.comparingInt(p->((Number)p.get("version")).intValue())).orElse(Map.of());
        String state=!latest.isEmpty()&&!Boolean.TRUE.equals(latest.get("returned"))?"submitted":Boolean.TRUE.equals(r.get("archived"))?"lab_done":"pending".equals(r.get("status"))?"assigned":"in_lab";
        student.put("status",state);student.put("groupName",r.get("groupName"));student.put("workflowRunId",r.get("id"));student.put("requiredTrials",r.get("requiredTrials"));
        student.put("operationScore",r.get("operationScore"));student.put("personalScore",latest.get("finalScore"));student.put("personalComment",latest.get(Boolean.TRUE.equals(latest.get("returned"))?"returnReason":"finalComment"));
        student.put("lessonState",r.getOrDefault("lessonState","active"));
        student.put("scheduledStartAt",r.get("scheduledStartAt"));
        student.put("teacherScoreConfirmationRequired",r.get("teacherScoreConfirmationRequired"));
        for(String key:List.of("archived","classOpen","myScoreConfirmed","retakeRequest"))student.put(key,r.get(key));
        var trialItems=list(r.get("trialItems"));
        int trialTotal=trialItems.isEmpty()?1:trialItems.size();
        long completedTrials=trialItems.isEmpty()?(!map(r.get("data")).isEmpty()&&!list(r.get("photos")).isEmpty()?1:0)
                :trialItems.stream().filter(t->Boolean.TRUE.equals(t.get("dataReady"))&&Boolean.TRUE.equals(t.get("photoReady"))).count();
        student.put("trialTotal",trialTotal);student.put("completedTrials",completedTrials);student.put("collectionComplete",completedTrials==trialTotal);
        student.put("personalReportState",latest.isEmpty()?"none":Boolean.TRUE.equals(latest.get("returned"))?"returned":latest.get("finalScore")!=null?"graded":"submitted");
        snapshot.put("mates",r.get("members"));
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }
    static String str(Object v) { return v==null?"":v.toString().trim(); }
    static Map<String,Object> map(Object v) { return v instanceof Map ? (Map<String,Object>)v : new LinkedHashMap<>(); }
    static List<Map<String,Object>> list(Object v) { return v instanceof List ? (List<Map<String,Object>>)v : new ArrayList<>(); }
    static String required(Object v,String label) { String s=str(v); if(s.isBlank()) throw new ApiException(400,"请填写"+label); if(s.length()>2000) throw new ApiException(400,label+"过长"); return s; }
    static double score(Object value) {
        try { double n=Double.parseDouble(str(value)); if(Double.isFinite(n)&&n>=0&&n<=100)return n; } catch(Exception ignored) {}
        throw new ApiException(400,"分数必须在0至100之间");
    }
    static void teacher(Actor actor) { if(!actor.teacher()) throw new ApiException(403,"需要教师权限"); }
    public void authorize(String runId,Actor a) {
        if(!a.teacher() && db.queryForObject("SELECT COUNT(*) FROM run_members WHERE run_id=? AND sid=?",Integer.class,runId,a.id())==0) throw new ApiException(403,"仅可访问本人所属实验记录");
    }
    Map<String,Object> load(String runId,Actor a,boolean lock) {
        authorize(runId,a);
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM experiment_runs WHERE id=?",runId);
        if(rows.isEmpty()) throw new ApiException(404,"实验记录不存在");
        var initial=JsonUtil.readMap(str(rows.get(0).get("payload")),new LinkedHashMap<>());
        boolean opened="scheduled".equals(initial.get("lessonState"))&&schedule.openIfDue(str(initial.get("taskId")));
        if(lock||opened)rows=db.queryForList("SELECT * FROM experiment_runs WHERE id=? FOR UPDATE",runId);
        Map<String,Object> r=JsonUtil.readMap(str(rows.get(0).get("payload")),new LinkedHashMap<>());
        r.put("id",runId); r.put("revision",rows.get(0).get("revision"));
        r.put("scheduledStartAt",TaskSchedule.startTime(r.get("timeText")).map(Instant::toString).orElse(""));
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String trial = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request ? request.getRequest().getParameter("trial") : null;
        TrialRecords.project(r, trial);
        return r;
    }
    void save(Map<String,Object> run,Actor actor,String action) {
        List<Map<String,Object>> audit=list(run.get("audit"));
        audit.add(Map.of("at",now(),"by",actor.id(),"action",action)); run.put("audit",audit);
        int revision=((Number)run.getOrDefault("revision",0)).intValue();
        int updated=db.update("UPDATE experiment_runs SET payload=?,revision=revision+1 WHERE id=? AND revision=?",JsonUtil.write(TrialRecords.stored(run)),run.get("id"),revision);
        if(updated!=1) throw new ApiException(409,"记录已更新，请刷新后重试"); run.put("revision",revision+1);
    }
    void mutable(Map<String,Object> run) {
        if("scheduled".equals(run.get("lessonState")))throw new ApiException(409,str(run.get("scheduledStartAt")).isEmpty()?"本课次尚未开始，请等待教师开课":"尚未到开课时间，到时自动开放采集");
        if(Boolean.TRUE.equals(run.get("archived"))) throw new ApiException(409,"操作记录已提交；请由教师退回后修改");
        if(!Boolean.TRUE.equals(run.get("classOpen"))) throw new ApiException(409,"教师已确认扣分；如需修改实验数据，请先联系教师重新开放记录");
    }
    void checkRevision(Map<String,Object> run,Map<String,Object> body) {
        if(!str(run.get("revision")).equals(str(body.get("revision")))) throw new ApiException(409,"记录已更新，请刷新后重试，未保存的输入仍保留");
    }

    /** Snapshot assigned members before later regrouping. Existing legacy data is referenced separately. */
    @Transactional
    public void provision(String taskId) {
        List<Map<String,Object>> tasks=db.queryForList("SELECT * FROM tasks WHERE id=?",taskId);
        if(tasks.isEmpty())return;
        Map<String,Object> t=tasks.get(0);
        for(Map<String,Object> group:db.queryForList(
                "SELECT g.id,g.name FROM lab_groups g WHERE g.id IN (SELECT group_id FROM task_groups WHERE task_id=?) "
                        + "OR g.id IN (SELECT DISTINCT group_id FROM students WHERE task_id=? AND group_id IS NOT NULL AND group_id<>'')",
                taskId, taskId)) {
            String runId=runIdFor(taskId, str(group.get("id")));
            List<Map<String,Object>> members=db.queryForList("SELECT sid,name,cls FROM students WHERE task_id=? AND group_id=? ORDER BY sid",taskId,group.get("id"));
            if(db.queryForObject("SELECT COUNT(*) FROM experiment_runs WHERE id=?",Integer.class,runId)>0) {
                syncRunMembers(runId, members, taskId);
                continue;
            }
            if(members.isEmpty()) continue;
            Map<String,Object> r=new LinkedHashMap<>();
            r.put("id",runId); r.put("taskId",taskId); r.put("groupId",group.get("id")); r.put("groupName",group.get("name"));
            r.put("expName",t.get("exp_name")); r.put("expId",t.get("exp_id")); r.put("timeText",t.get("time_text"));
            r.put("attempt",1); r.put("members",members); r.put("createdAt",now()); r.put("status","pending"); r.put("classOpen",true);
            initializeClassroom(r,t);
            r.put("photos",new ArrayList<>()); r.put("deductions",new ArrayList<>()); r.put("audit",new ArrayList<>());
            // Preserve legacy evidence, but do not relabel legacy replay points as a new device import.
            List<Map<String,Object>> legacy=db.queryForList("SELECT data_json,score,comment,deductions_json FROM group_reports WHERE task_id=? AND group_id=?",taskId,group.get("id"));
            if(!legacy.isEmpty()) {
                Map<String,Object> old=legacy.get(0); Map<String,Object> data=JsonUtil.readMap(str(old.get("data_json")),new LinkedHashMap<>());
                r.put("legacyAvailable",!data.isEmpty()); r.put("deductions",JsonUtil.readListOfMap(str(old.get("deductions_json")),new ArrayList<>()));
                if(old.get("score")!=null) { r.put("finalScore",old.get("score")); r.put("finalComment",str(old.get("comment"))); }
            }
            List<Map<String,Object>> legacyPersonal=db.queryForList("SELECT sid,name,personal_json,personal_submitted_at,personal_score,personal_comment FROM students WHERE task_id=? AND group_id=?",taskId,group.get("id"));
            boolean personalEvidence=legacyPersonal.stream().anyMatch(p->!JsonUtil.readMap(str(p.get("personal_json")),Map.of()).isEmpty());
            if(Boolean.TRUE.equals(r.get("legacyAvailable"))||personalEvidence) {
                r.put("legacyAvailable",true);
                db.update("INSERT IGNORE INTO legacy_records(run_id,payload) VALUES(?,?)",runId,JsonUtil.write(Map.of("group",legacy,"personal",legacyPersonal,"capturedAt",now(),"note","升级前记录快照；旧曲线来源未经本次设备文件导入验证")));
            }
            db.update("INSERT IGNORE INTO experiment_runs(id,task_id,group_id,payload,revision,created_at) VALUES(?,?,?,?,0,?)",runId,taskId,group.get("id"),JsonUtil.write(r),r.get("createdAt"));
            syncRunMembers(runId, members, taskId);
        }
    }
    static String runIdFor(String taskId, String groupId) {
        return UUID.nameUUIDFromBytes((taskId+":"+groupId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    void createGroupRunIfMissing(String taskId, String groupId, List<Map<String,Object>> members) {
        if(members.isEmpty()) return;
        String runId=runIdFor(taskId, groupId);
        if(db.queryForObject("SELECT COUNT(*) FROM experiment_runs WHERE id=?",Integer.class,runId)>0) return;
        List<Map<String,Object>> tasks=db.queryForList("SELECT * FROM tasks WHERE id=?",taskId);
        List<Map<String,Object>> groups=db.queryForList("SELECT id,name FROM lab_groups WHERE id=?",groupId);
        if(tasks.isEmpty()||groups.isEmpty()) return;
        Map<String,Object> t=tasks.get(0), group=groups.get(0);
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("id",runId); r.put("taskId",taskId); r.put("groupId",groupId); r.put("groupName",group.get("name"));
        r.put("expName",t.get("exp_name")); r.put("expId",t.get("exp_id")); r.put("timeText",t.get("time_text"));
        r.put("attempt",1); r.put("members",members); r.put("createdAt",now()); r.put("status","pending"); r.put("classOpen",true);
        initializeClassroom(r,t);
        r.put("photos",new ArrayList<>()); r.put("deductions",new ArrayList<>()); r.put("audit",new ArrayList<>());
        db.update("INSERT IGNORE INTO experiment_runs(id,task_id,group_id,payload,revision,created_at) VALUES(?,?,?,?,0,?)",runId,taskId,groupId,JsonUtil.write(r),r.get("createdAt"));
    }
    void syncRunMembers(String runId, List<Map<String,Object>> members, String taskId) {
        for(Map<String,Object> member:members) {
            db.update("INSERT IGNORE INTO run_members(run_id,sid,name) VALUES(?,?,?)",runId,member.get("sid"),member.get("name"));
            db.update("INSERT IGNORE INTO adopted_runs(task_id,sid,run_id) VALUES(?,?,?)",taskId,member.get("sid"),runId);
        }
    }
    /** 同组已下发但本行 task_id 为空时（补录名单等），从组内或 task_groups 同步任务字段。 */
    @Transactional
    public void syncStudentTaskAssignment(String sid) {
        List<Map<String,Object>> rows=db.queryForList("SELECT sid,group_id,task_id FROM students WHERE sid=?",sid.trim());
        if(rows.isEmpty()) return;
        Map<String,Object> s=rows.get(0);
        if(!str(s.get("task_id")).isBlank()) return;
        String groupId=str(s.get("group_id"));
        if(groupId.isBlank()) return;
        List<Map<String,Object>> mates=db.queryForList("SELECT task_id FROM students WHERE group_id=? AND task_id IS NOT NULL AND task_id<>'' LIMIT 1",groupId);
        String taskId=mates.isEmpty()?"":str(mates.get(0).get("task_id"));
        if(taskId.isBlank()) {
            List<Map<String,Object>> latest=db.queryForList(
                    "SELECT t.id,t.exp_id,t.exp_name,t.note,t.tip,t.place,t.time_text FROM tasks t JOIN task_groups tg ON tg.task_id=t.id "
                            + "WHERE tg.group_id=? ORDER BY t.created_at DESC LIMIT 1",groupId);
            if(latest.isEmpty()) return;
            Map<String,Object> t=latest.get(0);
            db.update("UPDATE students SET status='assigned', task_id=?, exp_id=?, exp_name=?, note=?, tip=?, place=?, time_text=? "
                            + "WHERE sid=? AND (task_id IS NULL OR task_id='')",
                    t.get("id"),t.get("exp_id"),t.get("exp_name"),t.get("note"),t.get("tip"),t.get("place"),t.get("time_text"),sid);
            taskId=str(t.get("id"));
        } else {
            List<Map<String,Object>> task=db.queryForList("SELECT id,exp_id,exp_name,note,tip,place,time_text FROM tasks WHERE id=?",taskId);
            if(task.isEmpty()) return;
            Map<String,Object> t=task.get(0);
            db.update("UPDATE students SET status='assigned', task_id=?, exp_id=?, exp_name=?, note=?, tip=?, place=?, time_text=? "
                            + "WHERE sid=? AND (task_id IS NULL OR task_id='')",
                    t.get("id"),t.get("exp_id"),t.get("exp_name"),t.get("note"),t.get("tip"),t.get("place"),t.get("time_text"),sid);
        }
        provision(taskId);
    }
    @Transactional
    public void repairAllStudentRuns() {
        for(Map<String,Object> row:db.queryForList("SELECT sid FROM students")) {
            String sid=str(row.get("sid"));
            syncStudentTaskAssignment(sid);
            ensureStudentRun(new Actor(false,sid));
        }
    }
    /** 下发后若 experiment_runs / run_members 未齐，按学生当前 task_id + group_id 补建。 */
    void ensureStudentRun(Actor actor) {
        if(actor.teacher()) return;
        syncStudentTaskAssignment(actor.id());
        List<Map<String,Object>> rows=db.queryForList("SELECT sid,name,task_id,group_id FROM students WHERE sid=?",actor.id());
        if(rows.isEmpty()) return;
        Map<String,Object> s=rows.get(0);
        String taskId=str(s.get("task_id")), groupId=str(s.get("group_id"));
        if(taskId.isBlank()||groupId.isBlank()) return;
        provision(taskId);
        String runId=runIdFor(taskId, groupId);
        List<Map<String,Object>> members=db.queryForList("SELECT sid,name FROM students WHERE task_id=? AND group_id=? ORDER BY sid",taskId,groupId);
        if(members.isEmpty()) members=List.of(Map.of("sid",s.get("sid"),"name",s.get("name")));
        if(db.queryForObject("SELECT COUNT(*) FROM experiment_runs WHERE id=?",Integer.class,runId)==0) createGroupRunIfMissing(taskId, groupId, members);
        syncRunMembers(runId, members, taskId);
    }
    public Map<String,Object> studentLabStatus(Actor actor) {
        if(actor.teacher()) throw new ApiException(403,"需要学生权限");
        ensureStudentRun(actor);
        List<Map<String,Object>> rows=db.queryForList("SELECT task_id,group_id,status FROM students WHERE sid=?",actor.id().trim());
        Map<String,Object> out=new LinkedHashMap<>();
        if(rows.isEmpty()) { out.put("hasTask",false); out.put("reason","no_student"); return out; }
        Map<String,Object> s=rows.get(0);
        String taskId=str(s.get("task_id"));
        out.put("status",str(s.get("status")));
        out.put("groupId",str(s.get("group_id")));
        out.put("hasTask",!taskId.isBlank());
        if(taskId.isBlank()) {
            out.put("reason",str(s.get("group_id")).isBlank()?"no_group":"no_task");
            return out;
        }
        provision(taskId);
        int runCount=db.queryForObject("SELECT COUNT(*) FROM experiment_runs r JOIN run_members m ON m.run_id=r.id WHERE m.sid=?",Integer.class,actor.id());
        out.put("hasRun",runCount>0);
        if(runCount==0) out.put("reason","no_run");
        return out;
    }
    @Transactional
    public List<Map<String,Object>> runs(Actor actor) {
        // One-time compatibility provisioning only for current assignments; new tasks provision immediately.
        for(Map<String,Object> t:db.queryForList(actor.teacher()?"SELECT DISTINCT task_id FROM students WHERE task_id IS NOT NULL":"SELECT DISTINCT task_id FROM students WHERE sid=? AND task_id IS NOT NULL",actor.teacher()?new Object[]{}:new Object[]{actor.id()})) provision(str(t.get("task_id")));
        if(!actor.teacher()) ensureStudentRun(actor);
        List<Map<String,Object>> rows=actor.teacher()?db.queryForList("SELECT id FROM experiment_runs ORDER BY created_at DESC"):
                db.queryForList("SELECT r.id FROM experiment_runs r JOIN run_members m ON m.run_id=r.id WHERE m.sid=? ORDER BY r.created_at DESC",actor.id());
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            try {
                Map<String,Object> r=load(str(row.get("id")),actor,false);
                Map<String,Object> s=new LinkedHashMap<>();
                for(String key:List.of("id","taskId","groupName","expName","expId","timeText","place","attempt","status","createdAt","members","classOpen","archived","retakeRequest","requiredTrials","lessonState","scheduledStartAt")) s.put(key,r.get(key));
                List<Map<String,Object>> trialItems=TrialRecords.combined(r)?TrialRecords.items(r):List.of();
                int trialTotal=TrialRecords.combined(r)?trialItems.size():1;
                int completedTrials=TrialRecords.combined(r)
                        ? (int)trialItems.stream().filter(t->Boolean.TRUE.equals(t.get("dataReady"))&&Boolean.TRUE.equals(t.get("photoReady"))).count()
                        : (TrialRecords.complete(r)?1:0);
                ClassroomService.expose(r,actor);
                s.put("operationScore",operationScore(r)); s.put("photoCount",list(r.get("photos")).size());
                s.put("dataReady",!map(r.get("data")).isEmpty());
                s.put("collectionComplete",completedTrials==trialTotal);
                s.put("completedTrials",completedTrials);s.put("trialTotal",trialTotal);
                s.put("deductionCount",list(r.get("deductions")).stream().filter(d->!Boolean.TRUE.equals(d.get("voided"))).count());
                for(String key:List.of("scoreVersion","scorePublishedAt","teacherScoreConfirmationRequired","scorePublicationMode","scoreReady"))s.put(key,r.get(key));
                out.add(s);
            } catch (ApiException ignored) { /* 跳过单条损坏或无权的记录，避免整表加载失败 */ }
        }
        return out;
    }
    static double operationScore(Map<String,Object> r) {
        return calculatedScore(r);
    }
    static double calculatedScore(Map<String,Object> r) {
        return Math.max(0,100-list(r.get("deductions")).stream().filter(d->!Boolean.TRUE.equals(d.get("voided"))).mapToDouble(d->score(d.get("points"))).sum());
    }
    private void enrichStoredDeviceData(Map<String,Object> run) {
        var data=map(run.get("data"));
        if(!"wance_mdb".equals(data.get("source"))||data.containsKey("deviceResults")||str(data.get("fileId")).isEmpty())return;
        var files=db.queryForList("SELECT contents FROM run_files WHERE id=? AND run_id=? AND kind='data'",data.get("fileId"),run.get("id"));
        if(files.isEmpty()||!(files.get(0).get("contents") instanceof byte[] content))return;
        try {
            var summary=WanceMdbReader.storedSummary(content,WanceMdbReader.asInt(data.get("testNo")));
            var enriched=new LinkedHashMap<>(data);enriched.put("deviceResults",summary.get("deviceResults"));
            if(!map(summary.get("geometry")).isEmpty())enriched.put("geometry",summary.get("geometry"));
            run.put("data",enriched);
        } catch(IOException|IllegalArgumentException ignored) { /* A damaged legacy attachment must not hide its saved curve. */ }
    }
    public Map<String,Object> detail(String id,Actor a) {
        Map<String,Object> r=load(id,a,false); r.put("operationScore",operationScore(r)); r.put("calculatedScore",calculatedScore(r));
        enrichStoredDeviceData(r);
        ClassroomService.expose(r,a);
        r.put("adoptedFor",db.queryForList("SELECT sid FROM adopted_runs WHERE run_id=?",id).stream().map(m->m.get("sid")).toList());
        List<Map<String,Object>> reports=new ArrayList<>();
        for(Map<String,Object> row:db.queryForList("SELECT id,sid,version,payload,submitted_at FROM report_versions WHERE run_id=? ORDER BY submitted_at,version",id)) {
            if(!a.teacher()&&!a.id().equals(row.get("sid")))continue;
            Map<String,Object> report=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());
            report.put("id",row.get("id")); report.put("sid",row.get("sid")); report.put("version",row.get("version")); report.put("submittedAt",row.get("submitted_at"));
            if(!a.teacher()) {
                report.remove("similarity"); report.remove("ai"); report.remove("foreignDataEvidence");
                for(Object trial : map(map(report.get("runSnapshot")).get("trials")).values()) { map(trial).remove("analysis"); map(trial).remove("reference"); }
            }
            reports.add(report);
        }
        r.put("reports",reports);
        if (TrialRecords.combined(r)) {
            r.put("trialItems", TrialRecords.items(r));
            r.remove("trials");
            if (!a.teacher()) for (Map<String,Object> trial : list(r.get("trialItems"))) { trial.remove("analysis"); trial.remove("reference"); }
        }
        if(!a.teacher()) {
            r.remove("analysis"); r.remove("reference"); r.remove("operationAi"); r.remove("audit");
            Map<String,Object> observations=map(r.get("observations"));
            r.put("observations",observations.containsKey(a.id())?Map.of(a.id(),observations.get(a.id())):Map.of());
            Map<String,Object> drafts=map(r.get("drafts"));
            r.put("drafts",drafts.containsKey(a.id())?Map.of(a.id(),drafts.get(a.id())):Map.of());
            r.remove("reportReopened");
        }
        return r;
    }
    public Map<String,Object> classroom(String id,Actor a) {
        Map<String,Object> r=load(id,a,false),out=new LinkedHashMap<>();
        ClassroomService.expose(r,a);
        for(String key:List.of("deductions","classOpen","finalScore","finalComment","revision","archived","archivedAt","scorePublishedAt","scoreVersion","confirmationRequired","myScoreConfirmed","myConfirmation","retakeRequest","lessonState","scheduledStartAt","teacherScoreConfirmationRequired","scorePublicationMode","scoreReady"))out.put(key,r.get(key));
        out.put("operationScore",operationScore(r));out.put("calculatedScore",calculatedScore(r));return out;
    }
    public Map<String,Object> legacy(String id,Actor a) {
        authorize(id,a);var rows=db.queryForList("SELECT payload FROM legacy_records WHERE run_id=?",id);
        if(rows.isEmpty())throw new ApiException(404,"没有升级前记录");
        Map<String,Object> out=JsonUtil.readMap(str(rows.get(0).get("payload")),new LinkedHashMap<>());
        if(!a.teacher()) {
            out.put("personal",list(out.get("personal")).stream().filter(p->a.id().equals(p.get("sid"))).toList());
            for(var group:list(out.get("group"))) {
                Map<String,Object> data=JsonUtil.readMap(str(group.get("data_json")),new LinkedHashMap<>());
                for(String key:List.of("fractureType","fractureConf","fractureAnalysis"))data.remove(key);
                group.put("data_json",data);
            }
        }
        return out;
    }
    public void preserveAssignments(List<String> groups) {
        for(String group:groups)for(var row:db.queryForList("SELECT DISTINCT task_id FROM students WHERE group_id=? AND task_id IS NOT NULL",group))provision(str(row.get("task_id")));
    }
    @Transactional
    public Map<String,Object> update(String id,Actor a,Map<String,Object> b) {
        Map<String,Object> r=load(id,a,true); checkRevision(r,b); mutable(r);
        if(b.containsKey("expectedMetadata")) {
            Map<String,Object> expected=map(b.get("expectedMetadata"));
            for(String key:List.of("specimenId","deviceId","experimentAt","note"))if(!str(expected.get(key)).equals(str(r.get(key))))throw new ApiException(409,"组员已更新试件信息，请重新选择当前记录查看后再修改");
        }
        for(String k:List.of("specimenId","deviceId","experimentAt","note")) if(b.containsKey(k))r.put(k,str(b.get(k)));
        if(b.containsKey("geometry")) {
            Map<String,Object> g=map(b.get("geometry"));Map<String,Object> clean=new LinkedHashMap<>();
            for(String key:List.of("area","gaugeLength")) {
                double n;try { n=Double.parseDouble(str(g.get(key))); }catch(Exception e){throw new ApiException(400,"请填写有效截面积和原始长度");}
                if(!Double.isFinite(n)||n<=0)throw new ApiException(400,"截面积和原始长度必须大于零");clean.put(key,n);
            }
            clean.put("strainSource","crosshead");r.put("geometry",clean);
        }
        if(b.containsKey("experimentAt")&&!str(b.get("experimentAt")).isBlank()) {
            try { Instant.parse(str(b.get("experimentAt"))); } catch(Exception e) { throw new ApiException(400,"实验时间格式无效"); }
        }
        r.put("status","collecting"); save(r,a,"保存试件与实验信息"); return detail(id,a);
    }
    byte[] bytes(MultipartFile f) {
        if(f==null||f.isEmpty()||f.getSize()>20*1024*1024) throw new ApiException(400,"文件不能为空且不能超过20MB");
        try { return f.getBytes(); } catch(IOException e) { throw new ApiException(400,"读取文件失败"); }
    }
    String file(String runId,Actor a,String kind,MultipartFile f,byte[] content,String mime,String text,String status) {
        String fid=id(); String name=str(f.getOriginalFilename()).replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
        if(name.isBlank()||name.length()>240)throw new ApiException(400,"文件名无效或过长");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            db.update("INSERT INTO run_files(id,run_id,owner_sid,kind,name,mime,sha256,contents,extracted,parse_status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",fid,runId,a.id(),kind,name,mime,hash,content,text,status,now());
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return fid;
    }
    public Map<String,Object> getFile(String fid,Actor a) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM run_files WHERE id=?",fid);
        if(rows.isEmpty())throw new ApiException(404,"文件不存在"); Map<String,Object> f=rows.get(0);
        if(!"template".equals(f.get("kind"))) {
            authorize(str(f.get("run_id")),a);
            if("report".equals(f.get("kind"))&&!a.teacher()&&!a.id().equals(f.get("owner_sid")))throw new ApiException(403,"仅可访问自己的个人报告");
        }
        return f;
    }
    @Transactional
    public Map<String,Object> photo(String id,Actor a,MultipartFile f,String angle) {
        Map<String,Object> r=load(id,a,true); mutable(r); byte[] data=bytes(f);
        if(!Set.of("正面","侧面","斜面","补充").contains(angle))throw new ApiException(400,"请选择拍摄角度");
        try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers=ImageIO.getImageReaders(input); if(!readers.hasNext())throw new ApiException(400,"请上传JPEG或PNG照片");
            var reader=readers.next();
            try {
                reader.setInput(input); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format))throw new ApiException(400,"支持JPEG和PNG");
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if((long)w*h>24000000)throw new ApiException(400,"图片超过2400万像素，请缩小后上传");
                String fid=file(id,a,"photo",f,data,format.equals("png")?"image/png":"image/jpeg","","ready");
                Map<String,Object> entry=new LinkedHashMap<>();
                entry.put("id",fid);entry.put("angle",angle);entry.put("name",str(f.getOriginalFilename()));
                entry.put("width",w);entry.put("height",h);entry.put("at",now());
                entry.put("warning",Math.min(w,h)<400?"图像较小，建议补拍清晰照片":"");
                // 保留各角度的每次采集，由用户明确移除不采用的照片。
                List<Map<String,Object>> photos=new ArrayList<>(list(r.get("photos")));
                photos.add(entry);
                r.put("photos",photos);r.remove("analysis");r.remove("fractureSummary");r.remove("reference");r.put("status","collecting");save(r,a,"采集断口照片："+angle);
            } finally { reader.dispose(); }
        } catch(ApiException e) { throw e; } catch(Exception e) { throw new ApiException(400,"图片损坏，无法读取"); }
        return detail(id,a);
    }
    @Transactional
    public Map<String,Object> removePhoto(String id,String fid,Actor a) {
        Map<String,Object> r=load(id,a,true);mutable(r);
        r.put("photos",new ArrayList<>(list(r.get("photos")).stream().filter(p->!fid.equals(p.get("id"))).toList()));
        r.remove("analysis");r.remove("fractureSummary");r.remove("reference");save(r,a,"移除不采用的照片（原文件留存）");return detail(id,a);
    }
    public Map<String,Object> preview(MultipartFile f) {
        List<List<String>> rows=ExperimentDataParser.rows(bytes(f),str(f.getOriginalFilename()));
        return Map.of("rows",rows.stream().limit(12).toList(),"rowCount",rows.size());
    }
    @Transactional
    public Map<String,Object> importWanceData(String id,Actor a) {
        return importWanceData(id,a,"127.0.0.1");
    }
    CaptureWindow captureWindow(Map<String,Object> run) {
        var tasks=db.queryForList("SELECT * FROM tasks WHERE id=?",run.get("taskId"));
        var window=CaptureWindow.of(run,tasks.isEmpty()?Map.of():tasks.get(0),Instant.now());
        window.requireOpen();
        return window;
    }
    @Transactional
    public Map<String,Object> importWanceData(String id,Actor a,String clientAddress) {
        Map<String,Object> r=load(id,a,true); mutable(r);
        var window=captureWindow(r);
        try {
            var station=wance.station(clientAddress);
            if(!Files.isDirectory(station.directory()))throw new ApiException(503,"实验台数据目录不可用，请联系管理员检查共享目录或采集同步");
            Path mdb=WanceMdbReader.latestMdb(station.directory(),window);
            var snapshot=WanceMdbReader.readStable(mdb,Instant.now(),wance.settleTime());
            byte[] content=snapshot.content();
            Map<String,Object> parsed=new LinkedHashMap<>(WanceMdbReader.parseSnapshot(mdb,snapshot));
            DataClaims.claim(db,r,content);
            parsed.put("sourceMode","device_file");
            parsed.put("stationId",station.id());parsed.put("stationName",station.name());
            parsed.put("validFrom",window.start().toString());parsed.put("validUntil",window.end().toString());
            Map<String,Object> geometry=map(parsed.get("geometry"));
            if(geometry.isEmpty())r.remove("geometry");
            else r.put("geometry",geometry);
            String fid=file(id,a,"data",new MockBytesMultipart(mdb.getFileName().toString(),content),content,"application/x-msaccess","","ready");
            parsed.put("fileId",fid);parsed.put("fileName",mdb.getFileName().toString());parsed.put("importedAt",now());
            r.put("data",parsed);r.remove("analysis");r.remove("fractureSummary");
            Object expAt=parsed.get("experimentAtFromFile");
            if(expAt!=null&&str(r.get("experimentAt")).isBlank()) r.put("experimentAt",expAt);
            save(r,a,"关联万测试验数据："+mdb.getFileName());return detail(id,a);
        } catch(ApiException e) { throw e; } catch(IOException e) { throw new ApiException(503,"读取万测数据失败："+e.getMessage()); }
    }
    public Map<String,Object> wanceStatus() throws IOException { return wance.status(); }
    public Map<String,Object> wanceStatus(String clientAddress) { return wance.status(clientAddress); }
    @Transactional
    public Map<String,Object> importWanceFile(String id,Actor a,MultipartFile upload,String clientAddress) {
        Map<String,Object> r=load(id,a,true);mutable(r);
        var window=captureWindow(r);var station=wance.station(clientAddress);
        String name=str(upload.getOriginalFilename()).replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".mdb"))throw new ApiException(400,"请选择万测软件生成的 .mdb 试验文件");
        window.require(CaptureWindow.uploadedTime(name));
        byte[] content=bytes(upload);
        try {
            Map<String,Object> parsed=new LinkedHashMap<>(WanceMdbReader.parseSnapshot(Path.of(name),new WanceMdbReader.Snapshot(content,Instant.now())));
            DataClaims.claim(db,r,content);
            String fid=file(id,a,"data",new MockBytesMultipart(name,content),content,"application/x-msaccess","","ready");
            parsed.put("sourceMode","manual_test_file");parsed.put("stationId",station.id());parsed.put("stationName",station.name());
            parsed.put("validFrom",window.start().toString());parsed.put("validUntil",window.end().toString());
            parsed.put("fileId",fid);parsed.put("fileName",name);parsed.put("importedAt",now());
            Map<String,Object> geometry=map(parsed.get("geometry"));
            if(geometry.isEmpty())r.remove("geometry");else r.put("geometry",geometry);
            r.put("data",parsed);r.remove("analysis");r.remove("fractureSummary");
            if(str(r.get("experimentAt")).isBlank())r.put("experimentAt",parsed.get("experimentAtFromFile"));
            save(r,a,"手选万测试验文件（临时测试）："+name);return detail(id,a);
        } catch(ApiException e) {throw e;} catch(IOException e) {throw new ApiException(400,"无法读取该万测文件，请选择软件生成并已保存完整的 .mdb 试验文件");}
    }
    @Transactional
    public Map<String,Object> undoData(String id,Actor a,Map<String,Object> body) {
        Map<String,Object> r=load(id,a,true);checkRevision(r,body);mutable(r);
        var data=map(r.get("data"));String fileId=str(data.get("fileId"));
        if(fileId.isEmpty())throw new ApiException(409,"当前子实验没有可撤销的采集数据");
        if(!fileId.equals(str(body.get("fileId"))))throw new ApiException(409,"采集数据已更新，请刷新核对后再撤销");
        DataClaims.release(db,r,fileId);
        r.remove("data");r.remove("geometry");r.remove("analysis");r.remove("fractureSummary");
        if(str(r.get("experimentAt")).equals(str(data.get("experimentAtFromFile"))))r.remove("experimentAt");
        r.put("status","collecting");
        save(r,a,"撤销"+str(r.getOrDefault("trialName",r.get("expName")))+"的数据采集（原文件留存）："+str(data.get("fileName")));
        return detail(id,a);
    }
    @Transactional
    public Map<String,Object> importData(String id,Actor a,MultipartFile f,int start,int force,int displacement,String unit) {
        return importData(id,a,f,start,force,displacement,unit,"127.0.0.1");
    }
    @Transactional
    public Map<String,Object> importData(String id,Actor a,MultipartFile f,int start,int force,int displacement,String unit,String clientAddress) {
        Map<String,Object> r=load(id,a,true); mutable(r); byte[] content=bytes(f);
        var window=captureWindow(r);
        var station=wance.station(clientAddress);
        Instant experimentAt=CaptureWindow.uploadedTime(f.getOriginalFilename());window.require(experimentAt);
        Map<String,Object> parsed=new LinkedHashMap<>(ExperimentDataParser.parse(ExperimentDataParser.rows(content,str(f.getOriginalFilename())),start,force,displacement,unit));
        DataClaims.claim(db,r,content);
        String fid=file(id,a,"data",f,content,"application/octet-stream","","ready");
        parsed.put("fileId",fid);parsed.put("fileName",str(f.getOriginalFilename()));parsed.put("importedAt",now());parsed.put("source","device_file");
        parsed.put("mapping",Map.of("startRow",start,"forceColumn",force,"displacementColumn",displacement,"originalForceUnit",unit));
        parsed.put("stationId",station.id());parsed.put("stationName",station.name());parsed.put("experimentAtFromFile",experimentAt.toString());
        parsed.put("validFrom",window.start().toString());parsed.put("validUntil",window.end().toString());
        r.put("data",parsed);r.remove("analysis");r.remove("fractureSummary");save(r,a,"导入原设备实验数据");return detail(id,a);
    }
    @Transactional
    public Map<String,Object> archive(String id,Actor a,Map<String,Object> b) {
        if(a.teacher())throw new ApiException(403,"请由学生本人接受成绩并提交记录");
        Map<String,Object> r=load(id,a,true);checkRevision(r,b);
        if("scheduled".equals(r.get("lessonState")))throw new ApiException(409,"本课次尚未开始");
        if(Boolean.TRUE.equals(r.get("archived")))throw new ApiException(409,"操作记录已提交");
        if(Boolean.TRUE.equals(r.get("classOpen"))&&ClassroomService.teacherConfirmationRequired(r))throw new ApiException(409,"请等待教师确认扣分后再提交操作记录");
        if(!str(ClassroomService.version(r)).equals(str(b.get("scoreVersion"))))throw new ApiException(409,"操作成绩已更新，请重新核对后提交");
        String retakeStatus=str(map(r.get("retakeRequest")).get("status"));
        if(Set.of("pending","arranged").contains(retakeStatus))throw new ApiException(409,"已申请或安排重做，请先处理重做申请");
        if(str(r.get("experimentAt")).isBlank()) r.put("experimentAt",now());
        if (TrialRecords.combined(r)) TrialRecords.requireComplete(r);
        else {
            if(list(r.get("photos")).isEmpty())throw new ApiException(400,"请先采集断口照片");
            if(map(r.get("data")).isEmpty())throw new ApiException(400,"断口已保存；请补齐本次原设备数据后归档");
        }
        ClassroomService.publishAutomatically(r);
        ClassroomService.recordConfirmation(r,a.id(),"confirmed","");
        r.put("archived",true);r.put("archivedAt",now());r.put("status","archived");save(r,a,"接受操作成绩并提交实验操作记录");
        db.update("INSERT INTO run_archives(id,run_id,payload,created_at) VALUES(?,?,?,?)",id(),id,JsonUtil.write(TrialRecords.stored(r)),now());
        return detail(id,a);
    }
}
