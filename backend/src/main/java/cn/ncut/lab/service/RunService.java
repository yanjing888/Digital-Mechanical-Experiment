package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@SuppressWarnings("unchecked")
public class RunService {
    final JdbcTemplate db;
    public RunService(JdbcTemplate db) { this.db=db; }
    public JdbcTemplate jdbc() { return db; }
    public record Actor(boolean teacher, String id) {}
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
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM experiment_runs WHERE id=?"+(lock?" FOR UPDATE":""),runId);
        if(rows.isEmpty()) throw new ApiException(404,"实验记录不存在");
        Map<String,Object> r=JsonUtil.readMap(str(rows.get(0).get("payload")),new LinkedHashMap<>());
        r.put("id",runId); r.put("revision",rows.get(0).get("revision")); return r;
    }
    void save(Map<String,Object> run,Actor actor,String action) {
        List<Map<String,Object>> audit=list(run.get("audit"));
        audit.add(Map.of("at",now(),"by",actor.id(),"action",action)); run.put("audit",audit);
        int revision=((Number)run.getOrDefault("revision",0)).intValue();
        int updated=db.update("UPDATE experiment_runs SET payload=?,revision=revision+1 WHERE id=? AND revision=?",JsonUtil.write(run),run.get("id"),revision);
        if(updated!=1) throw new ApiException(409,"记录已更新，请刷新后重试"); run.put("revision",revision+1);
    }
    void mutable(Map<String,Object> run) { if(Boolean.TRUE.equals(run.get("archived"))) throw new ApiException(409,"记录已归档；请由教师退回后修改"); }
    void checkRevision(Map<String,Object> run,Map<String,Object> body) {
        if(!str(run.get("revision")).equals(str(body.get("revision")))) throw new ApiException(409,"记录已更新，请刷新后重试，未保存的输入仍保留");
    }

    /** Snapshot assigned members before later regrouping. Existing legacy data is referenced separately. */
    @Transactional
    public void provision(String taskId) {
        List<Map<String,Object>> tasks=db.queryForList("SELECT * FROM tasks WHERE id=?",taskId);
        if(tasks.isEmpty())return;
        Map<String,Object> t=tasks.get(0);
        for(Map<String,Object> group:db.queryForList("SELECT g.id,g.name FROM task_groups tg JOIN lab_groups g ON g.id=tg.group_id WHERE tg.task_id=?",taskId)) {
            String runId=UUID.nameUUIDFromBytes((taskId+":"+group.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if(db.queryForObject("SELECT COUNT(*) FROM experiment_runs WHERE id=?",Integer.class,runId)>0)continue;
            List<Map<String,Object>> members=db.queryForList("SELECT sid,name FROM students WHERE task_id=? AND group_id=? ORDER BY sid",taskId,group.get("id"));
            if(members.isEmpty()) continue;
            Map<String,Object> r=new LinkedHashMap<>();
            r.put("id",runId); r.put("taskId",taskId); r.put("groupId",group.get("id")); r.put("groupName",group.get("name"));
            r.put("expName",t.get("exp_name")); r.put("expId",t.get("exp_id")); r.put("timeText",t.get("time_text"));
            r.put("attempt",1); r.put("members",members); r.put("createdAt",now()); r.put("status","pending"); r.put("classOpen",true);
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
            for(Map<String,Object> member:members) {
                db.update("INSERT IGNORE INTO run_members(run_id,sid,name) VALUES(?,?,?)",runId,member.get("sid"),member.get("name"));
                db.update("INSERT IGNORE INTO adopted_runs(task_id,sid,run_id) VALUES(?,?,?)",taskId,member.get("sid"),runId);
            }
        }
    }
    @Transactional
    public List<Map<String,Object>> runs(Actor actor) {
        // One-time compatibility provisioning only for current assignments; new tasks provision immediately.
        for(Map<String,Object> t:db.queryForList(actor.teacher()?"SELECT DISTINCT task_id FROM students WHERE task_id IS NOT NULL":"SELECT DISTINCT task_id FROM students WHERE sid=? AND task_id IS NOT NULL",actor.teacher()?new Object[]{}:new Object[]{actor.id()})) provision(str(t.get("task_id")));
        List<Map<String,Object>> rows=actor.teacher()?db.queryForList("SELECT id FROM experiment_runs ORDER BY created_at DESC"):
                db.queryForList("SELECT r.id FROM experiment_runs r JOIN run_members m ON m.run_id=r.id WHERE m.sid=? ORDER BY r.created_at DESC",actor.id());
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Map<String,Object> r=load(str(row.get("id")),actor,false);
            Map<String,Object> s=new LinkedHashMap<>();
            for(String key:List.of("id","taskId","groupName","expName","timeText","attempt","status","createdAt","members","classOpen","archived","retakeRequest")) s.put(key,r.get(key));
            s.put("operationScore",operationScore(r)); s.put("photoCount",list(r.get("photos")).size());
            s.put("dataReady",!map(r.get("data")).isEmpty()); out.add(s);
        }
        return out;
    }
    static double operationScore(Map<String,Object> r) {
        if(r.get("finalScore")!=null)return score(r.get("finalScore"));
        return calculatedScore(r);
    }
    static double calculatedScore(Map<String,Object> r) {
        return Math.max(0,100-list(r.get("deductions")).stream().filter(d->!Boolean.TRUE.equals(d.get("voided"))).mapToDouble(d->score(d.get("points"))).sum());
    }
    public Map<String,Object> detail(String id,Actor a) {
        Map<String,Object> r=load(id,a,false); r.put("operationScore",operationScore(r)); r.put("calculatedScore",calculatedScore(r));
        r.put("adoptedFor",db.queryForList("SELECT sid FROM adopted_runs WHERE run_id=?",id).stream().map(m->m.get("sid")).toList());
        List<Map<String,Object>> reports=new ArrayList<>();
        for(Map<String,Object> row:db.queryForList("SELECT id,sid,version,payload,submitted_at FROM report_versions WHERE run_id=? ORDER BY submitted_at,version",id)) {
            if(!a.teacher()&&!a.id().equals(row.get("sid")))continue;
            Map<String,Object> report=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());
            report.put("id",row.get("id")); report.put("sid",row.get("sid")); report.put("version",row.get("version")); report.put("submittedAt",row.get("submitted_at"));
            if(!a.teacher()) { report.remove("similarity"); report.remove("ai"); report.remove("foreignDataEvidence"); }
            reports.add(report);
        }
        r.put("reports",reports);
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
        for(String key:List.of("deductions","classOpen","finalScore","finalComment"))out.put(key,r.get(key));
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
                List<Map<String,Object>> photos=list(r.get("photos"));
                if(photos.size()>=8)throw new ApiException(400,"每次实验最多保留8张照片，可先移除不采用的照片");
                String fid=file(id,a,"photo",f,data,format.equals("png")?"image/png":"image/jpeg","","ready");
                photos.add(Map.of("id",fid,"angle",angle,"name",str(f.getOriginalFilename()),"width",w,"height",h,"at",now(),"warning",Math.min(w,h)<400?"图像较小，建议补拍清晰照片":""));
                r.put("photos",photos);r.remove("analysis");r.remove("reference");r.put("status","collecting");save(r,a,"采集断口照片："+angle);
            } finally { reader.dispose(); }
        } catch(ApiException e) { throw e; } catch(Exception e) { throw new ApiException(400,"图片损坏，无法读取"); }
        return detail(id,a);
    }
    @Transactional
    public Map<String,Object> removePhoto(String id,String fid,Actor a) {
        Map<String,Object> r=load(id,a,true);mutable(r);
        r.put("photos",new ArrayList<>(list(r.get("photos")).stream().filter(p->!fid.equals(p.get("id"))).toList()));
        r.remove("analysis");r.remove("reference");save(r,a,"移除不采用的照片（原文件留存）");return detail(id,a);
    }
    public Map<String,Object> preview(MultipartFile f) {
        List<List<String>> rows=ExperimentDataParser.rows(bytes(f),str(f.getOriginalFilename()));
        return Map.of("rows",rows.stream().limit(12).toList(),"rowCount",rows.size());
    }
    @Transactional
    public Map<String,Object> importData(String id,Actor a,MultipartFile f,int start,int force,int displacement,String unit) {
        Map<String,Object> r=load(id,a,true); mutable(r); byte[] content=bytes(f);
        Map<String,Object> parsed=new LinkedHashMap<>(ExperimentDataParser.parse(ExperimentDataParser.rows(content,str(f.getOriginalFilename())),start,force,displacement,unit));
        String fid=file(id,a,"data",f,content,"application/octet-stream","","ready");
        parsed.put("fileId",fid);parsed.put("fileName",str(f.getOriginalFilename()));parsed.put("importedAt",now());parsed.put("source","device_file");
        parsed.put("mapping",Map.of("startRow",start,"forceColumn",force,"displacementColumn",displacement,"originalForceUnit",unit));
        r.put("data",parsed);r.remove("analysis");save(r,a,"导入原设备实验数据");return detail(id,a);
    }
    @Transactional
    public Map<String,Object> archive(String id,Actor a,Map<String,Object> b) {
        Map<String,Object> r=load(id,a,true);checkRevision(r,b);mutable(r);
        required(r.get("specimenId"),"试件编号");required(r.get("deviceId"),"设备编号");required(r.get("experimentAt"),"实验完成时间");
        if(list(r.get("photos")).isEmpty())throw new ApiException(400,"请先采集断口照片");
        if(map(r.get("data")).isEmpty())throw new ApiException(400,"断口已保存；请补齐本次原设备数据后归档");
        r.put("archived",true);r.put("archivedAt",now());r.put("status","archived");save(r,a,"归档实验操作记录");
        db.update("INSERT INTO run_archives(id,run_id,payload,created_at) VALUES(?,?,?,?)",id(),id,JsonUtil.write(r),now());
        return detail(id,a);
    }
}
