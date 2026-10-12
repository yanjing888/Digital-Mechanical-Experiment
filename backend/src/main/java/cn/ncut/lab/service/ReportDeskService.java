package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.ncut.lab.service.RunService.*;

@Service
public class ReportDeskService {
    private final RunService runs; private final TeachingService teaching;
    public ReportDeskService(RunService runs,TeachingService teaching) { this.runs=runs;this.teaching=teaching; }
    public Map<String,Object> gradebook(Actor a) {
        teacher(a);
        List<Map<String,Object>> experiments=new ArrayList<>();
        experiments.add(new LinkedHashMap<>(Map.of("id",ExperimentCatalog.COMBINED,"name","拉伸压缩实验","hours",2,"available",true,"trials",ExperimentCatalog.TRIAL_IDS.stream().map(ExperimentCatalog.NAMES::get).toList())));
        experiments.add(new LinkedHashMap<>(Map.of("id",ExperimentCatalog.TORSION,"name","扭转实验","hours",2,"available",false,"trials",List.of())));
        experiments.add(new LinkedHashMap<>(Map.of("id",ExperimentCatalog.BENDING,"name","弯曲实验","hours",2,"available",false,"trials",List.of())));

        Map<String,Map<String,Object>> latestReports=new HashMap<>();
        for(var row:runs.jdbc().queryForList("SELECT run_id,sid,version,payload,submitted_at FROM report_versions ORDER BY submitted_at,version")) {
            Map<String,Object> report=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());
            report.put("version",row.get("version"));report.put("submittedAt",row.get("submitted_at"));
            latestReports.put(row.get("run_id")+"|"+row.get("sid"),report);
        }

