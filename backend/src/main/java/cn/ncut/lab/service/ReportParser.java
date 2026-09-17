package cn.ncut.lab.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

@Service
public class ReportParser {
    @Value("${report.ocr-command:}") private String ocrCommand="";
    @Value("${report.ocr-language:chi_sim+eng}") private String ocrLanguage="chi_sim+eng";
    public record Result(String text,String status,String warning,String mime) {}
    public Result parse(byte[] content,String name) {
        String ext=name.toLowerCase(Locale.ROOT); String mime="application/octet-stream";
        try {
            String text; List<String> warnings=new ArrayList<>();
            if(ext.endsWith(".pdf")) {
                mime="application/pdf";
                try(var doc=Loader.loadPDF(content)) {
                    if(doc.getNumberOfPages()>100)return new Result("","needs_review","超过100页，请人工查看或拆分文件",mime);
                    if(!doc.getCurrentAccessPermission().canExtractContent())return new Result("","needs_review","文件限制文本提取，请人工查看",mime);
                    PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                    StringBuilder all=new StringBuilder();PDFRenderer renderer=new PDFRenderer(doc);
                    for(int i=0;i<doc.getNumberOfPages();i++) {
                        stripper.setStartPage(i+1);stripper.setEndPage(i+1);String page=stripper.getText(doc);
                        if(page.replaceAll("\\s","").length()<20) {
                            if(!ocrCommand.isBlank()) {
                                try { page=ocr(renderer,i); } catch(Exception e) { warnings.add("第"+(i+1)+"页文字识别失败，需人工查看"); }
                            } else warnings.add("第"+(i+1)+"页文字不足，可能为扫描件；未配置OCR，请人工查看");
                        }
                        all.append("\n[第").append(i+1).append("页]\n").append(page);
                    }
                    text=all.toString();
                }
            } else if(ext.endsWith(".docx")) {
                mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                try(var doc=new XWPFDocument(new ByteArrayInputStream(content));var extractor=new XWPFWordExtractor(doc)) {
                    text=extractor.getText();
                    Matcher equations=Pattern.compile("<m:t(?:\\s[^>]*)?>(.*?)</m:t>",Pattern.DOTALL).matcher(doc.getDocument().xmlText());
                    StringBuilder math=new StringBuilder();while(equations.find())math.append(equations.group(1)).append(' ');
                    if(!math.isEmpty()){text+="\n[公式文本，结构请对照原件]\n"+math;warnings.add("检测到公式；公式结构与排版需对照原件复核");}
                }
            } else if(ext.endsWith(".doc")) {
                mime="application/msword";
                try(var doc=new HWPFDocument(new ByteArrayInputStream(content));var extractor=new WordExtractor(doc)) {text=extractor.getText();}
                warnings.add("旧版Word格式，复杂公式和排版请对照原件复核");
            } else return new Result("","invalid","请上传Word或PDF",mime);
            if(text.length()>200000) {text=text.substring(0,200000);warnings.add("正文超过20万字符，解析内容已截断，需人工查看全文");}
            if(text.replaceAll("\\s","").length()<30)warnings.add("未提取到足够正文，请人工查看原件");
            boolean incomplete=warnings.stream().anyMatch(w->w.contains("文字不足")||w.contains("失败")||w.contains("不足")||w.contains("截断"));
            return new Result(text,incomplete?"needs_review":"ready",String.join("；",warnings),mime);
        } catch(Exception e) {return new Result("","needs_review","文件无法完整解析，请人工查看原件或重新导出PDF",mime);}
    }
    private String ocr(PDFRenderer renderer,int page) throws Exception {
        Path dir=Files.createTempDirectory("lab-ocr-"); Path input=dir.resolve("page.png"),output=dir.resolve("result"),log=dir.resolve("ocr.log");
        try {
            ImageIO.write(renderer.renderImageWithDPI(page,150),"PNG",input.toFile());
            Process p=new ProcessBuilder(ocrCommand,input.toString(),output.toString(),"-l",ocrLanguage).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("OCR超时");}
            if(p.exitValue()!=0)throw new IOException("OCR失败");
            return Files.readString(dir.resolve("result.txt"),StandardCharsets.UTF_8);
        } finally {
            try(var files=Files.list(dir)){for(Path file:files.toList())Files.deleteIfExists(file);}Files.deleteIfExists(dir);
        }
    }
}
