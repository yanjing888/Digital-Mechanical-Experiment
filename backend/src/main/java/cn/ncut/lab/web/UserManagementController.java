package cn.ncut.lab.web;

import cn.ncut.lab.util.PasswordUtil;
import cn.ncut.lab.service.StudentPasswordVault;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UserManagementController {
    private final JdbcTemplate jdbc;
    private StudentPasswordVault passwordVault;

    @Autowired(required=false)
    public void setPasswordVault(StudentPasswordVault passwordVault) {this.passwordVault=passwordVault;}

    public UserManagementController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/account/password")
    @Transactional
    public Map<String, Object> changeOwnPassword(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String sid = Sessions.studentId(req);
        String current = password(body, "currentPassword"), next = password(body, "newPassword");
        if (current.isEmpty()) throw new ApiException(400, "请输入原密码");
        if (next.length() < 6 || next.length() > 128 || next.isBlank()) throw new ApiException(400, "新密码须为 6 至 128 位");
        if (!next.equals(password(body, "confirmPassword"))) throw new ApiException(400, "两次输入的新密码不一致");
        var rows = jdbc.queryForList("SELECT password_hash FROM students WHERE sid=? FOR UPDATE", sid);
        if (rows.isEmpty()) throw new ApiException(404, "学生账号不存在");
        String stored = String.valueOf(rows.get(0).get("password_hash"));
        if (!PasswordUtil.verify(current, stored)) throw new ApiException(400, "原密码不正确");
        if (next.equals(current)) throw new ApiException(400, "新密码不能与原密码相同");
        String hash=PasswordUtil.hash(next);
        jdbc.update("UPDATE students SET password_hash=? WHERE sid=?", hash, sid);
        if(passwordVault!=null)passwordVault.save(sid,next,hash);
        jdbc.update("DELETE FROM sessions WHERE role='student' AND student_id=? AND token<>?", sid, Sessions.tokenFrom(req));
        return Map.of("ok", true);
    }

    private static String password(Map<String, Object> body, String key) {
        return body.get(key) instanceof String text ? text : "";
    }

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
    @Transactional
    public Map<String, Object> resetStudentPassword(HttpServletRequest req, @PathVariable String sid,
                                                     @RequestBody Map<String, Object> body) {
        Sessions.requireTeacher(req);
        String password = value(body, "password");
        if (password.length() < 6) throw new ApiException(400, "密码至少 6 位");
        String hash=PasswordUtil.hash(password);
        int changed = jdbc.update("UPDATE students SET password_hash=? WHERE sid=?", hash, sid);
        if (changed == 0) throw new ApiException(404, "学生不存在");
        if(passwordVault!=null)passwordVault.save(sid,password,hash);
        jdbc.update("DELETE FROM sessions WHERE role='student' AND student_id=?", sid);
        return Map.of("ok", true);
    }

    @PostMapping("/roster/students/{sid}/password")
    public ResponseEntity<Map<String,Object>> revealStudentPassword(HttpServletRequest req,@PathVariable String sid) {
        String teacher=Sessions.teacherName(req);
        var rows=jdbc.queryForList("SELECT password_hash FROM students WHERE sid=?",sid);
        if(rows.isEmpty())throw new ApiException(404,"学生不存在");
        if(passwordVault==null)throw new ApiException(503,"密码查看服务未就绪");
        try {
            var password=passwordVault.reveal(sid,String.valueOf(rows.get(0).get("password_hash")));
            org.slf4j.LoggerFactory.getLogger(UserManagementController.class).info("教师 {} 查看学生 {} 的登录密码，可用：{}",teacher,sid,password.isPresent());
            Map<String,Object> result=password.isPresent()?Map.of("available",true,"password",password.get()):Map.of("available",false,"message","历史密码无法还原，请重置密码后查看");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(result);
        } catch(IllegalStateException e) {throw new ApiException(503,"无法读取密码，请检查学生密码密钥文件");}
    }

    private static String value(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
