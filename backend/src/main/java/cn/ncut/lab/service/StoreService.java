package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.util.PasswordUtil;
import cn.ncut.lab.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 业务核心，等价于 Node 版 server/services/store.js。
 * 使用 JdbcTemplate + Map，保证返回 JSON 结构与前端契约一致。
 */
@Service
public class StoreService {

    private final JdbcTemplate jdbc;

    @Value("${app.student-default-password:123456}")
    private String studentDefaultPassword;

    public StoreService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============ 扣分项目录（默认，最终以学校清单为准） ============
    public static final int OPERATION_BASE_SCORE = 100;

    public List<Map<String, Object>> deductionCatalog() {
        return List.of(
                ded("safety_major", "重大危险操作（危及人身/设备安全）", 40, "安全"),
                ded("safety_door", "安全门未关闭即尝试启动", 20, "安全"),
                ded("safety_op", "违规操作试验机", 15, "安全"),
                ded("no_ppe", "未按要求穿戴防护用品", 10, "安全"),
                ded("order_chaos", "小组秩序混乱", 10, "纪律"),
                ded("disobey", "不听从指挥", 10, "纪律"),
                ded("late", "迟到 / 早退", 5, "纪律"),
                ded("phone", "课堂玩手机 / 做无关事", 5, "纪律"),
                ded("env", "实验后未清理台面 / 归位", 5, "纪律")
        );
    }

    private Map<String, Object> ded(String id, String label, int points, String group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("points", points);
        m.put("group", group);
        return m;
    }

    private int computeOperationScore(List<Map<String, Object>> deductions) {
        int total = 0;
        if (deductions != null) {
            for (Map<String, Object> d : deductions) total += asInt(d.get("points"), 0);
        }
        return Math.max(0, OPERATION_BASE_SCORE - total);
    }

    // ============ 基础工具 ============
    private static final String ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RND = new SecureRandom();

