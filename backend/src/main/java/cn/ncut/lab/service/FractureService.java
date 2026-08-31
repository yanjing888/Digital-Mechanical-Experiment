package cn.ncut.lab.service;

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
import java.util.List;
import java.util.Map;

/**
 * 断口图像分析：调用独立的 Python + OpenCV 微服务（fracture-service）。
 */
@Service
public class FractureService {

    @Value("${fracture.service-url:http://127.0.0.1:8090}")
    private String serviceUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(String imageBase64, List<Object> points) {
        if (imageBase64 == null || imageBase64.isBlank()) throw new ApiException(400, "请先提供断口图像");
        if (imageBase64.length() > 12 * 1024 * 1024) throw new ApiException(400, "图像过大，请压缩后再上传");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("imageBase64", imageBase64);
            body.put("points", points == null ? List.of() : points);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl.replaceAll("/+$", "") + "/analyze"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = mapper.readValue(res.body(), Map.class);
            if (res.statusCode() < 200 || res.statusCode() >= 300 || data.get("error") != null) {
                String msg = data.get("error") != null ? String.valueOf(data.get("error")) : ("断口分析失败 HTTP " + res.statusCode());
                throw new ApiException(res.statusCode() == 400 ? 400 : 500, msg);
            }
            if (data.get("fractureType") == null) throw new ApiException(500, "断口分析无有效结果");
            return data;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "断口分析服务不可用：" + e.getMessage());
        }
    }
}
