package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static cn.ncut.lab.service.RunService.*;

@Service
@SuppressWarnings("unchecked")
public class TeachingService {
    private final RunService runs;
    private final ReportParser parser;
    public TeachingService(RunService runs,ReportParser parser){this.runs=runs;this.parser=parser;}
    public Map<String,Object> settings() {
        List<Map<String,Object>> rows=runs.jdbc().queryForList("SELECT payload FROM workflow_settings WHERE id='main'");
        if(!rows.isEmpty())return JsonUtil.readMap(str(rows.get(0).get("payload")),new LinkedHashMap<>());
        Map<String,Object> s=new LinkedHashMap<>();
        s.put("catalog",List.of(Map.of("id","install","label","装样时多人同时操作","points",0),Map.of("id","door","label","实验运行时尝试打开保护罩","points",0),Map.of("id","remove","label","拆样时多人同时操作","points",0),Map.of("id","discipline","label","实验过程中打闹嬉戏","points",0)));
        s.put("catalogConfirmed",false);s.put("requiredSections",List.of());s.put("similarityThreshold",70);s.put("similarityPenalty",10);s.put("templateExclusions","");s.put("revision",0);return s;
    }
    @Transactional
    public Map<String,Object> settings(Actor a,Map<String,Object> b) {
        teacher(a);
        runs.jdbc().update("INSERT IGNORE INTO workflow_settings(id,payload) VALUES('main',?)",JsonUtil.write(settings()));
        Map<String,Object> s=JsonUtil.readMap(str(runs.jdbc().queryForMap("SELECT payload FROM workflow_settings WHERE id='main' FOR UPDATE").get("payload")),new LinkedHashMap<>());
        if(!str(s.get("revision")).equals(str(b.get("revision"))))throw new ApiException(409,"规则已更新，请刷新后重试");
        if(b.containsKey("catalog")) {
            List<Map<String,Object>> catalog=list(b.get("catalog"));if(catalog.isEmpty()||catalog.size()>50)throw new ApiException(400,"请设置1至50个扣分项目");
            Set<String> ids=new HashSet<>();List<Map<String,Object>> clean=new ArrayList<>();
            for(var item:catalog){String key=required(item.get("id"),"项目编号");if(!ids.add(key))throw new ApiException(400,"项目编号不能重复");clean.add(Map.of("id",key,"label",required(item.get("label"),"项目名称"),"points",score(item.get("points"))));}
            s.put("catalog",clean);s.put("catalogConfirmed",Boolean.TRUE.equals(b.get("catalogConfirmed")));
        }
        for(String key:List.of("similarityThreshold","similarityPenalty"))if(b.containsKey(key))s.put(key,score(b.get(key)));
        if(b.containsKey("requiredSections")) {
            if(!(b.get("requiredSections") instanceof List<?> sections)||sections.size()>30)throw new ApiException(400,"章节配置无效");
            s.put("requiredSections",sections.stream().map(RunService::str).filter(x->!x.isBlank()).distinct().toList());
        }
        for(String key:List.of("templateExclusions","retakeStart","retakeEnd"))if(b.containsKey(key))s.put(key,str(b.get(key)));
        if(!str(s.get("retakeStart")).isBlank()||!str(s.get("retakeEnd")).isBlank()) {
            try {if(!java.time.Instant.parse(str(s.get("retakeStart"))).isBefore(java.time.Instant.parse(str(s.get("retakeEnd")))))throw new IllegalArgumentException();}
            catch(Exception e){throw new ApiException(400,"请填写有效的补做窗口，开始须早于结束");}
        }
        s.put("revision",((Number)s.getOrDefault("revision",0)).intValue()+1);s.put("updatedAt",now());s.put("updatedBy",a.id());
        runs.jdbc().update("UPDATE workflow_settings SET payload=? WHERE id='main'",JsonUtil.write(s));return s;
    }
    @Transactional
    public Object template(Actor a,MultipartFile f) {
        teacher(a);byte[] bytes=runs.bytes(f);validateExtension(f);
        ReportParser.Result parsed=parser.parse(bytes,str(f.getOriginalFilename()));
        String fid=runs.file("template",a,"template",f,bytes,parsed.mime(),parsed.text(),parsed.status());
        runs.jdbc().update("INSERT IGNORE INTO workflow_settings(id,payload) VALUES('main',?)",JsonUtil.write(settings()));
        Map<String,Object> s=JsonUtil.readMap(str(runs.jdbc().queryForMap("SELECT payload FROM workflow_settings WHERE id='main' FOR UPDATE").get("payload")),new LinkedHashMap<>());
        s.put("template",Map.of("id",fid,"name",str(f.getOriginalFilename()),"at",now()));s.put("revision",((Number)s.getOrDefault("revision",0)).intValue()+1);
        runs.jdbc().update("UPDATE workflow_settings SET payload=? WHERE id='main'",JsonUtil.write(s));return s;
    }
    @Transactional
    public Object deduction(String id,Actor a,Map<String,Object> b) {
        teacher(a);Map<String,Object> r=runs.load(id,a,true);runs.checkRevision(r,b);
        if(!Boolean.TRUE.equals(r.get("classOpen")))throw new ApiException(409,"课堂已结束，需先以纠错理由重新开放记录");
        String reason=required(b.get("reason"),"扣分依据");Map<String,Object> s=settings();
        if(!Boolean.TRUE.equals(s.get("catalogConfirmed")))throw new ApiException(400,"请先确认学校扣分项目及分值");
        List<?> selected=b.get("items") instanceof List<?> l?l:List.of();if(selected.isEmpty())throw new ApiException(400,"请选择扣分项目");
        for(Object key:new LinkedHashSet<>(selected)) {
            Map<String,Object> rule=list(s.get("catalog")).stream().filter(c->Objects.equals(c.get("id"),key)).findFirst().orElseThrow(()->new ApiException(400,"扣分项目不存在"));
            List<Map<String,Object>> deductions=list(r.get("deductions"));Map<String,Object> item=new LinkedHashMap<>(rule);item.put("id",id());item.put("ruleId",key);item.put("reason",reason);item.put("by",a.id());item.put("at",now());deductions.add(item);r.put("deductions",deductions);
        }
        runs.save(r,a,"记录课堂扣分："+reason);return runs.detail(id,a);
    }
    @Transactional
    public Object voidDeduction(String id,String did,Actor a,Map<String,Object> b) {
        teacher(a);Map<String,Object> r=runs.load(id,a,true);runs.checkRevision(r,b);
        String reason=required(b.get("reason"),"撤销理由");boolean found=false;
        for(var d:list(r.get("deductions")))if(did.equals(d.get("id"))){d.put("voided",true);d.put("voidReason",reason);d.put("voidAt",now());d.put("voidBy",a.id());found=true;}
        if(!found)throw new ApiException(404,"扣分记录不存在");runs.save(r,a,"纠正扣分："+reason);return runs.detail(id,a);
    }
    @Transactional
    public Object control(String id,Actor a,Map<String,Object> b) {
        teacher(a);Map<String,Object> r=runs.load(id,a,true);runs.checkRevision(r,b);String reason=required(b.get("reason"),"操作理由");
        switch(str(b.get("action"))) {
            case "close" -> r.put("classOpen",false);
            case "open" -> r.put("classOpen",true);
            case "return" -> {r.put("archived",false);r.put("status","collecting");}
            case "grade" -> {r.put("finalScore",score(b.get("score")));r.put("finalComment",required(b.get("comment"),"评语"));r.put("gradedBy",a.id());r.put("gradedAt",now());}
            case "clearGrade" -> {r.remove("finalScore");r.remove("finalComment");}
            default -> throw new ApiException(400,"未知操作");
        }
        runs.save(r,a,str(b.get("action"))+"："+reason);return runs.detail(id,a);
    }
    private void validateExtension(MultipartFile f) {
        if(!str(f.getOriginalFilename()).toLowerCase(Locale.ROOT).matches(".*\\.(docx|doc|pdf)$"))throw new ApiException(400,"支持Word（DOC/DOCX）和PDF");
    }
    @Transactional
    public Object uploadReport(String id,Actor a,MultipartFile f) {
        if(a.teacher())throw new ApiException(403,"由学生本人上传报告");
        Map<String,Object> r=runs.load(id,a,true);canSubmit(r,a);validateExtension(f);
        byte[] bytes=runs.bytes(f);ReportParser.Result parsed=parser.parse(bytes,str(f.getOriginalFilename()));
        String fid=runs.file(id,a,"report",f,bytes,parsed.mime(),parsed.text(),parsed.status());
        Map<String,Object> draft=new LinkedHashMap<>();draft.put("fileId",fid);draft.put("fileName",str(f.getOriginalFilename()));draft.put("parseStatus",parsed.status());draft.put("warning",parsed.warning());draft.put("uploadedAt",now());draft.put("textPreview",parsed.text().substring(0,Math.min(4000,parsed.text().length())));
        Map<String,Object> drafts=map(r.get("drafts"));drafts.put(a.id(),draft);r.put("drafts",drafts);
        runs.save(r,a,"保存个人报告草稿附件");return runs.detail(id,a);
    }
    private void canSubmit(Map<String,Object> r,Actor a) {
        if(!Boolean.TRUE.equals(r.get("archived")))throw new ApiException(409,"请先完成实验记录归档");
        Integer n=runs.jdbc().queryForObject("SELECT COUNT(*) FROM report_versions WHERE run_id=? AND sid=?",Integer.class,r.get("id"),a.id());
        if(n>0&&!Boolean.TRUE.equals(map(r.get("reportReopened")).get(a.id())))throw new ApiException(409,"报告已提交，需教师退回后上传新版本");
    }
    @Transactional
    public Object observation(String id,Actor a,Map<String,Object> b) {
        if(a.teacher())throw new ApiException(403,"由学生本人填写观察");Map<String,Object> r=runs.load(id,a,true);
        // Own observation remains independent of the group's capture progress.
        Integer n=runs.jdbc().queryForObject("SELECT COUNT(*) FROM report_versions WHERE run_id=? AND sid=?",Integer.class,id,a.id());
        if(n>0&&!Boolean.TRUE.equals(map(r.get("reportReopened")).get(a.id())))throw new ApiException(409,"已提交报告，观察记录已锁定");
        String judgment=required(b.get("judgment"),"断裂类型");if(!Set.of("ductile","brittle","uncertain").contains(judgment))throw new ApiException(400,"判断无效");
        Map<String,Object> all=map(r.get("observations"));all.put(a.id(),Map.of("features",required(b.get("features"),"断口特征描述"),"judgment",judgment,"at",now()));r.put("observations",all);
        runs.save(r,a,"保存个人断口观察");return runs.detail(id,a);
    }
    @Transactional
    public Object submit(String id,Actor a,Map<String,Object> b) {
        if(a.teacher())throw new ApiException(403,"由学生本人提交");Map<String,Object> r=runs.load(id,a,true);
        String requestId=required(b.get("requestId"),"提交请求编号");
        List<Map<String,Object>> old=runs.jdbc().queryForList("SELECT payload FROM report_versions WHERE run_id=? AND sid=?",id,a.id());
        for(var row:old)if(requestId.equals(JsonUtil.readMap(str(row.get("payload")),Map.of()).get("requestId")))return runs.detail(id,a);
        canSubmit(r,a);Map<String,Object> draft=map(map(r.get("drafts")).get(a.id()));if(draft.isEmpty())throw new ApiException(400,"请先上传报告文件");
        Map<String,Object> observation=map(map(r.get("observations")).get(a.id()));if(observation.isEmpty())throw new ApiException(400,"请先保存个人断口观察与判断");
        Map<String,Object> f=runs.getFile(str(draft.get("fileId")),a);int version=old.size()+1;
        Map<String,Object> report=new LinkedHashMap<>(draft);report.remove("textPreview");report.put("observation",observation);report.put("requestId",requestId);report.put("sha256",f.get("sha256"));
        Map<String,Object> submittedData=new LinkedHashMap<>(map(r.get("data")));submittedData.remove("points");
        report.put("runSnapshot",Map.of("specimenId",r.get("specimenId"),"deviceId",r.get("deviceId"),"dataFileId",map(r.get("data")).get("fileId"),"data",submittedData,"photos",r.get("photos"),"runRevision",r.get("revision")));
        List<String> missing=new ArrayList<>();String text=str(f.get("extracted"));
        if("ready".equals(f.get("parse_status")))for(Object section:(List<?>)settings().getOrDefault("requiredSections",List.of()))if(!text.replaceAll("\\s","").contains(str(section).replaceAll("\\s","")))missing.add(str(section));
        report.put("templateCheck",Map.of("missingSections",missing,"checked",!((List<?>)settings().getOrDefault("requiredSections",List.of())).isEmpty()&&"ready".equals(f.get("parse_status")),"note","章节检查仅供参考；公式、表格及排版请对照原件"));
        String rid=id();runs.jdbc().update("INSERT INTO report_versions(id,run_id,sid,version,payload,submitted_at) VALUES(?,?,?,?,?,?)",rid,id,a.id(),version,JsonUtil.write(report),now());
        Map<String,Object> reopened=map(r.get("reportReopened"));reopened.put(a.id(),false);r.put("reportReopened",reopened);runs.save(r,a,"提交个人报告第"+version+"版，回执"+rid);
        return runs.detail(id,a);
    }
    @Transactional
    public Object review(String id,String reportId,Actor a,Map<String,Object> b) {
        teacher(a);Map<String,Object> r=runs.load(id,a,true);runs.checkRevision(r,b);
        List<Map<String,Object>> rows=runs.jdbc().queryForList("SELECT * FROM report_versions WHERE id=? AND run_id=?",reportId,id);if(rows.isEmpty())throw new ApiException(404,"报告不存在");
        Map<String,Object> row=rows.get(0),report=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());String reason=required(b.get("comment"),"评语或退回理由");
        if("return".equals(b.get("action"))) {
            int latest=runs.jdbc().queryForObject("SELECT MAX(version) FROM report_versions WHERE run_id=? AND sid=?",Integer.class,id,row.get("sid"));
            if(((Number)row.get("version")).intValue()!=latest)throw new ApiException(409,"只能退回最新版本");
            Map<String,Object> reopened=map(r.get("reportReopened"));reopened.put(str(row.get("sid")),true);r.put("reportReopened",reopened);report.put("returned",true);report.put("returnReason",reason);
        } else {report.put("finalScore",score(b.get("score")));report.put("finalComment",reason);report.put("gradedAt",now());report.put("gradedBy",a.id());}
        runs.jdbc().update("UPDATE report_versions SET payload=? WHERE id=?",JsonUtil.write(report),reportId);runs.save(r,a,"复核个人报告"+reportId+"："+reason);return runs.detail(id,a);
    }
}
