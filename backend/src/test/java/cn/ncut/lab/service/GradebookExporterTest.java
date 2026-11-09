package cn.ncut.lab.service;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GradebookExporterTest {
    private Map<String,Object> student(String sid,String name,String cls,Map<String,Object> scores) {
        return Map.of("sid",sid,"name",name,"cls",cls,"scores",scores);
    }
    private Map<String,Object> book(List<Map<String,Object>> students) {
        return Map.of("experiments",List.of(Map.of("id","combined","name","拉伸压缩实验"),Map.of("id","torsion","name","扭转实验")),"students",students);
    }
    @Test void exportPreservesTextIdsNumericGradesAndBlankMissingScores() throws Exception {
        var scores=Map.<String,Object>of("combined",Map.of("operationScore",92.5,"reportScore",87,"status","已完成"));
        byte[] bytes=new GradebookExporter().export(book(List.of(student("00123456789012345678","学生甲","土木一班",scores))),"","");
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=wb.getSheet("实验成绩总表");var row=sheet.getRow(3);
            assertEquals("00123456789012345678",row.getCell(2).getStringCellValue());assertEquals(CellType.STRING,row.getCell(2).getCellType());
            assertEquals(CellType.NUMERIC,row.getCell(3).getCellType());assertEquals(92.5,row.getCell(3).getNumericCellValue());
            assertEquals(87,row.getCell(4).getNumericCellValue());assertEquals("已完成",row.getCell(5).getStringCellValue());
            assertEquals(CellType.BLANK,row.getCell(6).getCellType());assertEquals(CellType.BLANK,row.getCell(7).getCellType());
            assertEquals("未开课",row.getCell(8).getStringCellValue());
            assertEquals(3,sheet.getPaneInformation().getHorizontalSplitPosition());assertEquals(3,sheet.getPaneInformation().getVerticalSplitPosition());
            assertEquals("A3:I4",sheet.getCTWorksheet().getAutoFilter().getRef());
            assertEquals("拉伸压缩实验\n操作成绩",sheet.getRow(2).getCell(3).getStringCellValue());
        }
    }
    @Test void classAndSearchFiltersMatchTheVisibleRows() throws Exception {
        var data=book(List.of(student("001","Alice","土木一班",Map.of()),student("002","Alice","土木二班",Map.of()),student("003","Bob","土木一班",Map.of())));
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(new GradebookExporter().export(data,"土木一班"," ALICE ")))) {
            var sheet=wb.getSheetAt(0);assertEquals(4,sheet.getPhysicalNumberOfRows());
            assertEquals("001",sheet.getRow(3).getCell(2).getStringCellValue());
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("共 1 人"));
        }
    }
    @Test void emptyFilterResultRetainsOnlyTheWorkbookHeaders() throws Exception {
        var data=book(List.of(student("001","学生甲","土木一班",Map.of())));
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(new GradebookExporter().export(data,"不存在的班级","")))) {
            assertEquals(3,wb.getSheetAt(0).getPhysicalNumberOfRows());
            assertTrue(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue().contains("共 0 人"));
        }
    }
    @Test void importedNamesAreWrittenAsTextRatherThanExcelFormulas() throws Exception {
        var data=book(List.of(student("0001","=1+1","土木一班",Map.of())));
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(new GradebookExporter().export(data,"","")))) {
            var cell=wb.getSheetAt(0).getRow(3).getCell(1);
            assertEquals(CellType.STRING,cell.getCellType());assertEquals("=1+1",cell.getStringCellValue());
        }
    }
}
