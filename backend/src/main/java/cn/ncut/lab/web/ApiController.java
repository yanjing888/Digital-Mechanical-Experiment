package cn.ncut.lab.web;

import cn.ncut.lab.service.DifyService;
import cn.ncut.lab.service.FractureService;
import cn.ncut.lab.service.RosterService;
import cn.ncut.lab.service.StoreService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StoreService store;
    private final DifyService dify;
    private final FractureService fracture;
    private final RosterService roster;

    public ApiController(StoreService store, DifyService dify, FractureService fracture, RosterService roster) {
        this.store = store;
        this.dify = dify;
        this.fracture = fracture;
        this.roster = roster;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return store.listMeta();
    }

    @GetMapping("/assistant/status")
    public Map<String, Object> assistantStatus() {
        return dify.getStatus();
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return store.loginWithPassword(str(body.get("account")), str(body.get("password")));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        store.deleteSession(Sessions.tokenFrom(req));
        return Map.of("ok", true);
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
        Map<String, Object> snap = store.sessionSnapshot(Sessions.tokenFrom(req));
        if (snap == null) throw new ApiException(401, "会话无效");
        return snap;
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(HttpServletRequest req) {
        Sessions.requireTeacher(req);
        return Map.of("tasks", store.listTasks());
    }

    @GetMapping("/roster/template")
    public ResponseEntity<byte[]> rosterTemplate(HttpServletRequest req) {
        Sessions.requireTeacher(req);
        byte[] buf = roster.buildTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"student-roster-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(buf);
    }

    @GetMapping("/roster/students")
    public Map<String, Object> rosterStudents(HttpServletRequest req) {
        Sessions.requireTeacher(req);
        List<Map<String, Object>> students = store.listStudents();
        List<Map<String, Object>> ungrouped = new ArrayList<>();
        for (Map<String, Object> s : students) if (s.get("groupId") == null) ungrouped.add(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", students);
        out.put("groups", store.listGroups());
        out.put("ungrouped", ungrouped);
        return out;
    }

    @PostMapping("/roster/upload")
    public Map<String, Object> rosterUpload(HttpServletRequest req, @RequestParam("file") MultipartFile file) {
        Sessions.requireTeacher(req);
        if (file == null || file.isEmpty()) throw new ApiException(400, "请上传学生名单文件");
        try {
            return store.importRoster(roster.parse(file.getInputStream()));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "无法解析表格，请使用下载的模板");
        }
    }

    @PostMapping("/groups")
    public Map<String, Object> createGroup(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        return store.createGroup(str(body.get("name")));
    }

    @PatchMapping("/groups/{id}")
    public Map<String, Object> patchGroup(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        if (body.get("name") != null) return store.renameGroup(id, str(body.get("name")));
        if (body.get("memberSids") != null) return store.setGroupMembers(id, strList(body.get("memberSids")));
        throw new ApiException(400, "无有效更新内容");
    }

    @PostMapping("/groups/{id}/members")
    public Map<String, Object> moveMembers(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        return store.moveStudentsToGroup(id, strList(body.get("memberSids")));
    }

    @PostMapping("/groups/auto")
    public Map<String, Object> autoGroup(HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        Sessions.requireTeacher(req);
        if (body == null) body = Map.of();
        Integer size = body.get("size") == null ? null : toInt(body.get("size"));
        Integer count = body.get("count") == null ? null : toInt(body.get("count"));
        return store.autoGroup(size, count, str(body.get("mode")), str(body.get("prefix")));
    }

    @PostMapping("/groups/ungroup")
    public Map<String, Object> ungroup(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        return store.ungroupStudents(strList(body.get("memberSids")));
    }

    @DeleteMapping("/groups/{id}")
    public Map<String, Object> deleteGroup(HttpServletRequest req, @PathVariable String id) {
        Sessions.requireTeacher(req);
        return store.deleteGroup(id);
    }

    @PostMapping("/tasks")
    public Map<String, Object> createTask(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        Map<String, Object> task = store.createTask(
                str(body.get("expId")),
                strList(body.get("groupIds")),
                str(body.get("place")),
                str(body.get("timeText")),
                str(body.get("note")),
                str(body.get("tip")),
                Sessions.teacherName(req)
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("tasks", store.listTasks());
        return out;
    }

    @PostMapping("/lab/start")
    public Map<String, Object> labStart(HttpServletRequest req) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        String st = s == null ? "" : str(s.get("status"));
        if (s == null || !Set.of("assigned", "in_lab", "lab_done").contains(st)) {
            throw new ApiException(400, "当前无法开始实验");
        }
        Map<String, Object> aligned = store.alignStudentWithGroupLab(sid);
        if (aligned != null && !"assigned".equals(str(aligned.get("status")))) {
            return Map.of("student", aligned);
        }
        if (!"assigned".equals(st)) throw new ApiException(400, "当前无法开始实验");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "in_lab");
        patch.put("expFlow", "wait_door");
        patch.put("doorClosed", false);
        patch.put("acqPaused", false);
        Map<String, Object> emptyAcq = new LinkedHashMap<>();
        emptyAcq.put("points", List.of());
        emptyAcq.put("t0", null);
        patch.put("acq", emptyAcq);
        patch.put("maxF", 0);
        patch.put("maxD", 0);
        patch.put("fractureImg", null);
        patch.put("fractureType", null);
        patch.put("fractureConf", 0);
        patch.put("fractureAnalysis", null);
        patch.put("groupConfirmed", false);
        return Map.of("student", store.saveLabProgressForGroup(sid, patch));
    }

    @PostMapping("/lab/door")
    public Map<String, Object> labDoor(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return Map.of("student", store.saveLabProgressForGroup(Sessions.studentId(req), Map.of("doorClosed", truthy(body.get("closed")))));
    }

    @PostMapping("/lab/start-machine")
    public Map<String, Object> labStartMachine(HttpServletRequest req) {
        String sid = Sessions.studentId(req);
        store.alignStudentWithGroupLab(sid);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || !"in_lab".equals(s.get("status")) || !"wait_door".equals(s.get("expFlow"))) {
            throw new ApiException(400, "当前阶段无法启动");
        }
        if (!truthy(s.get("doorClosed"))) throw new ApiException(400, "请先确认安全门已关闭");
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expFlow", "acquiring");
        patch.put("acqPaused", false);
        patch.put("acq", Map.of("points", List.of(), "t0", System.currentTimeMillis()));
        patch.put("maxF", 0);
        patch.put("maxD", 0);
        return Map.of("student", store.saveLabProgressForGroup(sid, patch));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/lab/acq")
    public Map<String, Object> labAcq(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || !"acquiring".equals(s.get("expFlow"))) throw new ApiException(400, "未在采集中");
        Map<String, Object> acq = s.get("acq") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Object> points = body.get("points") instanceof List<?> l ? (List<Object>) l : (List<Object>) acq.getOrDefault("points", List.of());
        double maxF = body.get("maxF") != null ? toDouble(body.get("maxF")) : toDouble(s.get("maxF"));
        double maxD = body.get("maxD") != null ? toDouble(body.get("maxD")) : toDouble(s.get("maxD"));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("acq", Map.of("points", points, "t0", acq.get("t0") != null ? acq.get("t0") : System.currentTimeMillis()));
        patch.put("maxF", maxF);
        patch.put("maxD", maxD);
        patch.put("acqPaused", truthy(body.get("paused")));
        return Map.of("student", store.saveLabProgressForGroup(sid, patch));
    }

    @PostMapping("/lab/pause")
    public Map<String, Object> labPause(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return Map.of("student", store.saveLabProgressForGroup(Sessions.studentId(req), Map.of("acqPaused", truthy(body.get("paused")))));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/lab/rupture")
    public Map<String, Object> labRupture(HttpServletRequest req, @RequestBody(required = false) Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || !"acquiring".equals(s.get("expFlow"))) throw new ApiException(400, "未在采集中");
        if (body == null) body = Map.of();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expFlow", "post_break");
        patch.put("acqPaused", true);
        if (body.get("points") != null) {
            Map<String, Object> acq = s.get("acq") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            patch.put("acq", Map.of("points", body.get("points"), "t0", acq.get("t0") != null ? acq.get("t0") : System.currentTimeMillis()));
            patch.put("maxF", body.get("maxF") != null ? toDouble(body.get("maxF")) : toDouble(s.get("maxF")));
            patch.put("maxD", body.get("maxD") != null ? toDouble(body.get("maxD")) : toDouble(s.get("maxD")));
        }
        return Map.of("student", store.saveLabProgressForGroup(sid, patch));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/lab/fracture/analyze")
    public Map<String, Object> labFractureAnalyze(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || (!"post_break".equals(s.get("expFlow")) && !"done_viz".equals(s.get("expFlow")))) {
            throw new ApiException(400, "当前阶段无法分析断口");
        }
        String image = firstNonBlank(str(body.get("imageBase64")), str(body.get("fractureImg")));
        List<Object> points = body.get("points") instanceof List<?> l && !l.isEmpty()
                ? (List<Object>) l
                : (s.get("acq") instanceof Map<?, ?> acq && acq.get("points") instanceof List<?> lp ? (List<Object>) lp : List.of());
        return Map.of("analysis", fracture.analyze(image, points));
    }

    @PostMapping("/lab/fracture")
    public Map<String, Object> labFracture(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || (!"post_break".equals(s.get("expFlow")) && !"done_viz".equals(s.get("expFlow")))) {
            throw new ApiException(400, "当前阶段无法更新断口");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body.containsKey("fractureImg")) patch.put("fractureImg", body.get("fractureImg"));
        if (body.containsKey("fractureType")) patch.put("fractureType", body.get("fractureType"));
        if (body.containsKey("fractureConf")) patch.put("fractureConf", toDouble(body.get("fractureConf")));
        if (body.containsKey("fractureAnalysis")) patch.put("fractureAnalysis", body.get("fractureAnalysis"));
        if ("post_break".equals(s.get("expFlow")) && patch.get("fractureImg") != null) patch.put("expFlow", "done_viz");
        return Map.of("student", store.saveLabProgressForGroup(sid, patch));
    }

    @PostMapping("/lab/fracture/self")
    public Map<String, Object> labFractureSelf(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || (!"post_break".equals(s.get("expFlow")) && !"done_viz".equals(s.get("expFlow")))) {
            throw new ApiException(400, "当前阶段无法提交断口观察");
        }
        String judgment = str(body.get("judgment"));
        if (!"ductile".equals(judgment) && !"brittle".equals(judgment)) judgment = null;
        Map<String, Object> fractureSelf = new LinkedHashMap<>();
        fractureSelf.put("features", str(body.get("features")));
        fractureSelf.put("judgment", judgment);
        fractureSelf.put("updatedAt", nowLocal());
        return Map.of("student", store.saveStudentFields(sid, Map.of("fractureSelf", fractureSelf)));
    }

    @PostMapping("/lab/finish")
    public Map<String, Object> labFinish(HttpServletRequest req) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || !"done_viz".equals(s.get("expFlow"))) throw new ApiException(400, "请先完成断口确认");
        Map<String, Object> groupReport = store.syncGroupDataFromStudent(sid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", store.getStudent(sid));
        out.put("groupReport", groupReport);
        return out;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/reports/group/confirm")
    public Map<String, Object> confirmGroup(HttpServletRequest req) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || s.get("taskId") == null) throw new ApiException(400, "无任务");
        Map<String, Object> gr = store.ensureGroupReport(str(s.get("taskId")), str(s.get("groupId")));
        List<Object> confirmed = gr.get("confirmedBy") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        if (!confirmed.contains(sid)) confirmed.add(sid);
        gr.put("confirmedBy", confirmed);
        store.saveGroupReport(gr);
        store.saveStudentFields(sid, Map.of("groupConfirmed", true));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", store.getStudent(sid));
        out.put("groupReport", store.ensureGroupReport(str(s.get("taskId")), str(s.get("groupId"))));
        return out;
    }

    @PostMapping("/reports/group/submit")
    public Map<String, Object> submitGroup(HttpServletRequest req) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null || s.get("taskId") == null) throw new ApiException(400, "无任务");
        Map<String, Object> gr = store.ensureGroupReport(str(s.get("taskId")), str(s.get("groupId")));
        List<?> confirmed = gr.get("confirmedBy") instanceof List<?> l ? l : List.of();
        if (confirmed.isEmpty()) throw new ApiException(400, "请先由一名组员确认组报告");
        if (gr.get("data") == null) throw new ApiException(400, "尚无组实验数据");
        gr.put("submitted", true);
        gr.put("submittedAt", nowLocal());
        store.saveGroupReport(gr);
        return Map.of("groupReport", store.ensureGroupReport(str(s.get("taskId")), str(s.get("groupId"))));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/reports/personal/save")
    public Map<String, Object> savePersonal(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null) throw new ApiException(400, "学生不存在");
        if (truthy(s.get("personalSubmitted"))) throw new ApiException(400, "已提交，无法修改");
        Map<String, Object> old = s.get("personal") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        Map<String, Object> personal = new LinkedHashMap<>(old);
        personal.put("steps", body.get("steps") != null ? body.get("steps") : old.getOrDefault("steps", ""));
        personal.put("analysis", body.get("analysis") != null ? body.get("analysis") : old.getOrDefault("analysis", ""));
        personal.put("reflection", body.get("reflection") != null ? body.get("reflection") : old.getOrDefault("reflection", ""));
        personal.put("fileName", body.get("fileName") != null ? body.get("fileName") : old.getOrDefault("fileName", ""));
        personal.put("fileDataUrl", body.get("fileDataUrl") != null ? body.get("fileDataUrl") : old.getOrDefault("fileDataUrl", ""));
        return Map.of("student", store.saveStudentFields(sid, Map.of("personal", personal)));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/reports/personal/submit")
    public Map<String, Object> submitPersonal(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        Map<String, Object> s = store.getStudent(sid);
        if (s == null) throw new ApiException(400, "学生不存在");
        if (truthy(s.get("personalSubmitted"))) throw new ApiException(400, "已提交");
        Map<String, Object> old = s.get("personal") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        Map<String, Object> personal = new LinkedHashMap<>(old);
        personal.put("steps", body.get("steps") != null ? body.get("steps") : "");
        personal.put("analysis", body.get("analysis") != null ? body.get("analysis") : "");
        personal.put("reflection", body.get("reflection") != null ? body.get("reflection") : "");
        personal.put("fileName", body.get("fileName") != null ? body.get("fileName") : old.getOrDefault("fileName", ""));
        personal.put("fileDataUrl", body.get("fileDataUrl") != null ? body.get("fileDataUrl") : old.getOrDefault("fileDataUrl", ""));
        if (plainEmpty(str(personal.get("steps"))) && plainEmpty(str(personal.get("analysis"))) && str(personal.get("fileName")).isBlank()) {
            throw new ApiException(400, "请至少填写部分内容或上传附件");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("personal", personal);
        patch.put("personalSubmitted", true);
        patch.put("personalSubmittedAt", nowLocal());
        patch.put("status", "submitted");
        return Map.of("student", store.saveStudentFields(sid, patch));
    }

    @GetMapping("/grading")
    public Map<String, Object> grading(HttpServletRequest req) {
        Sessions.requireTeacher(req);
        return Map.of("items", store.gradingQueue());
    }

    @GetMapping("/grading/deduction-catalog")
    public Map<String, Object> deductionCatalog(HttpServletRequest req) {
        Sessions.requireTeacher(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog", store.deductionCatalog());
        out.put("baseScore", StoreService.OPERATION_BASE_SCORE);
        return out;
    }

    @PostMapping("/grading/{sid}/deduction")
    public Map<String, Object> addDeduction(HttpServletRequest req, @PathVariable String sid, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || student.get("taskId") == null) throw new ApiException(404, "无此学生任务");
        Integer pts = body.get("points") == null ? null : toInt(body.get("points"));
        Map<String, Object> gr = store.addDeduction(
                str(student.get("taskId")), str(student.get("groupId")),
                str(body.get("itemId")), str(body.get("label")), pts, str(body.get("reason")),
                Sessions.teacherName(req));
        return Map.of("groupReport", gr);
    }

    @DeleteMapping("/grading/{sid}/deduction/{did}")
    public Map<String, Object> removeDeduction(HttpServletRequest req, @PathVariable String sid, @PathVariable String did) {
        Sessions.requireTeacher(req);
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || student.get("taskId") == null) throw new ApiException(404, "无此学生任务");
        return Map.of("groupReport", store.removeDeduction(str(student.get("taskId")), str(student.get("groupId")), did));
    }

    @PostMapping("/grading/{sid}/material")
    public Map<String, Object> setMaterial(HttpServletRequest req, @PathVariable String sid, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || student.get("taskId") == null) throw new ApiException(404, "无此学生任务");
        String mt = str(body.get("materialType"));
        if (!"ductile".equals(mt) && !"brittle".equals(mt)) mt = null;
        return Map.of("groupReport", store.setMaterialType(str(student.get("taskId")), str(student.get("groupId")), mt));
    }

    @GetMapping("/grading/{sid}")
    public Map<String, Object> gradingDetail(HttpServletRequest req, @PathVariable String sid) {
        Sessions.requireTeacher(req);
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || "none".equals(student.get("status"))) throw new ApiException(404, "无此学生任务");
        Map<String, Object> groupReport = student.get("taskId") != null
                ? store.ensureGroupReport(str(student.get("taskId")), str(student.get("groupId"))) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", student);
        out.put("groupReport", groupReport);
        return out;
    }

    @PostMapping("/grading/{sid}")
    public Map<String, Object> gradingSave(HttpServletRequest req, @PathVariable String sid, @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || student.get("taskId") == null) throw new ApiException(404, "无此学生任务");
        Map<String, Object> gr = store.ensureGroupReport(str(student.get("taskId")), str(student.get("groupId")));
        if (body.get("groupScore") != null && truthy(gr.get("submitted"))) {
            gr.put("score", toDouble(body.get("groupScore")));
            if (body.get("groupComment") != null) gr.put("comment", str(body.get("groupComment")));
            store.saveGroupReport(gr);
        } else if (body.get("groupComment") != null) {
            gr.put("comment", str(body.get("groupComment")));
            store.saveGroupReport(gr);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body.get("personalScore") != null && truthy(student.get("personalSubmitted"))) {
            patch.put("personalScore", toDouble(body.get("personalScore")));
        }
        if (body.get("personalComment") != null && truthy(student.get("personalSubmitted"))) {
            patch.put("personalComment", str(body.get("personalComment")));
        }
        if (!patch.isEmpty()) store.saveStudentFields(sid, patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", store.getStudent(sid));
        out.put("groupReport", store.ensureGroupReport(str(student.get("taskId")), str(student.get("groupId"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/grading/{sid}/ai-review")
    public Map<String, Object> aiReview(HttpServletRequest req, @PathVariable String sid, @RequestBody(required = false) Map<String, Object> body) {
        Sessions.requireTeacher(req);
        if (body == null) body = Map.of();
        Map<String, Object> student = store.getStudent(sid);
        if (student == null || student.get("taskId") == null) throw new ApiException(404, "无此学生任务");
        String reportType = "personal".equals(body.get("reportType")) ? "personal" : "group";
        Map<String, Object> gr = store.ensureGroupReport(str(student.get("taskId")), str(student.get("groupId")));
        Map<String, Object> data = gr.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String reportText;
        if ("group".equals(reportType)) {
            if (!truthy(gr.get("submitted"))) throw new ApiException(400, "组报告尚未提交，无法 AI 评阅");
            reportText = str(gr.get("html"));
        } else {
            if (!truthy(student.get("personalSubmitted"))) throw new ApiException(400, "个人报告尚未提交，无法 AI 评阅");
            Map<String, Object> p = student.get("personal") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            reportText = String.join("\n\n", List.of(
                    "一、实验步骤描述\n" + str(p.get("steps")),
                    "二、数据分析与误差\n" + str(p.get("analysis")),
                    "三、总结与反思\n" + str(p.get("reflection")),
                    p.get("fileName") != null && !str(p.get("fileName")).isBlank() ? ("附件：" + p.get("fileName")) : ""
            ).stream().filter(x -> !x.isBlank()).toList());
        }
        Map<String, Object> acq = student.get("acq") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        int pointCount = data.get("points") instanceof List<?> lp ? lp.size()
                : (acq.get("points") instanceof List<?> ap ? ap.size() : 0);
        Map<String, Object> labData = new LinkedHashMap<>();
        labData.put("fMax", data.get("fMax") != null ? data.get("fMax") : student.get("maxF"));
        labData.put("dMax", data.get("dMax") != null ? data.get("dMax") : student.get("maxD"));
        labData.put("fractureType", data.get("fractureType") != null ? data.get("fractureType") : student.get("fractureType"));
        labData.put("fractureConf", data.get("fractureConf") != null ? data.get("fractureConf") : student.get("fractureConf"));
        labData.put("sourceSid", data.get("sourceSid"));
        labData.put("pointCount", pointCount);

        Map<String, Object> result = dify.gradeReport(
                reportType,
                str(student.get("name")),
                str(student.get("sid")),
                str(student.get("groupName")),
                str(student.get("expName")),
                reportText,
                labData,
                "teacher:" + Sessions.teacherName(req)
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportType", reportType);
        out.put("score", result.get("score"));
        out.put("comment", result.get("comment"));
        out.put("source", result.getOrDefault("source", "dify"));
        out.put("workflowRunId", result.get("workflowRunId"));
        return out;
    }

    @PostMapping("/assistant/chat")
    public Map<String, Object> assistantChat() {
        throw new ApiException(501, "对话能力请使用物小智本地助手；报告评阅请用 gradeReport 工作流");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(o);
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    private static boolean plainEmpty(String html) {
        String t = html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").trim();
        return t.isEmpty();
    }

    private static String nowLocal() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
