package cn.ncut.lab.service;

import java.util.*;

/** 教师下发任务可选的固定实验项目（顺序即下拉展示顺序）。 */
public final class ExperimentCatalog {
    private ExperimentCatalog() {}

    public static final String COMBINED = "TENSION_COMPRESSION";
    public static final List<String> IDS = List.of(COMBINED);
    public static final List<String> TRIAL_IDS = List.of("STEEL_TENS", "CAST_TENS", "STEEL_COMP", "CAST_COMP");

    public static final Map<String, String> NAMES = Map.of(
            COMBINED, "拉伸压缩实验",
            "STEEL_TENS", "钢的拉伸",
            "CAST_TENS", "铸铁的拉伸",
            "STEEL_COMP", "钢的压缩",
            "CAST_COMP", "铸铁的压缩");

    public static boolean isKnown(String expId) {
        return expId != null && NAMES.containsKey(expId);
    }

    /** 曲线/断口算法区分拉伸与压缩（兼容历史 TENS、COMP）。 */
    public static boolean isCompression(String expId) {
        if (expId == null || expId.isBlank()) return false;
        String u = expId.toUpperCase(Locale.ROOT);
        if ("COMP".equals(u)) return true;
        return u.endsWith("_COMP") || u.startsWith("COMP_");
    }

    /** 传给 fracture-service 的 experiment 字段。 */
    public static String fractureMode(String expId) {
        return isCompression(expId) ? "COMP" : "TENS";
    }
}
