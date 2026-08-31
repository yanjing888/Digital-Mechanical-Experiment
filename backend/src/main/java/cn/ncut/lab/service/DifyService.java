package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import cn.ncut.lab.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dify 报告评阅工作流接入，等价于 Node 版 dify.js。
 * 未配置时回退到本地启发式评阅（演示兜底）。
 */
@Service
public class DifyService {

    @Value("${dify.enabled:false}")
    private boolean enabled;
    @Value("${dify.api-url:}")
    private String apiUrl;
    @Value("${dify.api-key:}")
    private String apiKey;
    @Value("${dify.grading-api-key:}")
    private String gradingApiKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private boolean isEnabled() {
        return enabled && apiUrl != null && !apiUrl.isBlank() && !gradingKey().isBlank();
    }

    private String gradingKey() {
        String k = gradingApiKey != null && !gradingApiKey.isBlank() ? gradingApiKey : apiKey;
        return k == null ? "" : k.trim();
    }

    private String apiBase() {
        return apiUrl == null ? "" : apiUrl.replaceAll("/+$", "");
    }

    public Map<String, Object> getStatus() {
        boolean en = isEnabled();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", en);
        m.put("provider", "dify");
        m.put("ready", en);
        m.put("gradingReady", en && !gradingKey().isBlank());
        m.put("message", en ? "Dify 已启用，可用于报告 AI 评阅"
                : "未接入。配置 DIFY_ENABLED / DIFY_API_URL / DIFY_GRADING_API_KEY 后启用");
        return m;
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<script.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?</style>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("(?i)</h[1-6]>", "\n");
        s = s.replaceAll("(?i)</li>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"");
        s = s.replaceAll("\\s+\n", "\n").replaceAll("\n{3,}", "\n\n").replaceAll("[ \t]{2,}", " ");
        return s.trim();
    }

    private Double clampScore(Object n) {
        if (n == null) return null;
        try {
            double x = Double.parseDouble(String.valueOf(n));
            return Math.max(0, Math.min(100, Math.round(x * 10) / 10.0));
        } catch (Exception e) {
            return null;
        }
    }

    private int measureCharCount(String reportText) {
        String t = htmlToText(reportText == null ? "" : reportText)
                .replace("一、实验步骤描述", " ")
                .replace("二、数据分析与误差", " ")
                .replace("三、总结与反思", " ");
        Matcher m = Pattern.compile("[\\u4e00-\\u9fffA-Za-z]").matcher(t);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> gradeReport(String reportType, String studentName, String studentSid,
                                           String groupName, String expName, String reportText,
                                           Map<String, Object> labData, String user) {
        String type = "personal".equals(reportType) ? "personal" : "group";
        String text = htmlToText(reportText == null ? "" : reportText);
        if (text.length() > 14000) text = text.substring(0, 14000);
        int charCount = measureCharCount(text);
        if (labData == null) labData = new LinkedHashMap<>();

        if (!isEnabled()) {
            return localFallback(type, text, labData, charCount);
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("report_type", type);
        inputs.put("student_name", studentName == null ? "" : studentName);
        inputs.put("student_sid", studentSid == null ? "" : studentSid);
        inputs.put("group_name", groupName == null ? "" : groupName);
        inputs.put("exp_name", expName == null ? "" : expName);
        inputs.put("report_text", text.isBlank() ? "（报告正文为空：步骤/分析/反思均无有效文字）" : text);
        inputs.put("lab_data", JsonUtil.write(labData));
        inputs.put("content_chars", String.valueOf(charCount));

        Map<String, Object> raw = runWorkflow(inputs, user != null ? user : (studentSid != null ? studentSid : "teacher"));
        Map<String, Object> outputs = new LinkedHashMap<>();
        Object dataObj = raw.get("data");
        if (dataObj instanceof Map<?, ?> dm && dm.get("outputs") instanceof Map<?, ?> om) outputs = (Map<String, Object>) om;
        else if (raw.get("outputs") instanceof Map<?, ?> om) outputs = (Map<String, Object>) om;

        Map<String, Object> graded = parseGradePayload(outputs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", graded.get("score"));
        out.put("comment", graded.get("comment"));
        out.put("source", "dify");
        if (dataObj instanceof Map<?, ?> dm) out.put("workflowRunId", dm.get("id") != null ? dm.get("id") : dm.get("workflow_run_id"));
        out.put("charCount", charCount);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runWorkflow(Map<String, Object> inputs, String user) {
        String key = gradingKey();
        if (key.isBlank()) throw new ApiException(503, "缺少 DIFY_GRADING_API_KEY / DIFY_API_KEY");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputs", inputs);
            body.put("response_mode", "blocking");
            body.put("user", user == null ? "lab-teacher" : user);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase() + "/workflows/run"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = mapper.readValue(res.body(), Map.class);
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                Object msg = data.getOrDefault("message", data.getOrDefault("error", "Dify 调用失败 HTTP " + res.statusCode()));
                throw new ApiException(res.statusCode() >= 400 && res.statusCode() < 600 ? res.statusCode() : 502, String.valueOf(msg));
            }
            return data;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Dify 调用失败：" + e.getMessage());
        }
    }

    private Map<String, Object> parseGradePayload(Map<String, Object> outputs) {
        Map<String, Object> out = outputs == null ? new LinkedHashMap<>() : outputs;
        Double score = clampScore(out.get("score") != null ? out.get("score") : out.get("Score"));
        String comment = out.get("comment") != null ? String.valueOf(out.get("comment"))
                : (out.get("Comment") != null ? String.valueOf(out.get("Comment")) : "");
        if (score == null || comment.isBlank()) {
            String text = String.valueOf(out.getOrDefault("text", out.getOrDefault("result", out.getOrDefault("output", "")))).trim();
            Matcher m = Pattern.compile("\\{[\\s\\S]*\\}").matcher(text);
            if (m.find()) {
                Map<String, Object> data = JsonUtil.readMap(m.group(), null);
                if (data != null) {
                    if (score == null) score = clampScore(data.get("score"));
                    if (comment.isBlank() && data.get("comment") != null) comment = String.valueOf(data.get("comment")).trim();
                }
            }
            if (comment.isBlank() && !text.isBlank()) comment = text.length() > 800 ? text.substring(0, 800) : text;
        }
        if (score == null) score = 40.0;
        if (comment.isBlank()) comment = "AI 评阅结果不完整，请教师人工核阅后给分。";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("score", score);
        r.put("comment", comment);
        return r;
    }

    private Map<String, Object> localFallback(String type, String text, Map<String, Object> lab, int charCount) {
        double score;
        String comment;
        String kind = "group".equals(type) ? "实验操作记录" : "个人报告";
        if (charCount < 60) {
            if (charCount <= 0) { score = 8; comment = kind + "正文几乎为空，无法体现实验理解与数据处理，建议退回重写。参考分 8。"; }
            else if (charCount < 40) { score = 18; comment = kind + "有效文字过少（约 " + charCount + " 字），内容不充分，建议补充后再评。参考分 18。"; }
            else { score = 28; comment = kind + "篇幅偏短且信息不足，尚未达到合格报告要求。参考分 28。"; }
        } else {
            double sc = 55;
            if (text.length() > 80) sc += 8;
            if (Pattern.compile("目的|设备|过程|结论|误差|反思|断口|曲线|Fmax|σ|延伸").matcher(text).find()) sc += 10;
            Object fMax = lab.get("fMax");
            if (fMax != null && text.contains(String.valueOf(Math.round(Double.parseDouble(String.valueOf(fMax)))))) sc += 8;
            Object ft = lab.get("fractureType");
            if ("ductile".equals(ft) && Pattern.compile("塑|杯锥|纤维|剪切唇").matcher(text).find()) sc += 8;
            else if ("brittle".equals(ft) && Pattern.compile("脆|解理|平整|放射").matcher(text).find()) sc += 8;
            if ("personal".equals(type) && Pattern.compile("误差|不足|改进|反思").matcher(text).find()) sc += 6;
            score = Math.min(92, sc);
            comment = "【本地评阅】" + kind + "参考分 " + Math.round(score) + "，请教师结合现场表现核阅后给分。";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", clampScore(score));
        out.put("comment", comment);
        out.put("source", "local-fallback");
        out.put("charCount", charCount);
        return out;
    }
}
