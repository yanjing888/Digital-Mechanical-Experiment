package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.ncut.lab.service.RunService.*;

@Service
public class GradebookExporter {
    public byte[] export(Map<String,Object> gradebook,String className,String query) {
        String selectedClass=str(className), search=str(query).toLowerCase(Locale.ROOT);
        List<Map<String,Object>> experiments=list(gradebook.get("experiments"));
        List<Map<String,Object>> students=list(gradebook.get("students")).stream().filter(student ->
                (selectedClass.isEmpty() || selectedClass.equals(str(student.get("cls"))))
                        && (str(student.get("name"))+" "+str(student.get("sid"))+" "+str(student.get("cls"))).toLowerCase(Locale.ROOT).contains(search)).toList();
        try (var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            Sheet sheet=wb.createSheet("实验成绩总表");
            int columns=3+experiments.size()*3;
            Font normal=wb.createFont();normal.setFontName("微软雅黑");normal.setFontHeightInPoints((short)11);
            Font bold=wb.createFont();bold.setFontName("微软雅黑");bold.setFontHeightInPoints((short)11);bold.setBold(true);
            Font titleFont=wb.createFont();titleFont.setFontName("微软雅黑");titleFont.setFontHeightInPoints((short)16);titleFont.setBold(true);
            CellStyle body=wb.createCellStyle();body.setFont(normal);body.setVerticalAlignment(VerticalAlignment.CENTER);body.setWrapText(true);
            body.setBorderBottom(BorderStyle.THIN);body.setBorderTop(BorderStyle.THIN);body.setBorderLeft(BorderStyle.THIN);body.setBorderRight(BorderStyle.THIN);
            body.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());body.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());body.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());body.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            CellStyle text=wb.createCellStyle();text.cloneStyleFrom(body);text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle score=wb.createCellStyle();score.cloneStyleFrom(body);score.setAlignment(HorizontalAlignment.RIGHT);score.setDataFormat(wb.createDataFormat().getFormat("0.##"));
            CellStyle header=wb.createCellStyle();header.cloneStyleFrom(body);header.setFont(bold);header.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setAlignment(HorizontalAlignment.CENTER);
            CellStyle title=wb.createCellStyle();title.setFont(titleFont);title.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle note=wb.createCellStyle();note.setFont(normal);note.setWrapText(true);note.setVerticalAlignment(VerticalAlignment.CENTER);
            sheet.setDisplayGridlines(false);sheet.setDefaultRowHeightInPoints(28);
            Row titleRow=sheet.createRow(0);titleRow.setHeightInPoints(36);put(titleRow,0,"实验成绩总表",title);sheet.addMergedRegion(new CellRangeAddress(0,0,0,columns-1));
            String exportedAt=ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Row info=sheet.createRow(1);info.setHeightInPoints(32);
            put(info,0,"班级："+(selectedClass.isEmpty()?"全部班级":selectedClass)+"    搜索："+(search.isEmpty()?"全部学生":str(query))+"    共 "+students.size()+" 人    导出时间："+exportedAt,note);
            sheet.addMergedRegion(new CellRangeAddress(1,1,0,columns-1));
            Row headings=sheet.createRow(2);headings.setHeightInPoints(38);
            put(headings,0,"班级",header);put(headings,1,"姓名",header);put(headings,2,"学号",header);
            for(int i=0;i<experiments.size();i++) {
                String name=str(experiments.get(i).get("name"));int c=3+i*3;
                put(headings,c,name+"\n操作成绩",header);put(headings,c+1,name+"\n报告成绩",header);put(headings,c+2,name+"\n状态",header);
            }
            for(int i=0;i<students.size();i++) {
                Map<String,Object> student=students.get(i);Row row=sheet.createRow(i+3);
                put(row,0,str(student.get("cls")).isEmpty()?"未填写":str(student.get("cls")),text);
                put(row,1,str(student.get("name")),text);put(row,2,str(student.get("sid")),text);
                for(int j=0;j<experiments.size();j++) {
                    Map<String,Object> value=map(map(student.get("scores")).get(str(experiments.get(j).get("id"))));int c=3+j*3;
                    numeric(row,c,value.get("operationScore"),score);numeric(row,c+1,value.get("reportScore"),score);
                    put(row,c+2,str(value.getOrDefault("status","未开课")),body);
                }
            }
            sheet.setColumnWidth(0,18*256);sheet.setColumnWidth(1,14*256);sheet.setColumnWidth(2,20*256);
            for(int c=3;c<columns;c++)sheet.setColumnWidth(c,18*256);
            sheet.createFreezePane(3,3);sheet.setAutoFilter(new CellRangeAddress(2,Math.max(2,students.size()+2),0,columns-1));
            sheet.setRepeatingRows(new CellRangeAddress(2,2,-1,-1));sheet.setFitToPage(true);sheet.getPrintSetup().setLandscape(true);sheet.getPrintSetup().setFitWidth((short)1);sheet.getPrintSetup().setFitHeight((short)0);
            wb.write(out);return out.toByteArray();
        } catch(Exception e) {throw new ApiException(500,"导出实验成绩失败，请稍后重试");}
    }
    private void put(Row row,int column,String value,CellStyle style) {Cell cell=row.createCell(column,CellType.STRING);cell.setCellValue(value);cell.setCellStyle(style);}
    private void numeric(Row row,int column,Object value,CellStyle style) {Cell cell=row.createCell(column);if(value instanceof Number number)cell.setCellValue(number.doubleValue());cell.setCellStyle(style);}
}
