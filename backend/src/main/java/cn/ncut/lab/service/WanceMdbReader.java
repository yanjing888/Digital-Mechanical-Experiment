package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import com.healthmarketscience.jackcess.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 万测 TestPilot 试验软件生成的 Access .mdb（表 OriginalData：LoadValue、PositionValue）。 */
public final class WanceMdbReader {
    private static final Pattern FILE_TIME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})");
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private WanceMdbReader() {}

    public static List<Map<String, Object>> listSources(Path dataDir) throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            for (Path p : stream.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".mdb")).toList()) {
                out.add(Map.of(
                        "fileName", p.getFileName().toString(),
                        "modifiedAt", Files.getLastModifiedTime(p).toInstant().toString(),
                        "sizeBytes", Files.size(p)));
            }
        }
        out.sort((a, b) -> str(b.get("modifiedAt")).compareTo(str(a.get("modifiedAt"))));
        return out;
    }

    public static Path latestMdb(Path dataDir) throws IOException {
        return listSources(dataDir).stream()
                .map(m -> dataDir.resolve(str(m.get("fileName"))))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "数据目录中暂无 .mdb 试验文件，请先在万测软件完成试验"));
    }

    public static Map<String, Object> parse(Path mdb) throws IOException {
        List<Map<String, Object>> points = new ArrayList<>();
        double maxF = -Double.MAX_VALUE, maxD = -Double.MAX_VALUE;
        Integer testNo = null;
        try (Database db = DatabaseBuilder.open(mdb.toFile())) {
            if (!db.getTableNames().contains("OriginalData")) throw new ApiException(400, "不是可识别的万测数据文件（缺少 OriginalData 表）");
            Table table = db.getTable("OriginalData");
            List<Row> rows = new ArrayList<>();
            for (Row row : table) rows.add(row);
            // Access physical page order is not acquisition order (including after edits).
            String orderKey = rows.stream().allMatch(r -> finite(r.get("PlayTime"))) ? "PlayTime"
                    : rows.stream().allMatch(r -> finite(r.get("ID"))) ? "ID" : null;
            if (orderKey == null) throw new ApiException(400,"设备数据缺少有效采集时间或序号，无法确定曲线顺序");
            Set<Integer> tests = new HashSet<>();
            for (Row row : rows) { Integer number=asInt(row.get("TestNo")); if(number!=null) tests.add(number); }
            if(tests.size()>1) throw new ApiException(400,"设备文件包含多次试验，请导出单次试验数据后采集");
            rows.sort(Comparator.comparingDouble((Row row) -> asDouble(row.get(orderKey)))
                    .thenComparingDouble(row -> finite(row.get("ID")) ? asDouble(row.get("ID")) : 0));
            for (Row row : rows) {
                Double load = asDouble(row.get("LoadValue"));
                Double pos = asDouble(row.get("PositionValue"));
                if (load == null || pos == null || !Double.isFinite(load) || !Double.isFinite(pos)) continue;
                if (testNo == null) testNo = asInt(row.get("TestNo"));
                double fKn = load / 1000.0;
                double dMm = pos;
                points.add(Map.of("f", fKn, "d", dMm));
                maxF = Math.max(maxF, fKn);
                maxD = Math.max(maxD, dMm);
            }
        }
        if (points.size() < 2) throw new ApiException(400, "万测数据文件中没有有效曲线点");
        if (points.size() > 12000) points = downsample(points, 8000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("maxF", maxF);
        out.put("maxD", maxD);
        out.put("forceUnit", "kN");
        out.put("displacementUnit", "mm");
        out.put("pointCount", points.size());
        out.put("testNo", testNo);
        out.put("deviceVendor", "万测");
        out.put("deviceSoftware", "TestPilot");
        out.put("source", "wance_mdb");
        out.put("sourceFile", mdb.getFileName().toString());
        out.put("sourcePath", mdb.toAbsolutePath().toString());
        out.put("fileModifiedAt", Files.getLastModifiedTime(mdb).toInstant().toString());
        parseFileNameTime(mdb.getFileName().toString()).ifPresent(t -> out.put("experimentAtFromFile", t.toString()));
        return out;
    }

    static List<Map<String, Object>> downsample(List<Map<String, Object>> points, int limit) {
        if (points.size() <= limit) return points;
        List<Map<String, Object>> out = new ArrayList<>(limit);
        double step = (double) (points.size() - 1) / (limit - 1);
        for (int i = 0; i < limit; i++) out.add(points.get((int) Math.round(i * step)));
        return out;
    }

    static Optional<Instant> parseFileNameTime(String name) {
        Matcher m = FILE_TIME.matcher(name);
        if (!m.find()) return Optional.empty();
        try {
            LocalDateTime ldt = LocalDateTime.parse(m.group(0), LOCAL_FMT);
            return Optional.of(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }
    private static boolean finite(Object value) {
        Double number=asDouble(value);return number!=null && Double.isFinite(number);
    }

    static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    static String str(Object v) { return v == null ? "" : v.toString(); }
}
