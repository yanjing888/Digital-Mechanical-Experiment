package cn.ncut.lab;

import cn.ncut.lab.service.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

@SpringBootTest
class Student2025010101IT {
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired RunService runs;

    @Test
    void diagnoseAndRepair() {
        String sid = "2025010101";
        List<Map<String, Object>> s = jdbc.queryForList(
                "SELECT sid,name,status,task_id,group_id,exp_name FROM students WHERE sid=?", sid);
        System.out.println("STUDENT_ROWS=" + s);
        if (s.isEmpty()) {
            System.out.println("DIAGNOSIS=student_not_in_database");
            return;
        }
        runs.repairAllStudentRuns();
        int members = jdbc.queryForObject("SELECT COUNT(*) FROM run_members WHERE sid=?", Integer.class, sid);
        int runsN = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_runs r JOIN run_members m ON m.run_id=r.id WHERE m.sid=?", Integer.class, sid);
        System.out.println("AFTER_REPAIR run_members=" + members + " experiment_runs_visible=" + runsN);
        System.out.println("LAB_STATUS=" + runs.studentLabStatus(new RunService.Actor(false, sid)));
        System.out.println("RUN_LIST_SIZE=" + runs.runs(new RunService.Actor(false, sid)).size());
    }
}
