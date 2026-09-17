package cn.ncut.lab.service;

import java.util.*;
import java.util.regex.*;

/** Explainable screening of narrative text. Shared numeric data and template lines are excluded. */
public final class ReportSimilarity {
    private ReportSimilarity() {}
    static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：、（）【】“”‘’]+",""); }
    static String narrative(String text,String exclusions) {
        StringBuilder out=new StringBuilder();
        for(String line:text.split("\\R")) {
            // Exclude table-like rows, equation-only lines, numeric measurements, and very short headings.
            String s=line.replaceAll("[0-9]+(?:[.,][0-9]+)?","");
            s=s.replaceAll("[^\\p{IsHan}A-Za-z]","");
            if(s.length()>=20)out.append(s).append('\n');
        }
        String value=normalize(out.toString());
        for(String ex:exclusions.split("\\R")) {String s=normalize(ex);if(s.length()>=4)value=value.replace(s,"");}
        return value;
    }
    static Set<String> shingles(String s) {
        Set<String> out=new LinkedHashSet<>(); for(int i=0;i+12<=s.length();i++)out.add(s.substring(i,i+12));return out;
    }
    public static Map<String,Object> compare(String later,String earlier,String exclusions) {
        String a=narrative(later,exclusions),b=narrative(earlier,exclusions);
        if(a.length()<80||b.length()<80)return Map.of("available",false,"percent",0,"evidence",List.of(),"reason","有效分析文字不足80字，需人工比对");
        Set<String> x=shingles(a),y=shingles(b);x.retainAll(y);
        double percent=Math.round(1000.0*x.size()/Math.max(1,shingles(a).size()))/10.0;
        List<String> evidence=new ArrayList<>();
        for(int i=0;i<a.length()-12 && evidence.size()<5;) {
            if(b.contains(a.substring(i,i+12))) {int end=i+12;while(end<a.length()&&end-i<160&&b.contains(a.substring(i,end+1)))end++;evidence.add(a.substring(i,end));i=end;}else i++;
        }
        return Map.of("available",true,"percent",percent,"evidence",evidence,"method","个人分析文字12字片段覆盖率；排除数字数据和配置的模板文字");
    }
}
