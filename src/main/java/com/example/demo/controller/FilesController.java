package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.exception.KeycloakAdminException;
import com.example.demo.service.FilesService;
import com.example.demo.service.FilesService.StoredFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/files")
public class FilesController {

    private final FilesService filesService;

    public FilesController(FilesService filesService) {
        this.filesService = filesService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity
                    .badRequest()
                    .body(new ApiResponse<>(
                            false,
                            "validation_error",
                            "アップロード対象ファイルが指定されていません",
                            Map.of("file", List.of("この項目は必須です")),
                            null
                    ));
        }

        return ResponseEntity.ok(ApiResponse.ok(filesService.upload(file)));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<?> getFile(
            @PathVariable String fileId
    ) {
        StoredFile file = filesService.getFile(parseFileId(fileId));
        MediaType mediaType = parseMediaType(file.fileType());
        String dispositionType = isBrowserDisplayable(mediaType) ? "inline" : "attachment";

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.fileSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition
                                .builder(dispositionType)
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(file.resource());
    }

    private Long parseFileId(String fileId) {
        if (fileId == null
                || fileId.isBlank()
                || fileId.getBytes(StandardCharsets.UTF_8).length > 19
                || !fileId.matches("\\d+")) {
            throw invalidRequest();
        }

        try {
            return Long.parseLong(fileId);
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    private MediaType parseMediaType(String fileType) {
        try {
            return MediaType.parseMediaType(fileType);
        } catch (Exception exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean isBrowserDisplayable(MediaType mediaType) {
        return mediaType.getType().equals("image")
                || MediaType.APPLICATION_PDF.equals(mediaType)
                || mediaType.getType().equals("text");
    }

    private KeycloakAdminException invalidRequest() {
        return new KeycloakAdminException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "リクエストパラメータが不正です"
        );
    }
}
