package cn.ncut.lab.service;

import cn.ncut.lab.config.RosterSchema;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RosterServiceTest {
    RosterService roster=new RosterService();
    byte[] workbook(String... headers) throws Exception {
        try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=wb.createSheet();var header=sheet.createRow(0);var row=sheet.createRow(1);
            for(int i=0;i<headers.length;i++){header.createCell(i).setCellValue(headers[i]);row.createCell(i).setCellValue(switch(headers[i]){case "学号"->"00123456";case "姓名"->"Student";case "专业"->"Civil";default->"Class A";});}
            wb.write(out);return out.toByteArray();
        }
    }
    @Test void acceptsMajorAndPreservesStudentId() throws Exception {
        var rows=roster.parse(new ByteArrayInputStream(workbook("学号","姓名","专业","班级")));
        assertEquals("00123456",rows.get(0).get("sid"));assertEquals("Civil",rows.get(0).get("major"));assertEquals("Class A",rows.get(0).get("cls"));
    }
    @Test void olderTemplateDoesNotSupplyAnEmptyMajor() throws Exception {
        var row=roster.parse(new ByteArrayInputStream(workbook("学号","姓名","班级"))).get(0);
        assertFalse(row.containsKey("major"));
    }
    @Test void templateIncludesMajorAndClass() throws Exception {
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(roster.buildTemplate()))){var header=wb.getSheetAt(0).getRow(0);assertEquals("专业",header.getCell(2).getStringCellValue());assertEquals("班级",header.getCell(3).getStringCellValue());}
    }
    @Test void upgradesExistingRosterWithoutReplacingRowsAndCanRunTwice() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE students(sid VARCHAR(32))");db.update("INSERT INTO students VALUES('existing')");
        var schema=new RosterSchema(db);schema.run();schema.run();
        assertEquals("existing",db.queryForObject("SELECT sid FROM students",String.class));assertNull(db.queryForObject("SELECT major FROM students",String.class));
    }
}
