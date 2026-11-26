package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.ncut.lab.service.RunService.*;

@Service
public class ClassroomService {
    private final RunService runs;
    public ClassroomService(RunService runs) { this.runs=runs; }

    static int version(Map<String,Object> r) { return ((Number)r.getOrDefault("scoreVersion",0)).intValue(); }
    static boolean teacherConfirmationRequired(Map<String,Object> r) {
        return !Boolean.FALSE.equals(r.get("teacherScoreConfirmationRequired"));
    }
    // Publish only when the student explicitly confirms/submits, so complete data may still be supplemented.
    static void publishAutomatically(Map<String,Object> r) {
        if(!Boolean.TRUE.equals(r.get("classOpen"))||teacherConfirmationRequired(r))return;
        if("scheduled".equals(r.get("lessonState")))throw new ApiException(409,"本课次尚未开始");
        if(!TrialRecords.complete(r))throw new ApiException(409,"请先完成本课次全部资料采集");
        r.put("classOpen",false);r.put("confirmationRequired",true);
        r.put("scorePublishedAt",now());r.put("scorePublicationMode","automatic");
    }
    static void scoreChanged(Map<String,Object> r) {
        r.put("scoreVersion",version(r)+1);
        r.put("confirmationRequired",true);
    }
    static boolean confirmed(Map<String,Object> r,String sid) {
        Map<String,Object> c=map(map(r.get("scoreConfirmations")).get(sid));
        return !Boolean.TRUE.equals(r.get("classOpen")) && "confirmed".equals(c.get("status"))
                && Objects.equals(c.get("version"),version(r))
                && Objects.equals(c.get("score"),operationScore(r));
    }
    static void recordConfirmation(Map<String,Object> r,String sid,String status,String reason) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("sid",sid);item.put("version",version(r));item.put("score",operationScore(r));item.put("at",now());
        item.put("status",status);item.put("reason",reason);
        Map<String,Object> confirmations=map(r.get("scoreConfirmations"));confirmations.put(sid,item);r.put("scoreConfirmations",confirmations);
        List<Map<String,Object>> history=list(r.get("confirmationHistory"));history.add(item);r.put("confirmationHistory",history);
    }
    static void expose(Map<String,Object> r,Actor a) {
        r.put("scoreVersion",version(r));
        r.put("teacherScoreConfirmationRequired",teacherConfirmationRequired(r));
        r.put("scoreReady",!"scheduled".equals(r.get("lessonState"))&&TrialRecords.complete(r)
                &&(!Boolean.TRUE.equals(r.get("classOpen"))||!teacherConfirmationRequired(r)));
        r.putIfAbsent("confirmationRequired",true);
        if(!a.teacher()) { r.put("myScoreConfirmed",confirmed(r,a.id()));r.put("myConfirmation",map(map(r.get("scoreConfirmations")).get(a.id()))); }
        r.remove("scoreConfirmations");r.remove("confirmationHistory");
    }
    @Transactional
    public Object confirm(String id,Actor a,Map<String,Object> b) {
        if(a.teacher())throw new ApiException(403,"请由学生本人确认");
        Map<String,Object> r=runs.load(id,a,true);
        if("scheduled".equals(r.get("lessonState")))throw new ApiException(409,"本课次尚未开始");
        if(Boolean.TRUE.equals(r.get("classOpen"))&&teacherConfirmationRequired(r))throw new ApiException(409,"教师尚未发布最终操作成绩");
        if("pending".equals(map(r.get("retakeRequest")).get("status")))throw new ApiException(409,"该组已申请重做，请等待教师处理");
        if("arranged".equals(map(r.get("retakeRequest")).get("status")))throw new ApiException(409,"教师已安排重做，请进入新的实验记录");
        if(!str(version(r)).equals(str(b.get("scoreVersion"))))throw new ApiException(409,"操作成绩已变更，请核对后重新确认");
        String action=str(b.getOrDefault("action","confirm"));
        if(!Set.of("confirm","dispute").contains(action))throw new ApiException(400,"确认操作无效");
        String reason=action.equals("dispute")?required(b.get("reason"),"异议原因"):"";
        publishAutomatically(r);
        recordConfirmation(r,a.id(),action.equals("confirm")?"confirmed":"disputed",reason);
        runs.save(r,a,action.equals("confirm")?"确认操作成绩":"提交操作成绩异议");return runs.detail(id,a);
    }
}
