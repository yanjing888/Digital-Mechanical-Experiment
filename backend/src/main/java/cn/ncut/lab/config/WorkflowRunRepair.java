package cn.ncut.lab.config;

import cn.ncut.lab.service.RunService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时补全 experiment_runs / run_members，避免已下发任务但学生端无记录。 */
@Component
@Order(100)
public class WorkflowRunRepair implements CommandLineRunner {
    private final RunService runs;
    public WorkflowRunRepair(RunService runs) { this.runs = runs; }
    @Override
    public void run(String... args) { runs.repairAllStudentRuns(); }
}
