package cn.ncut.lab.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取项目根目录的 .env（与 Node 版共用同一份配置），
 * 注入为高优先级属性源，使 application.yml 的 ${DB_HOST} 等占位符可解析。
 */
public class EnvFileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path[] candidates = new Path[]{
                Paths.get(".env"),
                Paths.get("..", ".env"),
                Paths.get("..", "..", ".env")
        };
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    Map<String, Object> map = parse(p);
                    if (!map.isEmpty()) {
                        environment.getPropertySources().addFirst(new MapPropertySource("dotenv", map));
                    }
                    return;
                }
            } catch (IOException ignored) {
                // 忽略，回退到 application.yml 默认值
            }
        }
    }

    private Map<String, Object> parse(Path path) throws IOException {
        Map<String, Object> map = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int i = s.indexOf('=');
            if (i < 0) continue;
            String key = s.substring(0, i).trim();
            String val = s.substring(i + 1).trim();
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            if (!key.isEmpty()) map.put(key, val);
        }
        return map;
    }
}
