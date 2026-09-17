package cn.ncut.lab.service;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class ParsingTest {
    @Test void csvQuotedFieldsUnitsAndInvalidRows() {
        var rows=ExperimentDataParser.rows("\uFEFF\"位移\",\"力\",备注\r\n0,0,\"第一行,备注\"\r\n1,1500,正常".getBytes(StandardCharsets.UTF_8),"test.csv");
        assertEquals("第一行,备注",rows.get(1).get(2));assertEquals(1.5,ExperimentDataParser.parse(rows,2,1,0,"N").get("maxF"));
        assertThrows(RuntimeException.class,()->ExperimentDataParser.parse(rows,1,1,0,"N"));
        assertThrows(RuntimeException.class,()->ExperimentDataParser.parse(rows,2,1,1,"N"));
        assertThrows(RuntimeException.class,()->ExperimentDataParser.parse(ExperimentDataParser.rows("d,f\n0,NaN\n1,2".getBytes(),"test.csv"),2,1,0,"N"));
    }
    @Test void gbkTabSeparatedText() {
        var rows=ExperimentDataParser.rows("位移\t力\n0\t0\n1\t2".getBytes(java.nio.charset.Charset.forName("GB18030")),"data.txt");assertEquals("位移",rows.get(0).get(0));assertEquals(2.0,ExperimentDataParser.parse(rows,2,1,0,"kN").get("maxF"));
    }
    @Test void docxTablesAreExtractedAndBrokenDocumentsStayManual() throws Exception {
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("实验结论与分析内容用于验证正文提取完整且可阅读，原始报告会被独立保存。");doc.createTable(1,1).getRow(0).getCell(0).setText("表格中的实验结果");doc.write(out);
            var parsed=new ReportParser().parse(out.toByteArray(),"report.docx");assertEquals("ready",parsed.status());assertTrue(parsed.text().contains("表格中的实验结果"));
        }
        assertEquals("needs_review",new ReportParser().parse(new byte[]{1,2,3},"bad.pdf").status());
    }
    @Test void scannedPdfWithoutOcrDoesNotProduceAutomaticGradeInput() throws Exception {
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var out=new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(out);assertEquals("needs_review",new ReportParser().parse(out.toByteArray(),"scan.pdf").status());
        }
    }
    @Test void templateAndShortNumericRecordsAreNotPlagiarismEvidence() {
        String template="这是学校统一要求使用的实验报告模板请填写实验名称日期与仪器信息".repeat(5);
        assertEquals(false,ReportSimilarity.compare(template,template,template).get("available"));
        assertEquals(false,ReportSimilarity.compare("F=1\nF=2\nF=3","F=1\nF=2\nF=3","").get("available"));
    }
}
