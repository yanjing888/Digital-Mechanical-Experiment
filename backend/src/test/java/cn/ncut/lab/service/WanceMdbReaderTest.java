package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WanceMdbReaderTest {
    @Test
    void latestMdbUsesFileNameTimeInsteadOfModifiedTime(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path older = Files.createFile(dir.resolve("2024-04-25-09-24-49.mdb"));
        Path newer = Files.createFile(dir.resolve("2024-04-25-10-24-49.mdb"));
        Files.createFile(dir.resolve("other.mdb"));
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));
        assertEquals(newer, WanceMdbReader.latestMdb(dir));
        assertEquals(2, WanceMdbReader.listSources(dir).size());
    }

    @Test
    void dataDirectoryConfigReloadsWithoutRestart(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path first = Files.createDirectory(dir.resolve("first"));
        Path second = Files.createDirectory(dir.resolve("second"));
        Path config = dir.resolve("wance.properties");
        Files.writeString(config, "data.dir=" + first + "\n");
        WanceDataService service = new WanceDataService("", config.toString());
        assertEquals(first, service.directory());
        Files.writeString(config, "data.dir=" + second + "\n");
        assertEquals(second, service.directory());
    }

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
            var params = new com.healthmarketscience.jackcess.TableBuilder("TestNo")
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("TestNo",com.healthmarketscience.jackcess.DataType.LONG))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Area",com.healthmarketscience.jackcess.DataType.DOUBLE))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("GaugeLength",com.healthmarketscience.jackcess.DataType.DOUBLE)).toTable(db);
            params.addRow(1,80.0,50.0);
        }
        var points=(java.util.List<Map<String,Object>>)WanceMdbReader.parse(file).get("points");
        assertEquals(java.util.List.of(0.0,2.0,3.0),points.stream().map(p->p.get("f")).toList());
        assertEquals(0.5,points.get(2).get("d")); // Preserve genuine unloading, never sort by displacement.
        var geometry=(Map<?,?>)WanceMdbReader.parse(file).get("geometry");
        assertEquals(80.0,geometry.get("area"));
        assertEquals(50.0,geometry.get("gaugeLength"));
    }
}
