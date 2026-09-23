package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class WanceDataService {
    private final Path dataDir;
    private final String defaultFile;

    public WanceDataService(@Value("${lab.wance.data-dir:}") String dir, @Value("${lab.wance.default-file:2024-04-25-09-24-49.mdb}") String defaultFile) {
        this.dataDir = dir == null || dir.isBlank() ? null : Paths.get(dir.trim());
        this.defaultFile = defaultFile == null ? "" : defaultFile.trim();
    }

    public boolean configured() { return dataDir != null; }

    public Path directory() {
        if (dataDir == null) throw new ApiException(503, "尚未配置万测数据目录，请联系教师设置 lab.wance.data-dir");
        if (!Files.isDirectory(dataDir)) throw new ApiException(503, "万测数据目录不可用：" + dataDir);
        return dataDir;
    }

    public Map<String, Object> status() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", configured());
        out.put("vendor", "万测");
        out.put("software", "TestPilot");
        if (!configured()) {
            out.put("dataDir", "");
            out.put("sources", List.of());
            return out;
        }
        out.put("dataDir", dataDir.toAbsolutePath().toString());
        out.put("defaultFile", defaultFile);
        out.put("sources", WanceMdbReader.listSources(dataDir));
        return out;
    }

    public String defaultFileName() { return defaultFile; }

    public Path resolve(String fileName) throws IOException {
        Path dir = directory();
        String pick = fileName == null || fileName.isBlank() ? defaultFile : fileName.trim();
        if (pick.isBlank()) throw new ApiException(503, "未配置默认试验数据文件");
        Path candidate = dir.resolve(pick).normalize();
        if (!candidate.startsWith(dir) || !Files.isRegularFile(candidate)) throw new ApiException(404, "找不到试验数据文件：" + pick);
        if (!candidate.toString().toLowerCase(Locale.ROOT).endsWith(".mdb")) throw new ApiException(400, "仅支持万测 .mdb 文件");
        return candidate;
    }
}
