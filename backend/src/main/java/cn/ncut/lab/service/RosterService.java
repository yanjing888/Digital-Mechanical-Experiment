package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

/**
 * 学生名单 Excel 解析与模板生成，等价于 Node 版 roster.js（用 Apache POI 实现）。
 */
@Service
public class RosterService {

    private static final Map<String, String> HEADER_MAP = new HashMap<>();
    static {
        HEADER_MAP.put("学号", "sid");
        HEADER_MAP.put("学生学号", "sid");
        HEADER_MAP.put("sid", "sid");
        HEADER_MAP.put("姓名", "name");
        HEADER_MAP.put("学生姓名", "name");
        HEADER_MAP.put("name", "name");
        HEADER_MAP.put("班级", "cls");
        HEADER_MAP.put("行政班", "cls");
        HEADER_MAP.put("cls", "cls");
        HEADER_MAP.put("class", "cls");
    }

    private String normalizeHeader(String h) {
        return h == null ? "" : h.trim().replaceAll("\\s+", "");
    }

    public List<Map<String, String>> parse(InputStream in) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return List.of();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return List.of();
            DataFormatter fmt = new DataFormatter();

            Map<Integer, String> colField = new HashMap<>();
            for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                if (cell == null) continue;
                String field = HEADER_MAP.get(normalizeHeader(fmt.formatCellValue(cell)));
                if (field != null) colField.put(c, field);
            }
            if (colField.isEmpty()) return List.of();

            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> mapped = new HashMap<>();
                for (Map.Entry<Integer, String> e : colField.entrySet()) {
                    Cell cell = row.getCell(e.getKey());
                    mapped.put(e.getValue(), cell == null ? "" : fmt.formatCellValue(cell).trim());
                }
                String sid = mapped.getOrDefault("sid", "");
                String name = mapped.getOrDefault("name", "");
                if (!sid.isBlank() && !name.isBlank()) {
                    Map<String, String> out = new HashMap<>();
                    out.put("sid", sid);
                    out.put("name", name);
                    String cls = mapped.getOrDefault("cls", "");
                    out.put("cls", cls.isBlank() ? "—" : cls);
                    rows.add(out);
                }
            }
            return rows;
        } catch (Exception e) {
            throw new ApiException(400, "无法解析名单文件：" + e.getMessage());
        }
    }

    public byte[] buildTemplate() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("学生名单");
            Row header = sheet.createRow(0);
            String[] cols = {"学号", "姓名", "班级"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            sheet.setColumnWidth(0, 14 * 256);
            sheet.setColumnWidth(1, 10 * 256);
            sheet.setColumnWidth(2, 12 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(500, "生成模板失败：" + e.getMessage());
        }
    }
}