    private String nano(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ID_ALPHABET.charAt(RND.nextInt(ID_ALPHABET.length())));
        return sb.toString();
    }

    private String nowIso() {
        return LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    }

    private String nowLocal() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    static String escHtml(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private String asStr(Object o, String def) { return o == null ? def : String.valueOf(o); }

    private double asDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private int asInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Math.round(Double.parseDouble(String.valueOf(o))); } catch (Exception e) { return def; }
    }

    private boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(o);
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    static double calcWork(List<Map<String, Object>> points) {
        double work = 0;
        if (points == null) return 0;
        for (int i = 1; i < points.size(); i++) {
            double d1 = num(points.get(i).get("d")), d0 = num(points.get(i - 1).get("d"));
            double f1 = num(points.get(i).get("f")), f0 = num(points.get(i - 1).get("f"));
            double dd = d1 - d0;
            if (dd > 0) work += 0.5 * (f1 + f0) * dd;
        }
        return work;
    }

    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    // ============ HTML 生成 ============
    @SuppressWarnings("unchecked")
    private String buildCurveSvg(List<Map<String, Object>> points) {
        List<Map<String, Object>> pts = new ArrayList<>();
        if (points != null) for (Map<String, Object> p : points) {
            if (p != null && p.get("d") != null && p.get("f") != null) pts.add(p);
        }
        if (pts.size() < 2) return "<p class=\"rpt-empty\">暂无力—位移曲线数据</p>";
        double W = 640, H = 280, padL = 52, padR = 18, padT = 18, padB = 42;
        double maxD = 1e-6, maxF = 1e-6;
        for (Map<String, Object> p : pts) { maxD = Math.max(maxD, num(p.get("d"))); maxF = Math.max(maxF, num(p.get("f"))); }
        final double fMaxD = maxD, fMaxF = maxF;
        StringBuilder poly = new StringBuilder();
        for (Map<String, Object> p : pts) {
            double x = padL + (num(p.get("d")) / fMaxD) * (W - padL - padR);
            double y = padT + (1 - num(p.get("f")) / fMaxF) * (H - padT - padB);
            poly.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
        }
        StringBuilder gridY = new StringBuilder();
        for (double t : new double[]{0.25, 0.5, 0.75, 1}) {
            double y = padT + (1 - t) * (H - padT - padB);
            gridY.append(String.format(Locale.US,
                    "<line x1=\"%.0f\" y1=\"%.1f\" x2=\"%.0f\" y2=\"%.1f\" stroke=\"#E8EEF2\" stroke-width=\"1\"/>"
                            + "<text x=\"%.0f\" y=\"%.1f\" text-anchor=\"end\" font-size=\"11\" fill=\"#64748b\">%.1f</text>",
                    padL, y, (W - padR), y, (padL - 8), (y + 4), (fMaxF * t)));
        }
        return "<div class=\"rpt-curve-wrap\">"
                + "<svg class=\"rpt-curve\" viewBox=\"0 0 640 280\" role=\"img\" aria-label=\"力—位移曲线\">"
                + "<rect x=\"0\" y=\"0\" width=\"640\" height=\"280\" fill=\"#fff\"/>"
                + gridY
                + String.format(Locale.US, "<line x1=\"%.0f\" y1=\"%.0f\" x2=\"%.0f\" y2=\"%.0f\" stroke=\"#94a3b8\" stroke-width=\"1.2\"/>", padL, padT, padL, (H - padB))
                + String.format(Locale.US, "<line x1=\"%.0f\" y1=\"%.0f\" x2=\"%.0f\" y2=\"%.0f\" stroke=\"#94a3b8\" stroke-width=\"1.2\"/>", padL, (H - padB), (W - padR), (H - padB))
                + "<polyline points=\"" + poly.toString().trim() + "\" fill=\"none\" stroke=\"#0E7C8B\" stroke-width=\"2.2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>"
                + "<text x=\"14\" y=\"16\" font-size=\"11\" fill=\"#64748b\">F / kN</text>"
                + "<text x=\"632\" y=\"274\" text-anchor=\"end\" font-size=\"11\" fill=\"#64748b\">ΔL / mm</text>"
                + "</svg></div>";
    }

    @SuppressWarnings("unchecked")
    String buildGroupHtml(Map<String, Object> data) {
        double fmax = num(data.get("fMax"));
        double dmax = num(data.get("dMax"));
        double A0 = 78.54, L0 = 50;
        double sigb = fmax * 1000 / A0;
        double eps = dmax / L0 * 100;
        List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
        double work = calcWork(points);
        String img = data.get("fractureImg") != null
                ? "<div class=\"rpt-frac\"><img src=\"" + data.get("fractureImg") + "\" alt=\"断口原始图像\" /></div>"
                : "<p class=\"rpt-empty\">暂无断口图像</p>";
        return "<h4>一、实验目的</h4><p>测定材料在拉伸载荷下的力学性能，获取力—位移曲线，观察断口形貌。</p>"
                + "<h4>二、实验设备与试样</h4><p>电子万能试验机；圆截面试样 L<sub>0</sub>=50 mm，d=10 mm，A<sub>0</sub>≈78.54 mm²。</p>"
                + "<h4>三、实验过程摘要</h4><p>小组现场完成安全门联锁确认、启动、数据采集与断口拍摄。数据来源学号：" + escHtml(data.get("sourceSid") == null ? "—" : data.get("sourceSid")) + "。</p>"
                + "<h4>四、主要结果</h4>"
                + String.format(Locale.US, "<p>F<sub>max</sub> = <strong>%.2f</strong> kN；ΔL<sub>max</sub> = <strong>%.2f</strong> mm；σ<sub>b</sub> ≈ <strong>%.1f</strong> MPa；ε<sub>max</sub> ≈ <strong>%.2f</strong> %%；吸收功 W ≈ <strong>%.1f</strong> J。</p>", fmax, dmax, sigb, eps, work)
                + "<h4>五、力—位移曲线</h4>" + buildCurveSvg(points)
                + "<h4>六、断口原始图像</h4>" + img
                + "<h4>七、小组备注</h4><p>" + escHtml(data.get("note") == null ? "（无）" : data.get("note")) + "</p>";
    }

    // ============ 学生对象 ============
    private Map<String, Object> studentRowToObj(Map<String, Object> row, String groupName) {
        if (row == null) return null;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sid", row.get("sid"));
        s.put("name", row.get("name"));
        s.put("cls", row.get("cls"));
        s.put("groupId", row.get("group_id"));
        s.put("groupName", groupName == null ? "" : groupName);
        s.put("status", row.get("status"));
        s.put("taskId", row.get("task_id"));
        s.put("expId", row.get("exp_id"));
        s.put("expName", row.get("exp_name"));
        s.put("note", asStr(row.get("note"), ""));
        s.put("tip", asStr(row.get("tip"), ""));
        s.put("place", asStr(row.get("place"), ""));
        s.put("timeText", asStr(row.get("time_text"), ""));
        s.put("doorClosed", asBool(row.get("door_closed")));
        s.put("expFlow", asStr(row.get("exp_flow"), "wait_door"));
        s.put("acqPaused", asBool(row.get("acq_paused")));
        s.put("acq", JsonUtil.readMap(asStr(row.get("acq_json")), defaultAcq()));
        s.put("maxF", asDouble(row.get("max_f"), 0));
        s.put("maxD", asDouble(row.get("max_d"), 0));
        s.put("fractureImg", row.get("fracture_img"));
        s.put("fractureType", row.get("fracture_type"));
        s.put("fractureConf", asDouble(row.get("fracture_conf"), 0));
        s.put("fractureAnalysis", JsonUtil.readMap(asStr(row.get("fracture_analysis_json")), null));
        s.put("fractureSelf", JsonUtil.readMap(asStr(row.get("fracture_self_json")), null));
        s.put("groupConfirmed", asBool(row.get("group_confirmed")));
        s.put("personal", JsonUtil.readMap(asStr(row.get("personal_json")), new LinkedHashMap<>()));
        s.put("personalSubmitted", asBool(row.get("personal_submitted")));
        s.put("personalSubmittedAt", row.get("personal_submitted_at"));
        s.put("personalScore", row.get("personal_score") == null ? null : asDouble(row.get("personal_score"), 0));
        s.put("personalComment", asStr(row.get("personal_comment"), ""));
        return s;
    }

    private Map<String, Object> defaultAcq() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", new ArrayList<>());
        m.put("t0", null);
        return m;
    }

    private String getGroupName(String groupId) {
        if (groupId == null) return "未分组";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT name FROM lab_groups WHERE id = ?", groupId);
        return rows.isEmpty() ? groupId : String.valueOf(rows.get(0).get("name"));
    }

    public Map<String, Object> getStudent(String sid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM students WHERE sid = ?", sid);
        if (rows.isEmpty()) return null;
        return studentRowToObj(rows.get(0), getGroupName((String) rows.get(0).get("group_id")));
    }

    public List<Map<String, Object>> listStudents() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*, g.name AS group_name FROM students s LEFT JOIN lab_groups g ON g.id = s.group_id "
                        + "ORDER BY (s.group_id IS NULL) DESC, s.group_id, s.sid");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(studentRowToObj(r, asStr(r.get("group_name"), "未分组")));
        return out;
    }

    public List<Map<String, Object>> listGroups() {
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT id, name FROM lab_groups ORDER BY name, id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            List<Map<String, Object>> members = jdbc.queryForList(
                    "SELECT sid, name, cls FROM students WHERE group_id = ? ORDER BY sid", g.get("id"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.get("id"));
            m.put("name", g.get("name"));
            m.put("members", members);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> listMeta() {
        List<Map<String, Object>> experiments = jdbc.queryForList("SELECT id, name, hours FROM experiments ORDER BY id");
        List<Map<String, Object>> teachers = jdbc.queryForList("SELECT name FROM teachers ORDER BY name");
        List<Map<String, Object>> students = listStudents();
        List<String> teacherNames = new ArrayList<>();
        for (Map<String, Object> t : teachers) teacherNames.add(String.valueOf(t.get("name")));
        List<Map<String, Object>> ungrouped = new ArrayList<>();
        for (Map<String, Object> s : students) if (s.get("groupId") == null) ungrouped.add(s);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("experiments", experiments);
        meta.put("teachers", teacherNames);
        meta.put("groups", listGroups());
        meta.put("students", students);
        meta.put("ungrouped", ungrouped);
        return meta;
    }

    // ============ 名单导入 ============
    @Transactional
    public Map<String, Object> importRoster(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) throw new ApiException(400, "表格中没有有效学生数据");
        String defaultHash = PasswordUtil.hash(studentDefaultPassword);
        int created = 0, updated = 0;
        for (Map<String, String> row : rows) {
            String sid = str(row.get("sid"));
            String name = str(row.get("name"));
            String cls = str(row.get("cls"));
            if (cls.isEmpty()) cls = "—";
            if (sid.isEmpty() || name.isEmpty()) continue;
            List<Map<String, Object>> exist = jdbc.queryForList("SELECT sid FROM students WHERE sid = ?", sid);
            if (!exist.isEmpty()) {
                jdbc.update("UPDATE students SET name = ?, cls = ? WHERE sid = ?", name, cls, sid);
                updated++;
            } else {
                jdbc.update("INSERT INTO students (sid, name, cls, group_id, password_hash, status, personal_json, acq_json) "
                        + "VALUES (?, ?, ?, NULL, ?, 'none', '{}', '{\"points\":[],\"t0\":null}')", sid, name, cls, defaultHash);
                created++;
            }
        }
        Map<String, Object> out = rosterResult();
        out.put("created", created);
        out.put("updated", updated);
        return out;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private Map<String, Object> rosterResult() {
        List<Map<String, Object>> students = listStudents();
        List<Map<String, Object>> ungrouped = new ArrayList<>();
        for (Map<String, Object> s : students) if (s.get("groupId") == null) ungrouped.add(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", students);
        out.put("groups", listGroups());
        out.put("ungrouped", ungrouped);
        return out;
    }

    // ============ 分组 ============
    public Map<String, Object> createGroup(String name) {
        String n = str(name);
        if (n.isEmpty()) throw new ApiException(400, "请输入小组名称");
        String id = "G_" + nano(8);
        jdbc.update("INSERT INTO lab_groups (id, name) VALUES (?, ?)", id, n);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", id);
        group.put("name", n);
        group.put("members", new ArrayList<>());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", group);
        out.put("groups", listGroups());
        return out;
    }

    public Map<String, Object> renameGroup(String groupId, String name) {
        String n = str(name);
        if (n.isEmpty()) throw new ApiException(400, "请输入小组名称");
        if (jdbc.queryForList("SELECT id FROM lab_groups WHERE id = ?", groupId).isEmpty())
            throw new ApiException(404, "小组不存在");
        jdbc.update("UPDATE lab_groups SET name = ? WHERE id = ?", n, groupId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", listGroups());
        return out;
    }

    @Transactional
    public Map<String, Object> deleteGroup(String groupId) {
        if (jdbc.queryForList("SELECT id FROM lab_groups WHERE id = ?", groupId).isEmpty())
            throw new ApiException(404, "小组不存在");
        if (!jdbc.queryForList("SELECT task_id FROM task_groups WHERE group_id = ? LIMIT 1", groupId).isEmpty())
            throw new ApiException(400, "该小组已有下发任务，无法删除");
        jdbc.update("UPDATE students SET group_id = NULL WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM lab_groups WHERE id = ?", groupId);
        return rosterResult();
    }

    @Transactional
    public Map<String, Object> setGroupMembers(String groupId, List<String> sids) {
        if (jdbc.queryForList("SELECT id FROM lab_groups WHERE id = ?", groupId).isEmpty())
            throw new ApiException(404, "小组不存在");
        jdbc.update("UPDATE students SET group_id = NULL WHERE group_id = ?", groupId);
        if (sids != null) for (String sid : sids) jdbc.update("UPDATE students SET group_id = ? WHERE sid = ?", groupId, sid);
        return rosterResult();
    }

    @Transactional
    public Map<String, Object> moveStudentsToGroup(String groupId, List<String> sids) {
        if (jdbc.queryForList("SELECT id FROM lab_groups WHERE id = ?", groupId).isEmpty())
            throw new ApiException(404, "小组不存在");
        if (sids == null || sids.isEmpty()) throw new ApiException(400, "请先选择学生");
        for (String sid : sids) jdbc.update("UPDATE students SET group_id = ? WHERE sid = ?", groupId, sid);
        return rosterResult();
    }

    @Transactional
    public Map<String, Object> ungroupStudents(List<String> sids) {
        if (sids == null || sids.isEmpty()) throw new ApiException(400, "请先选择学生");
        for (String sid : sids) jdbc.update("UPDATE students SET group_id = NULL WHERE sid = ?", sid);
        return rosterResult();
    }

    @Transactional
    public Map<String, Object> autoGroup(Integer size, Integer count, String mode, String prefix) {
        String m = "even".equals(mode) ? "even" : "random";
        String pfx = (prefix == null || prefix.isBlank()) ? "第" : prefix.trim();
        List<Map<String, Object>> students = listStudents();
        List<String> sids = new ArrayList<>();
        for (Map<String, Object> s : students) sids.add((String) s.get("sid"));
        if (sids.isEmpty()) throw new ApiException(400, "暂无学生，请先导入名单");

        if (m.equals("random")) Collections.shuffle(sids, RND);

        int groupCount;
        if (count != null && count > 0) groupCount = Math.min(count, sids.size());
        else {
            int sz = Math.max(1, size == null ? 4 : size);
            groupCount = Math.max(1, (int) Math.ceil(sids.size() / (double) sz));
        }

        List<List<String>> buckets = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) buckets.add(new ArrayList<>());
        for (int i = 0; i < sids.size(); i++) buckets.get(i % groupCount).add(sids.get(i));

        jdbc.update("UPDATE students SET group_id = NULL");
        List<Map<String, Object>> used = jdbc.queryForList("SELECT DISTINCT group_id FROM task_groups");
        Set<Object> usedIds = new HashSet<>();
        for (Map<String, Object> u : used) usedIds.add(u.get("group_id"));
        for (Map<String, Object> g : jdbc.queryForList("SELECT id FROM lab_groups")) {
            if (!usedIds.contains(g.get("id"))) jdbc.update("DELETE FROM lab_groups WHERE id = ?", g.get("id"));
        }
        for (int i = 0; i < buckets.size(); i++) {
            String gid = "G_" + nano(8);
            jdbc.update("INSERT INTO lab_groups (id, name) VALUES (?, ?)", gid, pfx + (i + 1) + "组");
            for (String sid : buckets.get(i)) jdbc.update("UPDATE students SET group_id = ? WHERE sid = ?", gid, sid);
        }
        return rosterResult();
    }

    // ============ 学生字段保存 ============
    private static final Map<String, String> FIELD_MAP = new HashMap<>();
    static {
        FIELD_MAP.put("status", "status");
        FIELD_MAP.put("taskId", "task_id");
        FIELD_MAP.put("expId", "exp_id");
        FIELD_MAP.put("expName", "exp_name");
        FIELD_MAP.put("note", "note");
        FIELD_MAP.put("tip", "tip");
        FIELD_MAP.put("place", "place");
        FIELD_MAP.put("timeText", "time_text");
        FIELD_MAP.put("doorClosed", "door_closed");
        FIELD_MAP.put("expFlow", "exp_flow");
        FIELD_MAP.put("acqPaused", "acq_paused");
        FIELD_MAP.put("acq", "acq_json");
        FIELD_MAP.put("maxF", "max_f");
        FIELD_MAP.put("maxD", "max_d");
        FIELD_MAP.put("fractureImg", "fracture_img");
        FIELD_MAP.put("fractureType", "fracture_type");
        FIELD_MAP.put("fractureConf", "fracture_conf");
        FIELD_MAP.put("fractureAnalysis", "fracture_analysis_json");
        FIELD_MAP.put("fractureSelf", "fracture_self_json");
        FIELD_MAP.put("groupConfirmed", "group_confirmed");
        FIELD_MAP.put("personal", "personal_json");
        FIELD_MAP.put("personalSubmitted", "personal_submitted");
        FIELD_MAP.put("personalSubmittedAt", "personal_submitted_at");
        FIELD_MAP.put("personalScore", "personal_score");
        FIELD_MAP.put("personalComment", "personal_comment");
    }
    private static final Set<String> JSON_FIELDS = Set.of("acq", "personal", "fractureAnalysis", "fractureSelf");
    private static final Set<String> BOOL_FIELDS = Set.of("doorClosed", "acqPaused", "groupConfirmed", "personalSubmitted");

    public Map<String, Object> saveStudentFields(String sid, Map<String, Object> patch) {
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String k = e.getKey();
            if (!FIELD_MAP.containsKey(k)) continue;
            Object v = e.getValue();
            if (JSON_FIELDS.contains(k)) v = JsonUtil.write(v);
            else if (BOOL_FIELDS.contains(k)) v = asBool(v) ? 1 : 0;
            sets.add(FIELD_MAP.get(k) + " = ?");
            vals.add(v);
        }
        if (sets.isEmpty()) return getStudent(sid);
        vals.add(sid);
        jdbc.update("UPDATE students SET " + String.join(", ", sets) + " WHERE sid = ?", vals.toArray());
        return getStudent(sid);
    }

    // ============ 实验操作记录（组报告） ============
    @SuppressWarnings("unchecked")
    public Map<String, Object> ensureGroupReport(String taskId, String groupId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM group_reports WHERE task_id = ? AND group_id = ?", taskId, groupId);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO group_reports (task_id, group_id, data_json, html, confirmed_by_json, submitted, comment) "
                    + "VALUES (?, ?, NULL, '', '[]', 0, '')", taskId, groupId);
            return ensureGroupReport(taskId, groupId);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> data = JsonUtil.readMap(asStr(row.get("data_json")), null);
        String html = data != null ? buildGroupHtml(data) : asStr(row.get("html"), "");
        List<Map<String, Object>> deductions = JsonUtil.readListOfMap(asStr(row.get("deductions_json")), new ArrayList<>());
        Double score;
        if (!deductions.isEmpty()) score = (double) computeOperationScore(deductions);
        else score = row.get("score") == null ? null : asDouble(row.get("score"), 0);

        Map<String, Object> gr = new LinkedHashMap<>();
        gr.put("taskId", row.get("task_id"));
        gr.put("groupId", row.get("group_id"));
        gr.put("data", data);
        gr.put("html", html);
        gr.put("confirmedBy", JsonUtil.readList(asStr(row.get("confirmed_by_json")), new ArrayList<>()));
        gr.put("submitted", asBool(row.get("submitted")));
        gr.put("submittedAt", row.get("submitted_at"));
        gr.put("deductions", deductions);
        gr.put("baseScore", OPERATION_BASE_SCORE);
        gr.put("score", score);
        gr.put("comment", asStr(row.get("comment"), ""));
        gr.put("materialType", row.get("material_type"));
        gr.put("sourceSid", row.get("source_sid"));
        return gr;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveGroupReport(Map<String, Object> gr) {
        List<Map<String, Object>> deductions = (List<Map<String, Object>>) gr.getOrDefault("deductions", new ArrayList<>());
        Double score;
        if (deductions != null && !deductions.isEmpty()) score = (double) computeOperationScore(deductions);
        else score = gr.get("score") == null ? null : asDouble(gr.get("score"), 0);
        jdbc.update("UPDATE group_reports SET data_json = ?, html = ?, confirmed_by_json = ?, submitted = ?, submitted_at = ?, "
                        + "score = ?, comment = ?, source_sid = ?, deductions_json = ?, material_type = ? WHERE task_id = ? AND group_id = ?",
                gr.get("data") != null ? JsonUtil.write(gr.get("data")) : null,
                asStr(gr.get("html"), ""),
                JsonUtil.write(gr.getOrDefault("confirmedBy", new ArrayList<>())),
                asBool(gr.get("submitted")) ? 1 : 0,
                gr.get("submittedAt"),
                score,
                asStr(gr.get("comment"), ""),
                gr.get("sourceSid"),
                JsonUtil.write(deductions == null ? new ArrayList<>() : deductions),
                gr.get("materialType"),
                gr.get("taskId"),
                gr.get("groupId"));
        return ensureGroupReport((String) gr.get("taskId"), (String) gr.get("groupId"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> addDeduction(String taskId, String groupId, String itemId, String label, Integer points, String reason, String by) {
        Map<String, Object> gr = ensureGroupReport(taskId, groupId);
        Map<String, Object> catalogHit = null;
        for (Map<String, Object> c : deductionCatalog()) if (c.get("id").equals(itemId)) { catalogHit = c; break; }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "d_" + nano(8));
        entry.put("itemId", itemId == null ? "custom" : itemId);
        entry.put("label", label != null && !label.isBlank() ? label : (catalogHit != null ? catalogHit.get("label") : "自定义扣分"));
        int pts = points != null ? points : (catalogHit != null ? asInt(catalogHit.get("points"), 0) : 0);
        entry.put("points", pts);
        entry.put("reason", reason == null ? "" : reason);
        entry.put("by", by == null ? "" : by);
        entry.put("at", nowLocal());
        List<Map<String, Object>> deductions = (List<Map<String, Object>>) gr.get("deductions");
        if (deductions == null) deductions = new ArrayList<>();
        deductions.add(entry);
        gr.put("deductions", deductions);
        return saveGroupReport(gr);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> removeDeduction(String taskId, String groupId, String deductionId) {
        Map<String, Object> gr = ensureGroupReport(taskId, groupId);
        List<Map<String, Object>> deductions = (List<Map<String, Object>>) gr.get("deductions");
        List<Map<String, Object>> kept = new ArrayList<>();
        if (deductions != null) for (Map<String, Object> d : deductions) if (!deductionId.equals(d.get("id"))) kept.add(d);
        gr.put("deductions", kept);
        return saveGroupReport(gr);
    }

    public Map<String, Object> setMaterialType(String taskId, String groupId, String materialType) {
        Map<String, Object> gr = ensureGroupReport(taskId, groupId);
        gr.put("materialType", materialType);
        return saveGroupReport(gr);
    }

    // ============ 任务 ============
    public List<Map<String, Object>> listTasks() {
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT * FROM tasks ORDER BY created_at DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            List<Map<String, Object>> gids = jdbc.queryForList("SELECT group_id FROM task_groups WHERE task_id = ?", t.get("id"));
            List<Object> groupIds = new ArrayList<>();
            for (Map<String, Object> g : gids) groupIds.add(g.get("group_id"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id"));
            m.put("expId", t.get("exp_id"));
            m.put("expName", t.get("exp_name"));
            m.put("place", asStr(t.get("place"), ""));
            m.put("timeText", asStr(t.get("time_text"), ""));
            m.put("note", asStr(t.get("note"), ""));
            m.put("tip", asStr(t.get("tip"), ""));
            m.put("teacher", t.get("teacher"));
            m.put("createdAt", t.get("created_at"));
            m.put("groupIds", groupIds);
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createTask(String expId, List<String> groupIds, String place, String timeText, String note, String tip, String teacher) {
        List<Map<String, Object>> exps = jdbc.queryForList("SELECT * FROM experiments WHERE id = ?", expId);
        if (exps.isEmpty()) throw new ApiException(400, "实验不存在");
        if (groupIds == null || groupIds.isEmpty()) throw new ApiException(400, "请选择小组");
        Map<String, Object> exp = exps.get(0);
        String tipText = str(tip);

        List<Map<String, Object>> ungrouped = jdbc.queryForList("SELECT sid FROM students WHERE group_id IS NULL OR group_id = ''");
        if (!ungrouped.isEmpty()) throw new ApiException(400, "还有学生未分配组别，请全部分组后再下发");
        for (String gid : groupIds) {
            if (jdbc.queryForList("SELECT sid FROM students WHERE group_id = ?", gid).isEmpty())
                throw new ApiException(400, "所选小组存在空组，请先完成分组");
        }

        String id = "task_" + nano(10);
        String createdAt = nowIso();
        jdbc.update("INSERT INTO tasks (id, exp_id, exp_name, place, time_text, note, tip, teacher, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, exp.get("id"), exp.get("name"), place == null ? "" : place, timeText == null ? "" : timeText,
                note == null ? "" : note, tipText, teacher == null ? "" : teacher, createdAt);
        for (String gid : groupIds) {
            jdbc.update("INSERT INTO task_groups (task_id, group_id) VALUES (?, ?)", id, gid);
            jdbc.update("INSERT INTO group_reports (task_id, group_id, data_json, html, confirmed_by_json, submitted, submitted_at, score, comment, source_sid, deductions_json, material_type) "
                    + "VALUES (?, ?, NULL, '', '[]', 0, NULL, NULL, '', NULL, '[]', NULL) "
                    + "ON DUPLICATE KEY UPDATE data_json=NULL, html='', confirmed_by_json='[]', submitted=0, submitted_at=NULL, score=NULL, comment='', source_sid=NULL, deductions_json='[]', material_type=NULL",
                    id, gid);
            jdbc.update("UPDATE students SET status='assigned', task_id=?, exp_id=?, exp_name=?, note=?, tip=?, place=?, time_text=?, "
                    + "door_closed=0, exp_flow='wait_door', acq_paused=0, acq_json='{\"points\":[],\"t0\":null}', max_f=0, max_d=0, "
                    + "fracture_img=NULL, fracture_type=NULL, fracture_conf=0, fracture_analysis_json=NULL, fracture_self_json=NULL, group_confirmed=0, "
                    + "personal_json='{}', personal_submitted=0, personal_submitted_at=NULL, personal_score=NULL, personal_comment='' WHERE group_id=?",
                    id, exp.get("id"), exp.get("name"), note == null ? "" : note, tipText, place == null ? "" : place, timeText == null ? "" : timeText, gid);
        }
        for (Map<String, Object> t : listTasks()) if (id.equals(t.get("id"))) return t;
        return null;
    }

    // ============ 组内实验数据同步 ============
    @SuppressWarnings("unchecked")
    public Map<String, Object> syncGroupDataFromStudent(String sid) {
        Map<String, Object> s = getStudent(sid);
        if (s == null || s.get("taskId") == null || s.get("groupId") == null) return null;
        Map<String, Object> acq = (Map<String, Object>) s.get("acq");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fMax", s.get("maxF"));
        data.put("dMax", s.get("maxD"));
        data.put("points", acq != null ? acq.get("points") : new ArrayList<>());
        data.put("fractureType", s.get("fractureType"));
        data.put("fractureConf", s.get("fractureConf"));
        data.put("fractureImg", s.get("fractureImg"));
        data.put("fractureAnalysis", s.get("fractureAnalysis"));
        data.put("sourceSid", sid);
        data.put("note", asStr(s.get("note"), ""));
        Map<String, Object> gr = ensureGroupReport((String) s.get("taskId"), (String) s.get("groupId"));
        gr.put("data", data);
        gr.put("html", buildGroupHtml(data));
        gr.put("sourceSid", sid);
        saveGroupReport(gr);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "lab_done");
        patch.put("expFlow", "done_viz");
        patch.put("maxF", s.get("maxF"));
        patch.put("maxD", s.get("maxD"));
        patch.put("acq", s.get("acq"));
        patch.put("fractureImg", s.get("fractureImg"));
        patch.put("fractureType", s.get("fractureType"));
        patch.put("fractureConf", s.get("fractureConf"));
        patch.put("fractureAnalysis", s.get("fractureAnalysis"));
        saveLabProgressForGroup(sid, patch);
        return ensureGroupReport((String) s.get("taskId"), (String) s.get("groupId"));
    }

    @SuppressWarnings("unchecked")
    private int labProgressRank(Map<String, Object> s) {
        if (s == null) return 0;
        Map<String, Integer> statusRankMap = Map.of("none", 0, "assigned", 1, "in_lab", 2, "lab_done", 3, "submitted", 4);
        Map<String, Integer> flowRankMap = Map.of("wait_door", 0, "acquiring", 1, "post_break", 2, "done_viz", 3);
        int statusRank = statusRankMap.getOrDefault(asStr(s.get("status"), "none"), 0);
        int flowRank = flowRankMap.getOrDefault(asStr(s.get("expFlow"), "wait_door"), 0);
        Map<String, Object> acq = (Map<String, Object>) s.get("acq");
        int pts = 0;
        if (acq != null && acq.get("points") instanceof List<?> l) pts = l.size();
        return statusRank * 100000 + flowRank * 1000 + Math.min(pts, 999);
    }

    private Map<String, Object> pickSharedLabFields(Map<String, Object> s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "submitted".equals(s.get("status")) ? "lab_done" : s.get("status"));
        m.put("expFlow", s.get("expFlow"));
        m.put("doorClosed", asBool(s.get("doorClosed")));
        m.put("acqPaused", asBool(s.get("acqPaused")));
        m.put("acq", s.get("acq") != null ? s.get("acq") : defaultAcq());
        m.put("maxF", s.get("maxF"));
        m.put("maxD", s.get("maxD"));
        m.put("fractureImg", s.get("fractureImg"));
        m.put("fractureType", s.get("fractureType"));
        m.put("fractureConf", s.get("fractureConf"));
        m.put("fractureAnalysis", s.get("fractureAnalysis"));
        return m;
    }

    private List<String> listTaskGroupMembers(String groupId, String taskId) {
        if (groupId == null || taskId == null) return new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sid FROM students WHERE group_id = ? AND task_id = ? ORDER BY sid", groupId, taskId);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add((String) r.get("sid"));
        return out;
    }

    public Map<String, Object> saveLabProgressForGroup(String sid, Map<String, Object> patch) {
        Map<String, Object> self = getStudent(sid);
        if (self == null || self.get("groupId") == null || self.get("taskId") == null) return saveStudentFields(sid, patch);
        saveStudentFields(sid, patch);
        Map<String, Object> updated = getStudent(sid);
        Map<String, Object> shared = pickSharedLabFields(updated);
        int newRank = labProgressRank(updated);
        for (String mateSid : listTaskGroupMembers((String) self.get("groupId"), (String) self.get("taskId"))) {
            if (mateSid.equals(sid)) continue;
            Map<String, Object> mate = getStudent(mateSid);
            if (mate == null) continue;
            if (labProgressRank(mate) > newRank) continue;
            if ("submitted".equals(mate.get("status"))) {
                Map<String, Object> rest = new LinkedHashMap<>(shared);
                rest.remove("status");
                saveStudentFields(mateSid, rest);
            } else {
                saveStudentFields(mateSid, shared);
            }
        }
        return getStudent(sid);
    }

    public Map<String, Object> alignStudentWithGroupLab(String sid) {
        Map<String, Object> self = getStudent(sid);
        if (self == null || self.get("groupId") == null || self.get("taskId") == null) return self;
        if ("none".equals(self.get("status"))) return self;
        Map<String, Object> leader = self;
        for (String mateSid : listTaskGroupMembers((String) self.get("groupId"), (String) self.get("taskId"))) {
            Map<String, Object> mate = getStudent(mateSid);
            if (mate == null) continue;
            if (labProgressRank(mate) > labProgressRank(leader)) leader = mate;
        }
        if (leader.get("sid").equals(self.get("sid"))) return self;
        if (labProgressRank(leader) <= labProgressRank(self)) return self;
        Map<String, Object> shared = pickSharedLabFields(leader);
        if ("submitted".equals(self.get("status"))) {
            Map<String, Object> rest = new LinkedHashMap<>(shared);
            rest.remove("status");
            return saveStudentFields(sid, rest);
        }
        return saveStudentFields(sid, shared);
    }

    // ============ 评阅队列 ============
    public List<Map<String, Object>> gradingQueue() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*, g.name AS group_name FROM students s JOIN lab_groups g ON g.id = s.group_id "
                        + "WHERE s.status != 'none' ORDER BY s.group_id, s.sid");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> stu = studentRowToObj(r, (String) r.get("group_name"));
            Map<String, Object> gr = stu.get("taskId") != null
                    ? ensureGroupReport((String) stu.get("taskId"), (String) stu.get("groupId")) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sid", stu.get("sid"));
            item.put("name", stu.get("name"));
            item.put("groupName", stu.get("groupName"));
            item.put("expName", stu.get("expName") == null ? "—" : stu.get("expName"));
            item.put("status", stu.get("status"));
            item.put("groupSubmitted", gr != null && asBool(gr.get("submitted")));
            item.put("personalSubmitted", asBool(stu.get("personalSubmitted")));
            item.put("groupScore", gr != null && gr.get("score") != null ? gr.get("score") : null);
            item.put("personalScore", stu.get("personalScore"));
            item.put("taskId", stu.get("taskId"));
            item.put("groupId", stu.get("groupId"));
            out.add(item);
        }
        return out;
    }

    // ============ 会话与登录 ============
    public String createSession(String role, String studentId, String teacherName) {
        String token = nano(24);
        jdbc.update("INSERT INTO sessions (token, role, student_id, teacher_name, created_at) VALUES (?, ?, ?, ?, ?)",
                token, role, studentId, teacherName, nowIso());
        return token;
    }

    public Map<String, Object> loginWithPassword(String account, String password) {
        String acc = str(account);
        String pwd = password == null ? "" : password;
        if (acc.isEmpty() || pwd.isEmpty()) throw new ApiException(400, "请输入账号和密码");

        List<Map<String, Object>> teachers = jdbc.queryForList(
                "SELECT id, name, username, password_hash FROM teachers WHERE username = ? LIMIT 1", acc);
        if (!teachers.isEmpty()) {
            if (!PasswordUtil.verify(pwd, (String) teachers.get(0).get("password_hash")))
                throw new ApiException(401, "账号或密码错误");
            String teacherName = (String) teachers.get(0).get("name");
            String token = createSession("teacher", null, teacherName);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("role", "teacher");
            out.put("teacherName", teacherName);
            return out;
        }

        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT sid, name, password_hash FROM students WHERE sid = ? LIMIT 1", acc);
        if (!students.isEmpty()) {
            if (!PasswordUtil.verify(pwd, (String) students.get(0).get("password_hash")))
                throw new ApiException(401, "账号或密码错误");
            String sid = (String) students.get(0).get("sid");
            String token = createSession("student", sid, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("role", "student");
            out.put("student", getStudent(sid));
            return out;
        }
        throw new ApiException(401, "账号或密码错误");
    }

    public Map<String, Object> getSession(String token) {
        if (token == null || token.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sessions WHERE token = ?", token);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteSession(String token) {
        if (token == null || token.isEmpty()) return;
        jdbc.update("DELETE FROM sessions WHERE token = ?", token);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sessionSnapshot(String token) {
        Map<String, Object> sess = getSession(token);
        if (sess == null) return null;
        Map<String, Object> meta = listMeta();
        if ("teacher".equals(sess.get("role"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "teacher");
            out.put("teacherName", sess.get("teacher_name"));
            out.put("tasks", listTasks());
            out.put("grading", gradingQueue());
            out.put("meta", meta);
            return out;
        }
        Map<String, Object> student = alignStudentWithGroupLab((String) sess.get("student_id"));
        if (student == null) return null;
        Map<String, Object> groupReport = student.get("taskId") != null
                ? ensureGroupReport((String) student.get("taskId"), (String) student.get("groupId")) : null;
        List<Map<String, Object>> groups = (List<Map<String, Object>>) meta.get("groups");
        List<Map<String, Object>> mates = new ArrayList<>();
        for (Map<String, Object> g : groups) if (g.get("id").equals(student.get("groupId"))) mates = (List<Map<String, Object>>) g.get("members");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "student");
        out.put("student", student);
        out.put("mates", mates);
        out.put("groupReport", groupReport);
        out.put("meta", meta);
        return out;
    }
}
