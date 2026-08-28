package com.classschedule.importexport;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
@PreAuthorize("hasRole('PLANNER') and hasAuthority('IMPORT_EXECUTE')")
public class ImportController {
    private final WorkbookImportService workbookImportService;

    public ImportController(WorkbookImportService workbookImportService) {
        this.workbookImportService = workbookImportService;
    }

    @GetMapping("/templates/master-data.xlsx")
    public ResponseEntity<byte[]> masterDataTemplate() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(WorkbookImportService.XLSX_MIME));
        headers.setContentDisposition(ContentDisposition.attachment().filename(MasterDataSchemaRegistry.FILE_NAME, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(workbookImportService.masterDataTemplate());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@RequestPart("file") MultipartFile file, Authentication authentication) {
        return workbookImportService.preview(file, authentication.getName());
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResult> confirm(@Valid @RequestBody ImportConfirmRequest request, Authentication authentication) {
        try {
            return ResponseEntity.ok(workbookImportService.confirm(request.batchId(), authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ImportResult(request.batchId(), "REJECTED", 0, 1, exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ImportResult(request.batchId(), "ROLLED_BACK", 0, 1, exception.getMessage()));
        }
    }
}
