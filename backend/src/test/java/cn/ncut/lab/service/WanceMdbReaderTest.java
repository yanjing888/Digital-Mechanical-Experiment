package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WanceMdbReaderTest {
    private Path metricsFile(Path dir,boolean extension) throws Exception {
        Path file=dir.resolve("2026-11-27-14-20-00.mdb");
        try(var db=com.healthmarketscience.jackcess.DatabaseBuilder.create(com.healthmarketscience.jackcess.Database.FileFormat.V2000,file.toFile())) {
            var points=new com.healthmarketscience.jackcess.TableBuilder("OriginalData");
            for(String name:java.util.List.of("ID","TestNo","PlayTime","LoadValue","PositionValue","ExtendValue"))points.addColumn(new com.healthmarketscience.jackcess.ColumnBuilder(name,com.healthmarketscience.jackcess.DataType.DOUBLE));
            var table=points.toTable(db);table.addRow(1,1,0,0,0,0);table.addRow(2,1,1,34880,27.734,extension?0.1:0);
            var head=new com.healthmarketscience.jackcess.TableBuilder("ReportSecondHead")
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Name",com.healthmarketscience.jackcess.DataType.TEXT))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Unit",com.healthmarketscience.jackcess.DataType.TEXT))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Col",com.healthmarketscience.jackcess.DataType.LONG)).toTable(db);
            head.addRow("下屈服强度ReL","N/mm²",9);head.addRow("弹性模量E","GPa",5);head.addRow("抗拉强度Rm","MPa",7);head.addRow("上屈服强度ReH","kPa",3);
            var body=new com.healthmarketscience.jackcess.TableBuilder("ReportSecondBody")
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Value",com.healthmarketscience.jackcess.DataType.TEXT))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Col",com.healthmarketscience.jackcess.DataType.LONG)).toTable(db);
            body.addRow("444.102926888101",7);body.addRow("318.612037331002",9);body.addRow("344707.330925086",3);body.addRow("8.91227407377794",5);
            var params=new com.healthmarketscience.jackcess.TableBuilder("TestNoParam")
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("TestNo",com.healthmarketscience.jackcess.DataType.LONG))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("Area",com.healthmarketscience.jackcess.DataType.DOUBLE))
                    .addColumn(new com.healthmarketscience.jackcess.ColumnBuilder("GaugeLength",com.healthmarketscience.jackcess.DataType.DOUBLE)).toTable(db);
            params.addRow(1,78.53982,50.0);
        }
        return file;
    }
    @Test void readsNamedDeviceResultsByColumnAndConvertsStressUnits(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=metricsFile(dir,false);var data=WanceMdbReader.parse(file);var results=(Map<?,?>)data.get("deviceResults");
        assertEquals(444.102926888101,(Double)results.get("tensileStrength"),0.000001);
        assertEquals(344.707330925086,(Double)results.get("upperYieldStrength"),0.000001);
        assertEquals(318.612037331002,(Double)results.get("lowerYieldStrength"),0.000001);
        assertEquals(8912.27407377794,(Double)results.get("elasticModulus"),0.000001);
        assertEquals(true,results.get("modulusNeedsReview"));
        var summary=WanceMdbReader.storedSummary(Files.readAllBytes(file),1);
        assertEquals(results,summary.get("deviceResults"));assertEquals(data.get("geometry"),summary.get("geometry"));
    }
    @Test void validExtensionChannelAndPartialGeometryAreRepresentedHonestly(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=metricsFile(dir,true);
        try(var db=com.healthmarketscience.jackcess.DatabaseBuilder.open(file.toFile())) {
            var table=db.getTable("TestNoParam");var row=table.iterator().next();row.put("GaugeLength",null);table.updateRow(row);
        }
        var data=WanceMdbReader.parse(file);var geometry=(Map<?,?>)data.get("geometry");
        assertEquals(78.53982,geometry.get("area"));assertFalse(geometry.containsKey("gaugeLength"));
        assertEquals(false,((Map<?,?>)data.get("deviceResults")).get("modulusNeedsReview"));
    }
    @Test void ambiguousMalformedAndUnknownUnitResultsDoNotProduceFakeNumbers(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=metricsFile(dir,false);
        try(var db=com.healthmarketscience.jackcess.DatabaseBuilder.open(file.toFile())) {
            db.getTable("ReportSecondBody").addRow("500",7);
            var head=db.getTable("ReportSecondHead");for(var row:head)if(((Number)row.get("Col")).intValue()==5){row.put("Unit","unknown");head.updateRow(row);}
            var body=db.getTable("ReportSecondBody");for(var row:body){int col=((Number)row.get("Col")).intValue();if(col==9||col==3){row.put("Value",col==9?"-1":"NaN");body.updateRow(row);}}
        }
        var data=WanceMdbReader.parse(file);assertTrue(((Map<?,?>)data.get("deviceResults")).isEmpty());
        assertEquals(34.88,data.get("maxF"));
    }
    @Test void sharedReportColumnsCannotBeAssignedToDifferentProperties(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var file=metricsFile(dir,false);
        try(var db=com.healthmarketscience.jackcess.DatabaseBuilder.open(file.toFile())){db.getTable("ReportSecondHead").addRow("弹性模量E","MPa",7);}
        var results=(Map<?,?>)WanceMdbReader.parse(file).get("deviceResults");
        assertFalse(results.containsKey("tensileStrength"));assertFalse(results.containsKey("elasticModulus"));
        assertEquals(2,results.size());assertTrue(results.containsKey("upperYieldStrength"));assertTrue(results.containsKey("lowerYieldStrength"));
    }
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
        var snapshot=new WanceMdbReader.Snapshot(Files.readAllBytes(file),Files.getLastModifiedTime(file).toInstant());
        Files.write(file,new byte[]{1,2,3}); // The parser must use the archived snapshot, not reopen a changing device file.
        assertEquals(points,WanceMdbReader.parseSnapshot(file,snapshot).get("points"));
    }
}
