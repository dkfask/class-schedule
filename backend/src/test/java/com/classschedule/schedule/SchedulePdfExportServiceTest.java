package com.classschedule.schedule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.classschedule.schedule.export.SchedulePdfExportService;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SchedulePdfExportServiceTest {
    @Test
    @Timeout(10)
    void emptyScheduleProducesOnePage() throws Exception {
        try (var document = Loader.loadPDF(export(List.of()))) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    @Test
    void longSchedulePreservesEveryRowAcrossPages() throws Exception {
        var rows =
                IntStream.range(0, 100)
                        .mapToObj(
                                i -> {
                                    var row = mock(ScheduleAssignmentView.class);
                                    when(row.subjectName()).thenReturn("SUBJECT_" + i + "_END");
                                    return row;
                                })
                        .toList();
        try (var document = Loader.loadPDF(export(rows))) {
            assertTrue(document.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(document);
            for (int i = 0; i < 100; i++) {
                assertTrue(text.contains("SUBJECT_" + i + "_END"));
            }
        }
    }

    private byte[] export(List<ScheduleAssignmentView> rows) {
        var repository = mock(ScheduleRepository.class);
        when(repository.findVersionFiltered(1L, "CLASS", null))
                .thenReturn(
                        new ScheduleVersionView(1L, "DRAFT", "0hard/0medium/0soft", false, rows));
        return new SchedulePdfExportService(repository, "", "").pdf(1L, "CLASS", null);
    }
}
