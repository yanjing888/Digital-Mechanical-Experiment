package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRulesTest {
    final Instant afterClass=Instant.parse("2026-11-27T09:00:00Z");
    CaptureWindow window() { return CaptureWindow.of(Map.of(),Map.of("time_text","2026-11-27 14:00"),afterClass); }
    @Test void windowChecksDeviceTimeWithinFiveHoursNotLoginTime() {
        var window=window();
        assertEquals(Instant.parse("2026-11-27T06:00:00Z"),window.start());
        assertEquals(Instant.parse("2026-11-27T11:00:00Z"),window.end());
        assertFalse(window.accepts(Instant.parse("2026-11-27T05:59:59Z")));
        assertTrue(window.accepts(Instant.parse("2026-11-27T06:20:00Z")));
        assertFalse(window.accepts(afterClass.plusSeconds(1)));
        var delayed=CaptureWindow.of(Map.of(),Map.of("time_text","2026-11-27 14:00"),afterClass.plus(Duration.ofDays(2)));
        assertFalse(delayed.accepts(Instant.parse("2026-11-27T06:20:00Z")));
        assertThrows(ApiException.class,delayed::requireOpen);
        var atDeadline=new CaptureWindow(window.start(),window.end(),window.end());
        assertTrue(atDeadline.accepts(window.end().minusSeconds(1)));
        assertTrue(atDeadline.accepts(window.end()));
        var expired=new CaptureWindow(window.start(),window.end(),window.end().plusSeconds(1));
        assertFalse(expired.accepts(window.start()));
        assertThrows(ApiException.class,()->expired.require(window.start()));
        assertFalse(delayed.accepts(window.end().plusSeconds(1)));
    }
    @Test void automaticLateOpeningDoesNotExcludeEarlierExperimentButManualEarlyOpeningDoesAllowIt() {
        var task=Map.<String,Object>of("time_text","2026-11-27 14:00");
        var automatic=CaptureWindow.of(Map.of("startMode","automatic","startedAt","2026-11-27T08:00:00Z"),task,afterClass);
        assertTrue(automatic.accepts(Instant.parse("2026-11-27T06:20:00Z")));
        var manual=CaptureWindow.of(Map.of("startMode","manual","startedAt","2026-11-27T05:30:00Z"),task,afterClass);
        assertTrue(manual.accepts(Instant.parse("2026-11-27T05:40:00Z")));
        assertEquals(manual.start().plus(Duration.ofHours(5)),manual.end());
        var overnight=CaptureWindow.of(Map.of("startMode","manual","startedAt","2026-11-26T15:30:00Z"),Map.of("time_text","2026-11-27 00:30"),Instant.parse("2026-11-26T20:30:00Z"));
        assertTrue(overnight.accepts(Instant.parse("2026-11-26T16:40:00Z")));
        assertEquals(Instant.parse("2026-11-26T20:30:00Z"),overnight.end());
        assertTrue(overnight.accepts(overnight.end()));
        assertFalse(overnight.accepts(overnight.end().plusSeconds(1)));
    }
    @Test void undatedTasksUseActualOpeningAndRetakesStartANewWindow() {
        assertEquals(Instant.parse("2026-11-27T06:00:00Z"),CaptureWindow.of(Map.of("startedAt","2026-11-27T06:00:00Z"),Map.of(),afterClass).start());
        assertEquals(Instant.parse("2026-11-27T07:00:00Z"),CaptureWindow.of(Map.of("parentId","parent","createdAt","2026-11-27T07:00:00Z"),Map.of("time_text","2026-11-26 14:00"),afterClass).start());
        assertThrows(ApiException.class,()->CaptureWindow.of(Map.of(),Map.of(),afterClass));
        assertThrows(ApiException.class,()->CaptureWindow.uploadedTime("data.csv"));
        assertEquals(Instant.parse("2026-11-27T06:20:00Z"),CaptureWindow.uploadedTime("2026-11-27-14-20-00.csv"));
    }
    @Test void latestEligibleFileSkipsYesterdayAndFutureAndNeverTrustsModifiedTime(@TempDir Path dir) throws Exception {
        Path old=Files.createFile(dir.resolve("2026-11-26-16-00-00.mdb"));
        Path early=Files.createFile(dir.resolve("2026-11-27-13-59-59.mdb"));
        Files.setLastModifiedTime(old,FileTime.from(afterClass));Files.setLastModifiedTime(early,FileTime.from(afterClass));
        var missing=assertThrows(ApiException.class,()->WanceMdbReader.latestMdb(dir,window()));
        assertTrue(missing.getMessage().contains("2026-11-27 14:00:00"));
        assertTrue(missing.getMessage().contains("开课前的数据不会采集"));
        Files.createFile(dir.resolve("2026-02-30-14-20-00.mdb"));
        Path valid=Files.createFile(dir.resolve("2026-11-27-14-20-00.mdb"));
        Files.createFile(dir.resolve("2026-11-27-19-00-01.mdb"));
        Files.createFile(dir.resolve("2026-11-28-14-20-00.mdb"));
        assertEquals(valid,WanceMdbReader.latestMdb(dir,window()));
        var delayed=CaptureWindow.of(Map.of(),Map.of("time_text","2026-11-27 14:00"),afterClass.plus(Duration.ofDays(2)));
        assertThrows(ApiException.class,()->WanceMdbReader.latestMdb(dir,delayed));
        var original=TimeZone.getDefault();
        try { TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertEquals(Instant.parse("2026-11-27T06:20:00Z"),WanceMdbReader.parseFileNameTime(valid.getFileName().toString()).orElseThrow()); }
        finally { TimeZone.setDefault(original); }
    }
    @Test void activeFileMustSettleBeforeItsBytesCanBeCaptured(@TempDir Path dir) throws Exception {
        Path file=Files.write(dir.resolve("data.mdb"),new byte[]{1,2,3});Files.setLastModifiedTime(file,FileTime.from(afterClass));
        assertThrows(ApiException.class,()->WanceMdbReader.readStable(file,afterClass.plusSeconds(1),Duration.ofSeconds(2)));
        assertArrayEquals(new byte[]{1,2,3},WanceMdbReader.readStable(file,afterClass.plusSeconds(3),Duration.ofSeconds(2)).content());
    }
    @Test void centralSourcesBindToRegisteredComputerAndReloadWithoutTrustingStudentInput(@TempDir Path dir) throws Exception {
        Path first=Files.createDirectory(dir.resolve("one")),second=Files.createDirectory(dir.resolve("two")),config=dir.resolve("sources.properties");
        String bindings="station.ids=one,two\nstation.one.name=1号实验台\nstation.one.dir="+first+"\nstation.one.clients=192.168.1.11\nstation.two.name=2号实验台\nstation.two.dir="+second+"\nstation.two.clients=192.168.1.12\n";
        Files.writeString(config,bindings);var service=new WanceDataService("",config.toString());
        assertEquals(first,service.station("192.168.1.11").directory());assertEquals(second,service.station("::ffff:192.168.1.12").directory());
        assertEquals(403,assertThrows(ApiException.class,()->service.station("192.168.1.99")).getStatus());
        assertEquals(false,service.status("192.168.1.99").get("configured"));
        assertFalse(service.status("192.168.1.11").containsKey("dataDir"));
        Files.writeString(config,bindings.replace("192.168.1.11","192.168.1.13"));assertEquals(first,service.station("192.168.1.13").directory());
        Files.writeString(config,bindings.replace(second.toString(),first.toString()));assertThrows(ApiException.class,()->service.station("192.168.1.11"));
        Files.writeString(config,bindings.replace("192.168.1.12","192.168.1.11"));assertThrows(ApiException.class,()->service.station("192.168.1.11"));
    }
}
