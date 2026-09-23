package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WanceMdbReaderTest {
    static boolean samplePresent() {
        return java.nio.file.Files.isRegularFile(Path.of("D:/yanjing/01 北方工业大学土木力学实验室数字化升级项目/data(1)/2024-04-25-09-24-49.mdb"));
    }

    @Test
    @EnabledIf("samplePresent")
    void parseSampleMdb() throws Exception {
        Path mdb = Path.of("D:/yanjing/01 北方工业大学土木力学实验室数字化升级项目/data(1)/2024-04-25-09-24-49.mdb");
        Map<String, Object> data = WanceMdbReader.parse(mdb);
        assertEquals("wance_mdb", data.get("source"));
        assertTrue(((Number) data.get("pointCount")).intValue() > 100);
        assertTrue(((Number) data.get("maxF")).doubleValue() > 10);
        assertTrue(((Number) data.get("maxD")).doubleValue() > 1);
    }
}
