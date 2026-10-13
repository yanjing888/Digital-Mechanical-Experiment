package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class WanceDataService {
    private final Path fallbackDir;
    private final Path configFile;

    public WanceDataService(@Value("${lab.wance.data-dir:}") String dir,
                            @Value("${lab.wance.config-file:}") String configFile) {
        this.fallbackDir = dir == null || dir.isBlank() ? null : Paths.get(dir.trim()).toAbsolutePath().normalize();
        this.configFile = configFile == null || configFile.isBlank() ? null : Paths.get(configFile.trim()).toAbsolutePath().normalize();
    }

    private Map<String, String> configValues() {
        if (configFile == null || !Files.isRegularFile(configFile)) return Map.of();
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int equals = trimmed.indexOf('=');
                if (equals > 0) values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
        } catch (IOException e) {
            throw new ApiException(503, "读取采集配置失败：" + e.getMessage());
        }
        return values;
    }

    private Path configuredDirectory() {
        Map<String, String> config = configValues();
        if (configFile != null && Files.isRegularFile(configFile)) {
            String value = config.getOrDefault("data.dir", "");
            if (value.isEmpty()) throw new ApiException(503, "采集配置缺少 data.dir");
            return Paths.get(value).toAbsolutePath().normalize();
        }
        return fallbackDir;
    }

    public boolean configured() { return configuredDirectory() != null; }

    public Path directory() {
        Path dir = configuredDirectory();
        if (dir == null) throw new ApiException(503, "尚未配置万测数据目录");
        if (!Files.isDirectory(dir)) throw new ApiException(503, "万测数据目录不可用：" + dir);
        return dir;
    }

    public Map<String, Object> status() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Path dir = configuredDirectory();
        out.put("configured", dir != null && Files.isDirectory(dir));
        out.put("vendor", "万测");
        out.put("software", "TestPilot");
        if (dir == null) {
            out.put("dataDir", "");
            out.put("sources", List.of());
            return out;
        }
        out.put("dataDir", dir.toString());
        out.put("sources", WanceMdbReader.listSources(dir));
        return out;
    }

}
