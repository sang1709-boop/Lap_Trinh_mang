package vn.ktd.taskhub.server.feature.progress;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import vn.ktd.taskhub.server.feature.TeamRequest;

public final class ReportExport {
    private ReportExport() {}

    public static JsonObject generate(TeamRequest q, JsonObject report) throws IOException {
        String format=q.field("format");
        var lines=new ArrayList<String>();
        lines.add("Báo cáo tiến độ nhóm "+q.id("groupId"));
        lines.add(q.field("from")+" đến "+q.field("to")+"   |   Chốt lúc "+report.get("asOf").getAsString()+" (UTC+7)");
        var total=report.getAsJsonObject("summary");
        lines.add("Hoàn thành "+total.get("completed")+"/"+total.get("total")+" việc   |   "+pct(total,"countPct")+" theo số việc");
        lines.add("Hoàn thành "+total.get("completedHours")+"/"+total.get("totalHours")+" giờ   |   "+pct(total,"hoursPct")+" theo giờ");
        lines.add("Đang trễ: "+total.get("lateOpen")+"   |   Hoàn thành muộn: "+total.get("lateCompleted")+"   |   Đã hủy: "+total.get("cancelled"));
        lines.add("Tiến độ từng thành viên");
        for(var e:report.getAsJsonArray("members")) {
            var r=e.getAsJsonObject();
            lines.add(r.get("name").getAsString()+(r.get("leftGroup").getAsBoolean()?" (đã rời nhóm)":"")
                    +"   |   "+r.get("completed")+"/"+r.get("total")+" việc   |   "+pct(r,"hoursPct")+" theo giờ"
                    +"   |   Đang trễ "+r.get("lateOpen")+"   |   Hoàn thành muộn "+r.get("lateCompleted"));
        }
        for(var e:report.getAsJsonArray("tasks")) {
            var r=e.getAsJsonObject();
            if(Progress.flag(r,"self_review")) lines.add("Tự duyệt · #"+r.get("task_id")+" "+r.get("title").getAsString());
        }
        byte[] bytes=switch(format) {
            case "docx" -> word(lines);
            case "pdf" -> pdf(lines);
            default -> throw TeamRequest.invalid();
        };
        if(bytes.length>700_000) throw new IllegalArgumentException("REPORT_TOO_LARGE");
        var result=new JsonObject();
        result.addProperty("format",format);
        result.addProperty("content",Base64.getEncoder().encodeToString(bytes));
        return result;
    }

    private static String pct(JsonObject r,String key) {
        return r.get(key).isJsonNull()?"Chưa có việc":String.format(Locale.ROOT,"%.1f%%",r.get(key).getAsDouble());
    }
    private static byte[] pdf(List<String> lines) throws IOException {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            String path=System.getProperty("ktd.report.font",File.separatorChar=='\\'?"C:/Windows/Fonts/arial.ttf":"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
            var font=PDType0Font.load(doc,Path.of(path).toFile());
            var wrapped=new ArrayList<String>();
            for(String line:lines) {
                String current="";
                for(String word:line.replaceAll("\\p{Cntrl}"," ").split(" +")) {
                    String next=current.isEmpty()?word:current+" "+word;
                    if(font.getStringWidth(next)*0.011>510 && !current.isEmpty()) { wrapped.add(current); current=word; }
                    else current=next;
                }
                wrapped.add(current);
            }
            for(int offset=0;offset<wrapped.size();offset+=40) {
                var page=new PDPage(PDRectangle.A4); doc.addPage(page);
                try(var out=new PDPageContentStream(doc,page)) {
                    out.beginText(); out.setFont(font,11); out.setLeading(18);
                    out.newLineAtOffset(40,790);
                    for(String line:wrapped.subList(offset,Math.min(offset+40,wrapped.size()))) {out.showText(line);out.newLine();}
                    out.endText();
                }
            }
            doc.save(bytes); return bytes.toByteArray();
        }
    }
    private static byte[] word(List<String> lines) throws IOException {
        var body=new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for(String text:lines) body.append("<w:p><w:r><w:t>").append(text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","")
                .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")).append("</w:t></w:r></w:p>");
        body.append("<w:sectPr/></w:body></w:document>");
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            put(zip,"[Content_Types].xml","""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>
                """);
            put(zip,"_rels/.rels","""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>
                """);
            put(zip,"word/document.xml",body.toString());
        }
        return bytes.toByteArray();
    }
    private static void put(ZipOutputStream zip,String name,String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
