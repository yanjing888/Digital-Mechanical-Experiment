package cn.ncut.lab.service;

import cn.ncut.lab.web.ApiException;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Explicit column/unit mapping; original bytes are stored separately. */
public final class ExperimentDataParser {
    private ExperimentDataParser() {}
    public static List<List<String>> rows(byte[] bytes, String name) {
        try {
            if (name.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$")) {
                try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                    List<List<String>> out = new ArrayList<>();
                    DataFormatter format = new DataFormatter(Locale.ROOT);
                    for (Row row : book.getSheetAt(0)) {
                        if (out.size() >= 100001) throw new ApiException(400,"数据超过10万行，请分段导出");
                        List<String> values = new ArrayList<>();
                        if (row.getLastCellNum() > 256) throw new ApiException(400,"列数超过256列");
                        for (int i=0; i<row.getLastCellNum(); i++) values.add(format.formatCellValue(row.getCell(i)));
                        out.add(values);
                    }
                    return out;
                }
            }
            if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(csv|txt|tsv)$")) throw new ApiException(400,"支持 CSV、TXT、TSV、XLS、XLSX");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException ex) { text = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            text = text.replace("\uFEFF", "");
            String sample = String.join("\n",text.lines().limit(40).toList());
            char delimiter = sample.contains("\t") ? '\t' : sample.contains(";") ? ';' : sample.contains(",") ? ',' : ' ';
            if(delimiter==' ') {
                List<List<String>> rows=text.lines().filter(s->!s.isBlank()).map(s->Arrays.asList(s.trim().split("\\s+"))).toList();
                if(rows.size()>100000 || rows.stream().anyMatch(r->r.size()>256))throw new ApiException(400,"文件行列数超限");
                return rows;
            }
            List<List<String>> out = new ArrayList<>();
            List<String> row = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
            for (int i=0; i<text.length(); i++) {
                char c=text.charAt(i);
                if(c=='"') { if(quoted && i+1<text.length() && text.charAt(i+1)=='"') { value.append('"'); i++; } else quoted=!quoted; }
                else if(c==delimiter && !quoted) { row.add(value.toString().trim()); value.setLength(0); }
                else if((c=='\n'||c=='\r') && !quoted) {
                    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                    row.add(value.toString().trim()); value.setLength(0);
                    if(row.stream().anyMatch(s->!s.isBlank())) out.add(row);
                    row=new ArrayList<>();
                } else value.append(c);
                if(out.size()>100000 || row.size()>256) throw new ApiException(400,"文件行列数超限");
            }
            if(quoted) throw new ApiException(400,"CSV引号未闭合");
            row.add(value.toString().trim()); if(row.stream().anyMatch(s->!s.isBlank())) out.add(row);
            return out;
        } catch(ApiException e) { throw e; } catch(Exception e) { throw new ApiException(400,"无法解析数据文件，请检查格式或转为CSV"); }
    }
    public static Map<String,Object> parse(List<List<String>> rows, int start, int force, int displacement, String unit) {
        if(start<1 || start>rows.size() || force<0 || displacement<0 || force==displacement) throw new ApiException(400,"请选择正确的数据起始行和两个不同的数据列");
        if(!Set.of("N","kN").contains(unit)) throw new ApiException(400,"请选择力的单位N或kN；位移须为mm");
        List<Map<String,Object>> points=new ArrayList<>(); double maxF=-Double.MAX_VALUE,maxD=-Double.MAX_VALUE;
        for(int i=start-1;i<rows.size();i++) {
            List<String> r=rows.get(i);
            if(r.stream().allMatch(String::isBlank)) continue;
            try {
                double f=Double.parseDouble(r.get(force)), d=Double.parseDouble(r.get(displacement));
                if(!Double.isFinite(f)||!Double.isFinite(d)) throw new NumberFormatException();
                if(unit.equals("N")) f/=1000;
                points.add(Map.of("f",f,"d",d)); maxF=Math.max(maxF,f); maxD=Math.max(maxD,d);
            } catch(Exception e) { throw new ApiException(400,"第"+(i+1)+"行不是有效数值；请检查起始行和列映射"); }
        }
        if(points.size()<2) throw new ApiException(400,"至少需要两个有效数据点");
        return Map.of("points",points,"maxF",maxF,"maxD",maxD,"forceUnit","kN","displacementUnit","mm","pointCount",points.size());
    }
}
