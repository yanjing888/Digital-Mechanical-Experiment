package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WanceMdbReaderTest {
    @Test
    void readsAcquisitionOrderRatherThanPhysicalRowOrder(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file=dir.resolve("unordered.mdb");
        try(var db=com.healthmarketscience.jackcess.DatabaseBuilder.create(com.healthmarketscience.jackcess.Database.FileFormat.V2000,file.toFile())) {
            var table=new com.healthmarketscience.jackcess.TableBuilder("OriginalData")
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("ID",com.healthmarketscience.jackcess.DataType.LONG))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("TestNo",com.healthmarketscience.jackcess.DataType.LONG))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("PlayTime",com.healthmarketscience.jackcess.DataType.DOUBLE))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("LoadValue",com.healthmarketscience.jackcess.DataType.DOUBLE))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("PositionValue",com.healthmarketscience.jackcess.DataType.DOUBLE)).toTable(db);
            table.addRow(1,1,0.0,0.0,0.0);table.addRow(3,1,2.0,3000.0,0.5);table.addRow(2,1,1.0,2000.0,1.0);
        }
        var points=(java.util.List<Map<String,Object>>)WanceMdbReader.parse(file).get("points");
        assertEquals(java.util.List.of(0.0,2.0,3.0),points.stream().map(p->p.get("f")).toList());
        assertEquals(0.5,points.get(2).get("d")); // Preserve genuine unloading, never sort by displacement.
    }
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
