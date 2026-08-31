package cn.ncut.lab.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

public final class Sessions {
    public static final String ATTR = "labSession";

    private Sessions() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> get(HttpServletRequest req) {
        Object s = req.getAttribute(ATTR);
        if (!(s instanceof Map<?, ?>)) throw new ApiException(401, "未登录");
        return (Map<String, Object>) s;
    }

    public static Map<String, Object> requireTeacher(HttpServletRequest req) {
        Map<String, Object> sess = get(req);
        if (!"teacher".equals(String.valueOf(sess.get("role")))) {
            throw new ApiException(403, "需要教师权限");
        }
        return sess;
    }

    public static Map<String, Object> requireStudent(HttpServletRequest req) {
        Map<String, Object> sess = get(req);
        if (!"student".equals(String.valueOf(sess.get("role")))) {
            throw new ApiException(403, "需要学生权限");
        }
        return sess;
    }

    public static String studentId(HttpServletRequest req) {
        return String.valueOf(requireStudent(req).get("student_id"));
    }

    public static String teacherName(HttpServletRequest req) {
        Object n = requireTeacher(req).get("teacher_name");
        return n == null ? "" : String.valueOf(n);
    }

    public static String tokenFrom(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        String x = req.getHeader("X-Session-Token");
        if (x != null && !x.isBlank()) return x.trim();
        String q = req.getParameter("token");
        return q == null ? "" : q.trim();
    }
}
