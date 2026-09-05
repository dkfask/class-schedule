package com.classschedule.schedule.export;

import com.classschedule.schedule.ScheduleRepository;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SchedulePdfExportService {
    private final ScheduleRepository schedules;
    private final String fontPath;
    private final String fontName;

    public SchedulePdfExportService(
            ScheduleRepository schedules,
            @Value("${app.pdf.font-path:}") String fontPath,
            @Value("${app.pdf.font-name:}") String fontName) {
        this.schedules = schedules;
        this.fontPath = fontPath == null ? "" : fontPath.trim();
        this.fontName = fontName == null ? "" : fontName.trim();
    }

    public byte[] validationPdf(
            com.classschedule.schedule.report.ValidationReportService.Report report) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont regular = loadFont(document, false);
            PDFont heading = loadFont(document, true);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(heading, 14);
                stream.newLineAtOffset(48, 770);
                stream.showText(
                        renderText(
                                "冲突报告 v" + report.versionId() + " / revision " + report.revision(),
                                heading));
                stream.setFont(regular, 9);
                int y = 735;
                for (var violation : report.violations()) {
                    if (y < 48) break;
                    stream.newLineAtOffset(0, -16);
                    stream.showText(
                            renderText(
                                    violation.code()
                                            + " | "
                                            + violation.severity()
                                            + " | "
                                            + violation.message(),
                                    regular));
                    y -= 16;
                }
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("冲突报告 PDF 导出失败", exception);
        }
    }

    public byte[] pdf(long versionId, String view, String resourceCode) {
        var version = schedules.findVersionFiltered(versionId, view, resourceCode);
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont regular = loadFont(document, false);
            PDFont heading = loadFont(document, true);
            int index = 0;
            while (index < version.assignments().size() || index == 0) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(heading, 16);
                stream.newLineAtOffset(48, 770);
                stream.showText(
                        renderText(
                                "课表版本 v" + version.id() + " / revision " + version.revision(),
                                heading));
                stream.setFont(regular, 9);
                stream.newLineAtOffset(0, -22);
                stream.showText(
                        renderText(
                                "状态：" + version.status() + " | 课程数：" + version.assignments().size(),
                                regular));
                int y = 720;
                while (index < version.assignments().size() && y >= 48) {
                    var assignment = version.assignments().get(index++);
                    stream.newLineAtOffset(0, -16);
                    stream.showText(
                            renderText(
                                    assignment.subjectName()
                                            + " | "
                                            + assignment.teacherCode()
                                            + " | "
                                            + assignment.studentGroupCode()
                                            + " | "
                                            + assignment.timeslotCode()
                                            + " | "
                                            + assignment.roomCode(),
                                    regular));
                }
                stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("PDF 导出失败", exception);
        }
    }

    private PDFont loadFont(PDDocument document, boolean bold) throws IOException {
        if (!fontPath.isBlank()) {
            File file = new File(fontPath);
            if (file.isFile()) {
                if (fontPath.toLowerCase().endsWith(".ttc")) {
                    try (TrueTypeCollection collection = new TrueTypeCollection(file)) {
                        TrueTypeFont selected =
                                fontName.isBlank()
                                        ? firstFont(collection)
                                        : collection.getFontByName(fontName);
                        if (selected != null) return PDType0Font.load(document, selected, true);
                    }
                } else {
                    return PDType0Font.load(document, file);
                }
            }
        }
        return new PDType1Font(
                bold
                        ? Standard14Fonts.FontName.HELVETICA_BOLD
                        : Standard14Fonts.FontName.HELVETICA);
    }

    private TrueTypeFont firstFont(TrueTypeCollection collection) throws IOException {
        final TrueTypeFont[] selected = new TrueTypeFont[1];
        collection.processAllFonts(
                font -> {
                    if (selected[0] == null) selected[0] = font;
                });
        return selected[0];
    }

    private String renderText(String value, PDFont font) {
        if (font instanceof PDType0Font) return value == null ? "" : value;
        return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