        Map<String,Map<String,Object>> students=new LinkedHashMap<>();
        for(var row:runs.jdbc().queryForList("SELECT sid,name,cls FROM students ORDER BY cls,sid")) {
            Map<String,Object> student=new LinkedHashMap<>(row);Map<String,Object> scores=new LinkedHashMap<>();
            for(var experiment:experiments)scores.put(str(experiment.get("id")),new LinkedHashMap<>(Map.of("status","未开课")));
            student.put("scores",scores);students.put(str(row.get("sid")),student);
        }
        for(var row:runs.jdbc().queryForList("SELECT r.id,r.payload,r.created_at,rm.sid FROM experiment_runs r JOIN run_members rm ON rm.run_id=r.id ORDER BY r.created_at,r.id")) {
            Map<String,Object> student=students.get(str(row.get("sid")));if(student==null)continue;
            Map<String,Object> run=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());String expId=str(run.get("expId"));
            if(Set.of("TENS","COMP").contains(expId))expId=ExperimentCatalog.COMBINED;
            if(!ExperimentCatalog.DISPLAY_IDS.contains(expId))continue;
            Map<String,Object> cell=new LinkedHashMap<>();boolean archived=Boolean.TRUE.equals(run.get("archived")),published=archived&&!Boolean.TRUE.equals(run.get("classOpen"));
            cell.put("runId",row.get("id"));cell.put("taskId",run.get("taskId"));cell.put("groupName",run.get("groupName"));cell.put("attempt",run.getOrDefault("attempt",1));cell.put("timeText",run.get("timeText"));
            cell.put("operationScore",published?operationScore(run):null);
            Map<String,Object> report=latestReports.get(row.get("id")+"|"+row.get("sid"));
            cell.put("reportScore",report==null?null:report.get("finalScore"));
            cell.put("reportSubmitted",report!=null);cell.put("published",published);
            cell.put("status",!archived?"实验进行中":!published?"待教师确认":report==null?"待交报告":report.get("finalScore")==null?"报告待批阅":"已完成");
            cell.put("sortKey",str(row.get("created_at")));
            @SuppressWarnings("unchecked") Map<String,Object> scores=(Map<String,Object>)student.get("scores");
            Map<String,Object> current=map(scores.get(expId));
            if(str(current.get("sortKey")).compareTo(str(cell.get("sortKey")))<=0)scores.put(expId,cell);
        }
        for(var student:students.values())for(Object value:map(student.get("scores")).values())map(value).remove("sortKey");
        Map<String,Object> result=new LinkedHashMap<>();result.put("experiments",experiments);result.put("students",new ArrayList<>(students.values()));return result;
    }
    public List<Map<String,Object>> roster(String taskId,Actor a) {
        teacher(a);List<Map<String,Object>> out=new ArrayList<>();
        for(var row:runs.jdbc().queryForList("SELECT id FROM experiment_runs WHERE task_id=? ORDER BY created_at,group_id",taskId)) {
            var r=runs.detail(str(row.get("id")),a);
            for(var member:list(r.get("members"))) {
                var item=new LinkedHashMap<>(member);
                if(str(item.get("cls")).isBlank()) {
                    var students=runs.jdbc().queryForList("SELECT cls FROM students WHERE sid=?",member.get("sid"));
                    if(!students.isEmpty())item.put("cls",students.get(0).get("cls"));
                }
                item.put("runId",r.get("id"));item.put("groupName",r.get("groupName"));item.put("attempt",r.get("attempt"));
                var reports=list(r.get("reports")).stream().filter(p->Objects.equals(member.get("sid"),p.get("sid"))).sorted(Comparator.comparingInt(p->-((Number)p.get("version")).intValue())).toList();
                item.put("reports",reports);item.put("latest",reports.isEmpty()?null:reports.get(0));out.add(item);
            }
        }
        return out;
    }
    private Map<String,Object> report(String id,Actor a) {
        teacher(a);var rows=runs.jdbc().queryForList("SELECT * FROM report_versions WHERE id=?",id);
        if(rows.isEmpty())throw new ApiException(404,"报告不存在");
        var row=rows.get(0);var p=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());
        for(String key:List.of("id","sid","version","run_id","submitted_at"))p.put(key,row.get(key));p.put("_sourcePayload",row.get("payload"));return p;
    }
    private String exclusions(Actor a) {
        var settings=teaching.settings();String text=str(settings.get("templateExclusions"));
        var template=map(settings.get("template"));
        if(template.get("id")!=null)text+="\n"+str(runs.getFile(str(template.get("id")),a).get("extracted"));
        return text;
    }
    public Map<String,Object> compare(String id,String otherId,Actor a) {
        var left=report(id,a);var right=report(otherId,a);
        var lr=runs.load(str(left.get("run_id")),a,false);var rr=runs.load(str(right.get("run_id")),a,false);
        if(!Objects.equals(lr.get("taskId"),rr.get("taskId"))||Objects.equals(left.get("sid"),right.get("sid")))throw new ApiException(400,"请选择本课次另一名学生的报告");
        var lf=runs.getFile(str(left.get("fileId")),a);var rf=runs.getFile(str(right.get("fileId")),a);
        String lt=str(lf.get("extracted")),rt=str(rf.get("extracted"));
        Map<String,Object> result=new LinkedHashMap<>();
        if(!"ready".equals(lf.get("parse_status"))||!"ready".equals(rf.get("parse_status"))) {
            result.put("available",false);result.put("reason","报告未完整解析，无法计算相似度");result.put("evidence",List.of());
        } else result.putAll(ReportSimilarity.compare(lt,rt,exclusions(a)));
        result.put("sameGroup",Objects.equals(lr.get("groupId"),rr.get("groupId")));
        left.remove("_sourcePayload");right.remove("_sourcePayload");
        result.put("left",left);result.put("right",right);result.put("leftText",lt);result.put("rightText",rt);
        List<Map<String,Object>> segments=new ArrayList<>();
        for(Object evidence:(List<?>)result.getOrDefault("evidence",List.of())) {
            int[] x=locate(lt,str(evidence)),y=locate(rt,str(evidence));
            if(x!=null&&y!=null)segments.add(Map.of("leftStart",x[0],"leftEnd",x[1],"rightStart",y[0],"rightEnd",y[1],"text",lt.substring(x[0],x[1]),"length",str(evidence).length()));
        }
        result.put("segments",segments);return result;
    }
    static int[] locate(String text,String needle) {
        StringBuilder clean=new StringBuilder();List<Integer> positions=new ArrayList<>();
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN||(c>='a'&&c<='z')||(c>='A'&&c<='Z')){clean.append(Character.toLowerCase(c));positions.add(i);}}
        if(needle.isEmpty())return null;int start=clean.indexOf(needle);if(start<0)return null;
        return new int[]{positions.get(start),positions.get(start+needle.length()-1)+1};
    }
    private Map<String,Object> dataConsistency(Map<String,Object> report,Map<String,Object> run,String text) {
        Map<String,Object> snapshot=map(report.get("runSnapshot"));
        boolean linked=str(snapshot.get("runId")).isBlank()||Objects.equals(str(snapshot.get("runId")),str(run.get("id")));
        List<Map<String,Object>> evidence=new ArrayList<>();
        for(var row:runs.jdbc().queryForList("SELECT id,payload FROM experiment_runs WHERE task_id=? AND id<>?",run.get("taskId"),run.get("id"))) {
            Map<String,Object> other=JsonUtil.readMap(str(row.get("payload")),new LinkedHashMap<>());
            if(Objects.equals(str(other.get("groupId")),str(run.get("groupId"))))continue;
            LinkedHashSet<String> identifiers=new LinkedHashSet<>();identifiers.add(str(row.get("id")));identifiers.add(str(other.get("specimenId")));
            for(Object trial:map(other.get("trials")).values())identifiers.add(str(map(trial).get("specimenId")));
            for(String identifier:identifiers)if(identifier.length()>=4&&text.contains(identifier)) {
                evidence.add(Map.of("runId",row.get("id"),"groupName",str(other.get("groupName")),"identifier",identifier,"note","正文出现其他组实验记录标识"));break;
            }
        }
        boolean sameGroup=linked&&evidence.isEmpty();
        Map<String,Object> result=new LinkedHashMap<>();result.put("sameGroupData",sameGroup);result.put("status",sameGroup?"matched":"review");result.put("evidence",evidence);
        result.put("message",sameGroup?"提交记录与本组实验数据关联一致":"发现其他组记录标识，需要教师核对数据来源");return result;
    }
    private Map<String,Object> writingAssessment(String text,Map<String,Object> run) {
        String compact=text.replaceAll("\\s+","");int chars=0;
        for(int i=0;i<compact.length();i++){char c=compact.charAt(i);if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN||Character.isLetter(c))chars++;}
        boolean result=containsAny(compact,"数据","曲线","载荷","位移","应力","应变","结果");
        boolean analysis=containsAny(compact,"分析","误差","原因","讨论","比较","差异");
        boolean conclusion=containsAny(compact,"结论","综上","表明","可知","说明");
        List<String> findings=new ArrayList<>();
        if(chars<120)findings.add("有效正文偏少");if(!result)findings.add("缺少实验数据或结果讨论");if(!analysis)findings.add("缺少分析或误差讨论");if(!conclusion)findings.add("缺少明确结论");
        if(ExperimentCatalog.COMBINED.equals(run.get("expId"))) {
            long covered=ExperimentCatalog.NAMES.values().stream().filter(compact::contains).count();if(covered<2)findings.add("未分别讨论拉伸压缩子实验");
        }
        boolean reasonable=findings.isEmpty();Map<String,Object> assessment=new LinkedHashMap<>();assessment.put("reasonable",reasonable);assessment.put("charCount",chars);assessment.put("findings",findings);
        assessment.put("message",reasonable?"报告包含结果、分析和结论":"报告结构或论述需要教师复核");return assessment;
    }
    private static boolean containsAny(String text,String... words) {for(String word:words)if(text.contains(word))return true;return false;}
    @Transactional
    public Object check(String id,Actor a) {
        var p=report(id,a);var r=runs.load(str(p.get("run_id")),a,true);p=report(id,a);Object original=p.remove("_sourcePayload");
        var file=runs.getFile(str(p.get("fileId")),a);String reportText=str(file.get("extracted"));
        Map<String,Object> dataConsistency=dataConsistency(p,r,reportText);Map<String,Object> writingAssessment=writingAssessment(reportText,r);
        var others=runs.jdbc().queryForList("SELECT p.id,p.sid,p.run_id FROM report_versions p JOIN experiment_runs r ON r.id=p.run_id WHERE r.task_id=? AND p.sid<>? AND p.version=(SELECT MAX(v.version) FROM report_versions v WHERE v.run_id=p.run_id AND v.sid=p.sid) ORDER BY p.submitted_at",r.get("taskId"),p.get("sid"));
        List<Map<String,Object>> matches=new ArrayList<>();
        for(var other:others) {
            var comparison=compare(id,str(other.get("id")),a);
            Map<String,Object> match=new LinkedHashMap<>();
            for(String key:List.of("available","percent","reason","sameGroup"))if(comparison.containsKey(key))match.put(key,comparison.get(key));
            match.put("reportId",other.get("id"));match.put("sid",other.get("sid"));matches.add(match);
        }
        var valid=matches.stream().filter(m->Boolean.TRUE.equals(m.get("available"))).toList();
        Map<String,Object> similarity=new LinkedHashMap<>();similarity.put("available",!valid.isEmpty());similarity.put("matches",matches);similarity.put("at",now());
        double max=!valid.isEmpty()?valid.stream().mapToDouble(m->((Number)m.get("percent")).doubleValue()).max().orElse(0):0;
        if(!valid.isEmpty())similarity.put("maxPercent",max);
        else similarity.put("reason",others.isEmpty()?"暂无其他学生报告":"有效文本不足或报告未完整解析");
        double threshold=score(teaching.settings().get("similarityThreshold"));boolean flagged=max>=threshold;
        similarity.put("scope","task");similarity.put("threshold",threshold);similarity.put("flagged",flagged);similarity.put("dataConsistency",dataConsistency);similarity.put("writingAssessment",writingAssessment);
        String decision=!Boolean.TRUE.equals(dataConsistency.get("sameGroupData"))?"数据来源需要复核":flagged?"文本相似度偏高，需要对照原文复核":!Boolean.TRUE.equals(writingAssessment.get("reasonable"))?"撰写内容需要复核":"未见明显异常";
        similarity.put("decision",decision);p.put("similarity",similarity);p.put("foreignDataEvidence",dataConsistency.get("evidence"));
        for(String key:List.of("id","sid","version","run_id","submitted_at"))p.remove(key);
        if(runs.jdbc().update("UPDATE report_versions SET payload=? WHERE id=? AND payload=?",JsonUtil.write(p),id,original)!=1)throw new ApiException(409,"报告已更新，请重新查重");
        return similarity;
    }
    @Transactional
    public void begin(String id,Actor a) {
        var p=report(id,a);runs.load(str(p.get("run_id")),a,true);p=report(id,a);Object original=p.remove("_sourcePayload");if(p.get("finalScore")!=null||Boolean.TRUE.equals(p.get("returned")))return;
        p.put("reviewStatus","reviewing");p.put("reviewer",a.id());
        for(String key:List.of("id","sid","version","run_id","submitted_at"))p.remove(key);
        if(runs.jdbc().update("UPDATE report_versions SET payload=? WHERE id=? AND payload=?",JsonUtil.write(p),id,original)!=1)throw new ApiException(409,"报告已更新，请重新打开");
    }
    public byte[] gradedReport(String id,Actor a) {
        var p=report(id,a);if(p.get("finalScore")==null)throw new ApiException(409,"报告尚未评分");
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("实验报告批阅记录");
            for(String text:List.of("学号："+p.get("sid"),"原报告："+p.get("fileName"),"成绩："+p.get("finalScore"),"批阅教师："+p.get("gradedBy"),"批阅时间："+p.get("gradedAt"),"评语："+p.get("finalComment")))doc.createParagraph().createRun().setText(text);
            doc.createParagraph().createRun().setText("报告正文（提取文本）");
            for(String line:str(runs.getFile(str(p.get("fileId")),a).get("extracted")).split("\\R"))doc.createParagraph().createRun().setText(line);
            doc.write(out);return out.toByteArray();
        }catch(java.io.IOException e){throw new ApiException(500,"批阅记录导出失败");}
    }
}
