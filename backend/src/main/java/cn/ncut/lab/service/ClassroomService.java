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
    static void expose(Map<String,Object> r,Actor a) {
        r.put("scoreVersion",version(r));
        r.putIfAbsent("confirmationRequired",true);
        if(!a.teacher()) { r.put("myScoreConfirmed",confirmed(r,a.id()));r.put("myConfirmation",map(map(r.get("scoreConfirmations")).get(a.id()))); }
        r.remove("scoreConfirmations");r.remove("confirmationHistory");
    }
    @Transactional
    public Object confirm(String id,Actor a,Map<String,Object> b) {
        if(a.teacher())throw new ApiException(403,"请由学生本人确认");
        Map<String,Object> r=runs.load(id,a,true);
        if(Boolean.TRUE.equals(r.get("classOpen")))throw new ApiException(409,"教师尚未发布最终操作成绩");
        if("pending".equals(map(r.get("retakeRequest")).get("status")))throw new ApiException(409,"该组已申请重做，请等待教师处理");
        if(!str(version(r)).equals(str(b.get("scoreVersion"))))throw new ApiException(409,"操作成绩已变更，请核对后重新确认");
        String action=str(b.getOrDefault("action","confirm"));
        if(!Set.of("confirm","dispute").contains(action))throw new ApiException(400,"确认操作无效");
        Map<String,Object> item=new LinkedHashMap<>();item.put("sid",a.id());item.put("version",version(r));item.put("score",operationScore(r));item.put("at",now());
        item.put("status",action.equals("confirm")?"confirmed":"disputed");
        item.put("reason",action.equals("dispute")?required(b.get("reason"),"异议原因"):"");
        Map<String,Object> confirmations=map(r.get("scoreConfirmations"));confirmations.put(a.id(),item);r.put("scoreConfirmations",confirmations);
        List<Map<String,Object>> history=list(r.get("confirmationHistory"));history.add(item);r.put("confirmationHistory",history);
        runs.save(r,a,action.equals("confirm")?"确认操作成绩":"提交操作成绩异议");return runs.detail(id,a);
    }
}
