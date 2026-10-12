package cn.ncut.lab.service;

import cn.ncut.lab.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DifyServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadsOriginalReportAndPassesFileIdToWorkflow() throws Exception {
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/files/upload", exchange -> {
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                assertTrue(contentType.startsWith("multipart/form-data; boundary="));
                byte[] body = exchange.getRequestBody().readAllBytes();
                String multipart = new String(body, StandardCharsets.ISO_8859_1);
                assertTrue(multipart.contains("name=\"user\""));
                assertTrue(multipart.contains("teacher-1"));
                assertTrue(multipart.contains("filename=\"report.pdf\""));
                assertTrue(multipart.contains("%PDF-test-report"));
                respond(exchange, 201, "{\"id\":\"uploaded-file-1\"}");
            } catch (Throwable error) {
                handlerFailure.set(error);
                respond(exchange, 500, "{\"message\":\"test handler failed\"}");
            }
        });
        server.createContext("/v1/workflows/run", exchange -> {
            try {
                assertEquals("POST", exchange.getRequestMethod());
                Map<String, Object> request = JsonUtil.readMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.of());
                Map<String, Object> inputs = (Map<String, Object>) request.get("inputs");
                assertEquals("张同学", inputs.get("student_name"));
                assertEquals("20260001", inputs.get("student_sid"));
                assertFalse(inputs.containsKey("report_type"));
                assertFalse(inputs.containsKey("report_text"));
                assertFalse(inputs.containsKey("lab_data"));
                assertFalse(inputs.containsKey("content_chars"));
                Map<String, Object> file = (Map<String, Object>) inputs.get("report_file");
                assertEquals("document", file.get("type"));
                assertEquals("local_file", file.get("transfer_method"));
                assertEquals("uploaded-file-1", file.get("upload_file_id"));
                respond(exchange, 200, "{\"data\":{\"status\":\"succeeded\",\"id\":\"run-1\",\"outputs\":{\"score\":88,\"comment\":\"分项评阅完成\"}}}");
            } catch (Throwable error) {
                handlerFailure.set(error);
                respond(exchange, 500, "{\"message\":\"test handler failed\"}");
            }
        });
        server.start();

        DifyService service = new DifyService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "gradingApiKey", "");

        Map<String, Object> result = service.gradeReport(
                "张同学", "20260001", "第一组", "拉伸压缩实验", "实验报告正文",
                "材料力学实验报告.pdf", "application/pdf", "%PDF-test-report".getBytes(StandardCharsets.UTF_8), "teacher-1"
        );

        assertNull(handlerFailure.get(), () -> String.valueOf(handlerFailure.get()));
        assertEquals(88.0, result.get("score"));
        assertEquals("分项评阅完成", result.get("comment"));
        assertEquals("run-1", result.get("workflowRunId"));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
