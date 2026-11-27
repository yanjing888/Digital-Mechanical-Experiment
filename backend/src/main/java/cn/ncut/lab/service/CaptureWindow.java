package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Both acquisition and the device experiment must fall within the lesson's five-hour window. */
record CaptureWindow(Instant start, Instant end, Instant now) {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static CaptureWindow of(Map<String,Object> run, Map<String,Object> task, Instant now) {
        Instant start, planned = null;
        if (!RunService.str(run.get("parentId")).isEmpty()) {
            start = TaskSchedule.startTime(run.get("createdAt")).orElse(null);
        } else {
            planned = TaskSchedule.startTime(task.getOrDefault("time_text", run.get("timeText"))).orElse(null);
            start = planned;
            Instant opened = TaskSchedule.startTime(run.get("startedAt")).orElse(null);
            if (opened != null && (start == null || !"automatic".equals(run.get("startMode")) && opened.isBefore(start))) start = opened;
            if (start == null) start = TaskSchedule.startTime(task.get("created_at")).orElse(null);
        }
        if (start == null) throw new ApiException(409, "本课次缺少有效实验时间，请联系教师安排开课时间后采集");
        start = start.truncatedTo(ChronoUnit.SECONDS);
        return new CaptureWindow(start, start.plus(Duration.ofHours(5)), now);
    }
    boolean open() { return !now.isBefore(start) && !now.isAfter(end); }
    void requireOpen() {
        if (now.isBefore(start)) throw new ApiException(409, "尚未到本课次开课时间，暂不能采集数据");
        if (now.isAfter(end)) throw new ApiException(409, "本课次5小时采集时间已结束，不能再采集或导入数据");
    }
    boolean accepts(Instant experimentAt) { return open() && !experimentAt.isBefore(start) && !experimentAt.isAfter(end) && !experimentAt.isAfter(now); }
    static Instant uploadedTime(String name) {
        String base=name==null?"":name.replace('\\','/');base=base.substring(base.lastIndexOf('/')+1);
        if(!base.matches("(?i)^\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.(csv|xlsx?|mdb)$"))throw new ApiException(400,"导入数据须保留设备试验时间命名，例如 2026-11-27-14-20-00.csv，不能使用无时间的历史文件");
        return WanceMdbReader.parseFileNameTime(base.substring(0,19)+".mdb").orElseThrow(()->new ApiException(400,"文件名中的试验时间无效"));
    }
    void require(Instant experimentAt) {
        requireOpen();
        if (!accepts(experimentAt)) throw new ApiException(409, "文件试验时间不在本课次有效范围内，请采集开课起5小时内生成的试验数据");
    }
}
