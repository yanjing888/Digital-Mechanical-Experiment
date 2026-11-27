package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import com.healthmarketscience.jackcess.*;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.attribute.BasicFileAttributes;
import com.healthmarketscience.jackcess.util.MemFileChannel;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 万测 TestPilot 试验软件生成的 Access .mdb（表 OriginalData：LoadValue、PositionValue）。 */
public final class WanceMdbReader {
    private static final Pattern FILE_TIME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})\\.mdb$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private WanceMdbReader() {}

    public static List<Map<String, Object>> listSources(Path dataDir) throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> parseFileNameTime(f.getFileName().toString()).isPresent()).toList()) {
                out.add(Map.of(
                        "fileName", p.getFileName().toString(),
                        "experimentAt", parseFileNameTime(p.getFileName().toString()).orElseThrow().toString(),
                        "modifiedAt", Files.getLastModifiedTime(p).toInstant().toString(),
                        "sizeBytes", Files.size(p)));
            }
        }
        out.sort((a, b) -> str(b.get("experimentAt")).compareTo(str(a.get("experimentAt"))));
        return out;
    }

    public static Path latestMdb(Path dataDir) throws IOException {
        return listSources(dataDir).stream()
                .map(m -> dataDir.resolve(str(m.get("fileName"))))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "数据目录中暂无按时间命名的 .mdb 文件，请先在万测软件完成试验"));
    }

    static Path latestMdb(Path dataDir, CaptureWindow window) throws IOException {
        return listSources(dataDir).stream().filter(m->window.accepts(Instant.parse(str(m.get("experimentAt")))))
                .map(m->dataDir.resolve(str(m.get("fileName")))).findFirst()
                .orElseThrow(()->new ApiException(409,"未检测到本课次开课时间（"+
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(CaptureWindow.ZONE).format(window.start())+
                        "）起生成的有效试验文件，请先完成试验并保存数据。开课前的数据不会采集。"));
    }
    record Snapshot(byte[] content, Instant modifiedAt) {}
    static Snapshot readStable(Path path, Instant now, Duration settleTime) throws IOException {
        var before=Files.readAttributes(path,BasicFileAttributes.class);
        if(before.size()>20L*1024*1024)throw new ApiException(400,"试验文件不能超过20MB");
        if(before.lastModifiedTime().toInstant().plus(settleTime).isAfter(now))throw new ApiException(409,"试验文件仍在写入，请等待万测软件保存完成后重新采集");
        byte[] content=Files.readAllBytes(path);
        var after=Files.readAttributes(path,BasicFileAttributes.class);
        if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey())||content.length!=after.size())throw new ApiException(409,"试验文件正在更新，请保存完成后重新采集");
        return new Snapshot(content,after.lastModifiedTime().toInstant());
    }

    public static Map<String, Object> parse(Path mdb) throws IOException {
        try(Database db=DatabaseBuilder.open(mdb.toFile())) { return parse(mdb,db,Files.getLastModifiedTime(mdb).toInstant()); }
    }
    static Map<String,Object> parseSnapshot(Path mdb, Snapshot snapshot) throws IOException {
        try(var channel=MemFileChannel.newChannel(new ByteArrayInputStream(snapshot.content()),MemFileChannel.RO_CHANNEL_MODE);
            Database db=new DatabaseBuilder().setChannel(channel).setReadOnly(true).open()) {
            return parse(mdb,db,snapshot.modifiedAt());
        }
    }
    private static Map<String,Object> parse(Path mdb,Database db,Instant modifiedAt) throws IOException {
        List<Map<String, Object>> points = new ArrayList<>();
        double maxF = -Double.MAX_VALUE, maxD = -Double.MAX_VALUE;
        Integer testNo = null;
        Map<String, Object> geometry = Map.of();
        {
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
            geometry = readGeometry(db, testNo);
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
        out.put("deviceResults", readDeviceResults(db));
        if (!geometry.isEmpty()) out.put("geometry", geometry);
        out.put("deviceVendor", "万测");
        out.put("deviceSoftware", "TestPilot");
        out.put("source", "wance_mdb");
        out.put("sourceFile", mdb.getFileName().toString());
        out.put("sourcePath", mdb.toAbsolutePath().toString());
        out.put("fileModifiedAt", modifiedAt.toString());
        parseFileNameTime(mdb.getFileName().toString()).ifPresent(t -> out.put("experimentAtFromFile", t.toString()));
        return out;
    }

    private static Map<String, Object> readGeometry(Database db, Integer testNo) throws IOException {
        Map<String,Object> geometry=new LinkedHashMap<>();
        for (String tableName : List.of("TestNo", "TestNoParam")) {
            if (!db.getTableNames().contains(tableName)) continue;
            for (Row row : db.getTable(tableName)) {
                Integer rowTestNo = asInt(row.get("TestNo"));
                if (testNo != null && !testNo.equals(rowTestNo)) continue;
                Double area = asDouble(row.get("Area"));
                Double gauge = asDouble(row.get("GaugeLength"));
                if(area!=null&&Double.isFinite(area)&&area>0)geometry.putIfAbsent("area",area);
                if(gauge!=null&&Double.isFinite(gauge)&&gauge>0)geometry.putIfAbsent("gaugeLength",gauge);
                if(!geometry.isEmpty()){geometry.put("strainSource","crosshead");geometry.put("source","mdb_"+tableName.toLowerCase(Locale.ROOT));}
                if(geometry.containsKey("area")&&geometry.containsKey("gaugeLength"))return geometry;
            }
        }
        return geometry;
    }

    /** Read stored results only; never recompute material properties from crosshead displacement. */
    private static Map<String,Object> readDeviceResults(Database db) throws IOException {
        Map<String,Object> result=new LinkedHashMap<>();
        if(!db.getTableNames().containsAll(List.of("ReportSecondHead","ReportSecondBody")))return result;
        Map<Integer,List<Row>> values=new HashMap<>();
        for(Row row:db.getTable("ReportSecondBody")) {
            Integer col=asInt(row.get("Col"));
            if(col!=null)values.computeIfAbsent(col,key->new ArrayList<>()).add(row);
        }
        List<Row> headers=new ArrayList<>();Map<Integer,Integer> headerColumns=new HashMap<>();
        for(Row header:db.getTable("ReportSecondHead")) {
            headers.add(header);Integer col=asInt(header.get("Col"));
            if(col!=null)headerColumns.merge(col,1,Integer::sum);
        }
        Set<String> seenKeys=new HashSet<>();Set<String> ambiguous=new HashSet<>();
        for(Row header:headers) {
            String key=null;
            for(String field:List.of("Name","Code","ShowName0")){key=resultKey(str(header.get(field)));if(key!=null)break;}
            if(key==null)continue;
            if(!seenKeys.add(key)){ambiguous.add(key);continue;}
            Integer col=asInt(header.get("Col"));Double factor=stressFactor(str(header.get("Unit")));
            if(col==null||headerColumns.getOrDefault(col,0)!=1)continue;
            var rows=values.getOrDefault(col,List.of());
            // Multiple report rows cannot be attributed to this trial without an explicit test identifier.
            if(rows.size()!=1||factor==null)continue;
            Double number=asDouble(rows.get(0).get("Value"));
            if(number!=null&&Double.isFinite(number)&&number>0&&Double.isFinite(number*factor))result.put(key,number*factor);
        }
        ambiguous.forEach(result::remove);
        if(result.containsKey("elasticModulus")) {
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(Row row:db.getTable("OriginalData")) {
                Double extension=asDouble(row.get("ExtendValue"));
                if(extension!=null&&Double.isFinite(extension)){min=Math.min(min,extension);max=Math.max(max,extension);}
            }
            boolean needsReview=!(max-min>1e-9);
            result.put("modulusNeedsReview",needsReview);
            if(needsReview)result.put("modulusReviewReason","未检测到有效引伸计数据，请核对设备的弹性模量测量方式。");
        }
        return result;
    }
    private static String resultKey(String name) {
        String normalized=name.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
        if(normalized.contains("抗拉强度")||normalized.endsWith("RM"))return "tensileStrength";
        if(normalized.contains("上屈服强度")||normalized.endsWith("REH"))return "upperYieldStrength";
        if(normalized.contains("下屈服强度")||normalized.endsWith("REL"))return "lowerYieldStrength";
        if(normalized.contains("弹性模量")||normalized.equals("E"))return "elasticModulus";
        return null;
    }
    private static Double stressFactor(String unit) {
        return switch(unit.replaceAll("\\s+","").replace('²','2').toLowerCase(Locale.ROOT)) {
            case "mpa","n/mm2","n/mm^2" -> 1.0;
            case "gpa" -> 1000.0;
            case "kpa" -> 0.001;
            case "pa" -> 0.000001;
            default -> null;
        };
    }
    static Map<String,Object> storedSummary(byte[] content,Integer testNo) throws IOException {
        try(var channel=MemFileChannel.newChannel(new ByteArrayInputStream(content),MemFileChannel.RO_CHANNEL_MODE);
            Database db=new DatabaseBuilder().setChannel(channel).setReadOnly(true).open()) {
            return Map.of("deviceResults",readDeviceResults(db),"geometry",readGeometry(db,testNo));
        }
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
            LocalDateTime ldt = LocalDateTime.parse(name.substring(0, 19), LOCAL_FMT);
            return Optional.of(ldt.atZone(CaptureWindow.ZONE).toInstant());
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
