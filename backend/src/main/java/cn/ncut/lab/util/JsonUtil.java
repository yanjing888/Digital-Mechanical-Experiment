package cn.ncut.lab.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "null";
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String s, Map<String, Object> fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return MAPPER.readValue(s, Map.class);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static List<Object> readList(String s, List<Object> fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return MAPPER.readValue(s, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readListOfMap(String s, List<Map<String, Object>> fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return MAPPER.readValue(s, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return fallback;
        }
    }
}
