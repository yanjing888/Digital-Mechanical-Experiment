package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class RosterAssignmentService {
    private final JdbcTemplate db;
    private final StoreService store;
    private final RunService runs;
    public RosterAssignmentService(JdbcTemplate db, StoreService store, RunService runs) {
        this.db=db; this.store=store; this.runs=runs;
    }

    @Transactional
    public Map<String,Object> assign(Map<String,Object> body, String teacher) {
        List<String> trials=runs.validateTrials(body.get("requiredTrials"));
        boolean teacherScoreConfirmationRequired=runs.validateTeacherScoreConfirmation(body.get("teacherScoreConfirmationRequired"));
        if (!ExperimentCatalog.IDS.contains(RunService.str(body.get("expId")))) throw new ApiException(400,"实验项目无效");
        Object value=body.get("studentGroups");
        if (!(value instanceof List<?> groups) || groups.isEmpty() || groups.size()>500) throw new ApiException(400,"请选择学生并完成分组");
        Set<String> scope=new HashSet<>(store.importedStudentIds(teacher));
        Set<String> selected=new HashSet<>();
        List<List<String>> members=new ArrayList<>();
        for (Object item:groups) {
            if (!(item instanceof List<?> raw) || raw.isEmpty() || raw.size()>50) throw new ApiException(400,"每组须包含 1 至 50 名学生");
            List<String> ids=raw.stream().map(RunService::str).sorted().toList();
            for (String sid:ids) {
                if (!scope.contains(sid)) throw new ApiException(403,"只能安排本人名单中的学生");
                if (!selected.add(sid)) throw new ApiException(400,"同一学生不能重复分组");
            }
            members.add(ids);
        }
        // 按学号顺序加锁，旧课次先固化成员快照，再更新学生的当前小组。
        Set<String> previousGroups=new LinkedHashSet<>();
        for (String sid:selected.stream().sorted().toList()) {
            var rows=db.queryForList("SELECT group_id FROM students WHERE sid=? FOR UPDATE",sid);
            if (rows.isEmpty()) throw new ApiException(400,"学生已不存在，请刷新名单");
            String group=RunService.str(rows.get(0).get("group_id"));
            if (!group.isBlank()) previousGroups.add(group);
        }
        runs.preserveAssignments(new ArrayList<>(previousGroups));
        List<String> groupIds=new ArrayList<>();
        for (int i=0;i<members.size();i++) {
            String id=UUID.randomUUID().toString().replace("-", "");
            db.update("INSERT INTO lab_groups(id,name) VALUES(?,?)",id,"第"+(i+1)+"组");
            for(String sid:members.get(i)) db.update("UPDATE students SET group_id=? WHERE sid=?",id,sid);
            groupIds.add(id);
        }
        Map<String,Object> task=store.createTask(RunService.str(body.get("expId")),groupIds,
                RunService.str(body.get("place")),RunService.str(body.get("timeText")),
                RunService.str(body.get("note")),RunService.str(body.get("tip")),teacher);
        String taskId=RunService.str(task.get("id"));
        runs.scheduleTask(taskId,trials,teacherScoreConfirmationRequired);
        runs.provision(taskId);
        task.put("lessonState",runs.schedule.openIfDue(taskId)?"active":"scheduled");
        task.put("teacherScoreConfirmationRequired",teacherScoreConfirmationRequired);
        return Map.of("task",task,"tasks",store.listTasks());
    }
}
