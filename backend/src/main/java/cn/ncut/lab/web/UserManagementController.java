package cn.ncut.lab.web;

import cn.ncut.lab.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UserManagementController {
    private final JdbcTemplate jdbc;

    public UserManagementController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/admin/teachers")
    public Map<String, Object> teachers(HttpServletRequest req) {
        Sessions.requireAdmin(req);
        return Map.of("teachers", jdbc.queryForList(
                "SELECT id, username, name, active FROM teachers ORDER BY name, username"));
    }

    @PostMapping("/admin/teachers")
    public Map<String, Object> addTeacher(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Sessions.requireAdmin(req);
        String username = value(body, "username");
        String name = value(body, "name");
        String password = value(body, "password");
        if (!username.matches("[A-Za-z0-9._-]{3,64}") || name.isEmpty() || name.length() > 64 || password.length() < 6)
            throw new ApiException(400, "请输入有效账号、姓名和至少 6 位密码");
        if (username.equalsIgnoreCase("admin") || !jdbc.queryForList("SELECT sid FROM students WHERE sid=?", username).isEmpty())
            throw new ApiException(409, "账号已被占用");
        String id = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            jdbc.update("INSERT INTO teachers (id,name,username,password_hash,active) VALUES (?,?,?,?,1)",
                    id, name, username, PasswordUtil.hash(password));
        } catch (DuplicateKeyException e) { throw new ApiException(409, "教师账号或姓名已存在"); }
        return Map.of("id", id, "username", username, "name", name, "active", true);
    }

    @PatchMapping("/admin/teachers/{id}")
    public Map<String, Object> updateTeacher(HttpServletRequest req, @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        Sessions.requireAdmin(req);
        if (jdbc.queryForList("SELECT id FROM teachers WHERE id=?", id).isEmpty()) throw new ApiException(404, "教师不存在");
        if (body.containsKey("name")) {
            String name = value(body, "name");
            if (name.isEmpty() || name.length() > 64) throw new ApiException(400, "教师姓名无效");
            String previousName = jdbc.queryForObject("SELECT name FROM teachers WHERE id=?", String.class, id);
            try { jdbc.update("UPDATE teachers SET name=? WHERE id=?", name, id); }
            catch (DuplicateKeyException e) { throw new ApiException(409, "教师姓名已存在"); }
            if (!name.equals(previousName)) jdbc.update("DELETE FROM sessions WHERE role='teacher' AND teacher_name=?", previousName);
        }
        if (body.containsKey("password")) {
            String password = value(body, "password");
            if (password.length() < 6) throw new ApiException(400, "密码至少 6 位");
            jdbc.update("UPDATE teachers SET password_hash=? WHERE id=?", PasswordUtil.hash(password), id);
            jdbc.update("DELETE FROM sessions WHERE role='teacher' AND teacher_name=(SELECT name FROM teachers WHERE id=?)", id);
        }
        if (body.containsKey("active")) {
            boolean active = Boolean.TRUE.equals(body.get("active"));
            jdbc.update("UPDATE teachers SET active=? WHERE id=?", active ? 1 : 0, id);
            if (!active) jdbc.update("DELETE FROM sessions WHERE role='teacher' AND teacher_name=(SELECT name FROM teachers WHERE id=?)", id);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/roster/students/{sid}/reset-password")
    public Map<String, Object> resetStudentPassword(HttpServletRequest req, @PathVariable String sid,
                                                     @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        String password = value(body, "password");
        if (password.length() < 6) throw new ApiException(400, "密码至少 6 位");
        int changed = jdbc.update("UPDATE students SET password_hash=? WHERE sid=?", PasswordUtil.hash(password), sid);
        if (changed == 0) throw new ApiException(404, "学生不存在");
        jdbc.update("DELETE FROM sessions WHERE role='student' AND student_id=?", sid);
        return Map.of("ok", true);
    }

    private static String value(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
