package cn.ncut.lab.service;

import java.util.*;

/** 拉伸/压缩：由设备曲线与断口宏观照片分别给出塑脆线索，合并为学生可见摘要；详细依据仅存 analysis。 */
@SuppressWarnings("unchecked")
public final class FractureSummary {
    private FractureSummary() {}

    public static Map<String, Object> fromCurve(Map<String, Object> data, String experiment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "device_curve");
        if (data == null || data.isEmpty()) {
            out.put("judgment", "uncertain");
            out.put("note", "尚未导入设备数据");
            return out;
        }
        List<Map<String, Object>> points = RunService.list(data.get("points"));
        if (points.size() < 5) {
            out.put("judgment", "uncertain");
            out.put("note", "曲线点数不足");
            return out;
        }
        int iMax = 0;
        double maxF = -Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double f = RunService.score(points.get(i).get("f"));
            if (f > maxF) {
                maxF = f;
                iMax = i;
            }
        }
        double dAtMax = RunService.score(points.get(iMax).get("d"));
        double dEnd = RunService.score(points.get(points.size() - 1).get("d"));
        double fEnd = RunService.score(points.get(points.size() - 1).get("f"));
        double tailDrop = maxF > 0 ? (maxF - fEnd) / maxF : 0;
        double postMaxDisp = Math.max(0, dEnd - dAtMax);
        double postMaxRatio = dAtMax > 1e-6 ? postMaxDisp / dAtMax : 0;
        boolean comp = ExperimentCatalog.isCompression(experiment);
        String judgment;
        String note;
        if (comp) {
            if (tailDrop > 0.35 && postMaxRatio < 0.08) {
                judgment = "brittle";
                note = "峰值后载荷骤降且位移增量很小，符合压缩脆性破坏曲线特征";
            } else if (postMaxRatio > 0.12 || (tailDrop < 0.25 && postMaxRatio > 0.05)) {
                judgment = "ductile";
                note = "峰值后仍有明显塑性变形段，符合压缩塑性破坏曲线特征";
            } else {
                judgment = "uncertain";
                note = "曲线塑脆特征不显著，需结合断口形貌判断";
            }
        } else {
            if (postMaxRatio > 0.08 && tailDrop > 0.15) {
                judgment = "ductile";
                note = "最大载荷后位移继续增大且载荷缓慢下降，符合拉伸颈缩/塑性断裂曲线特征";
            } else if (tailDrop > 0.4 && postMaxRatio < 0.04) {
                judgment = "brittle";
                note = "最大载荷后载荷骤降、塑性变形段极短，符合脆性断裂曲线特征";
            } else {
                judgment = "uncertain";
                note = "曲线塑脆特征不显著，需结合断口形貌判断";
            }
        }
        out.put("judgment", judgment);
        out.put("note", note);
        out.put("maxF", data.get("maxF"));
        out.put("maxD", data.get("maxD"));
        return out;
    }

    public static Map<String, Object> fromPhotos(List<Map<String, Object>> macroResults) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "fracture_photos");
        if (macroResults.isEmpty()) {
            out.put("judgment", "uncertain");
            out.put("note", "尚无断口照片分析");
            return out;
        }
        Map<String, Integer> votes = new HashMap<>();
        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> item : macroResults) {
            Map<String, Object> result = RunService.map(item.get("result"));
            String c = RunService.str(result.get("candidate"));
            if (Set.of("ductile", "brittle").contains(c)) votes.merge(c, 1, Integer::sum);
            for (Object line : RunService.list(result.get("evidence"))) evidence.add(RunService.str(line));
        }
        String judgment = pick(votes);
        out.put("judgment", judgment);
        out.put("note", judgmentLabel(judgment) + "（依据 " + macroResults.size() + " 张断口照片宏观形态）");
        out.put("evidence", evidence);
        return out;
    }

    public static Map<String, Object> build(String experiment, Map<String, Object> data, List<Map<String, Object>> macroResults) {
        Map<String, Object> curve = fromCurve(data, experiment);
        Map<String, Object> photo = fromPhotos(macroResults);
        String combined = combine(RunService.str(curve.get("judgment")), RunService.str(photo.get("judgment")));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("judgment", combined);
        summary.put("label", judgmentLabel(combined));
        summary.put("experiment", experiment);
        summary.put("curve", Map.of("judgment", curve.get("judgment"), "label", judgmentLabel(RunService.str(curve.get("judgment")))));
        summary.put("photo", Map.of("judgment", photo.get("judgment"), "label", judgmentLabel(RunService.str(photo.get("judgment")))));
        summary.put("studentHint", studentHint(combined));
        List<String> evidence = new ArrayList<>();
        for (Object line : RunService.list(photo.get("evidence"))) evidence.add(RunService.str(line));
        String curveNote = RunService.str(curve.get("note"));
        if (!curveNote.isBlank() && !"尚未导入设备数据".equals(curveNote)) evidence.add("设备曲线：" + curveNote);
        summary.put("evidence", evidence);
        summary.put("reportBasis", String.join("\n", evidence));
        return summary;
    }

    static String combine(String curve, String photo) {
        if (curve.equals(photo) && Set.of("ductile", "brittle").contains(curve)) return curve;
        if ("ductile".equals(curve) || "ductile".equals(photo)) {
            if ("brittle".equals(curve) || "brittle".equals(photo)) return "uncertain";
        }
        if ("brittle".equals(curve) || "brittle".equals(photo)) return "brittle";
        if ("ductile".equals(curve) || "ductile".equals(photo)) return "ductile";
        return "uncertain";
    }

    static String pick(Map<String, Integer> votes) {
        int d = votes.getOrDefault("ductile", 0), b = votes.getOrDefault("brittle", 0);
        if (d > b && d > 0) return "ductile";
        if (b > d && b > 0) return "brittle";
        return "uncertain";
    }

    static String judgmentLabel(String j) {
        return switch (j) {
            case "ductile" -> "塑性断裂";
            case "brittle" -> "脆性断裂";
            default -> "尚不能判断";
        };
    }

    static String studentHint(String combined) {
        return switch (combined) {
            case "ductile" -> "设备曲线与断口形貌均倾向塑性断裂，可作为报告中的初步结论；请结合现场观察自行表述。";
            case "brittle" -> "设备曲线与断口形貌均倾向脆性断裂，可作为报告中的初步结论；请结合现场观察自行表述。";
            default -> "曲线与照片线索不完全一致或不够明确，请在报告中依据亲眼观察描述断口特征并给出判断。";
        };
    }
}
