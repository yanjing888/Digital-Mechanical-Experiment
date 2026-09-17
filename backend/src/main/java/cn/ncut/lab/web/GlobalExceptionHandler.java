package cn.ncut.lab.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,Object>> uploadLimit(Exception e) {
        return ResponseEntity.status(413).body(Map.of("error","文件超过20MB限制，请压缩或拆分后重试"));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage() == null ? "请求失败" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        String msg = e.getMessage() == null ? "服务器内部错误" : e.getMessage();
        return ResponseEntity.status(500).body(Map.of("error", msg));
    }
}
