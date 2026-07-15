package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.exception.KeycloakAdminException;
import com.example.demo.service.FilesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/files")
public class FilesController {

    private final FilesService filesService;

    public FilesController(FilesService filesService) {
        this.filesService = filesService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "file_type", required = false) String fileType
    ) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (file == null || file.isEmpty()) {
            errors.put("file", List.of("この項目は必須です"));
        }

        if (!filesService.isSupportedFileType(fileType)) {
            errors.put("file_type", List.of("annotation、vr_image、map_image のいずれかを指定してください"));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiResponse<>(
                    false,
                    "validation_error",
                    "リクエストパラメータが不正です",
                    errors,
                    null
            ));
        }

        return ResponseEntity.ok(ApiResponse.ok(filesService.upload(file, fileType)));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFile(
            @PathVariable String fileId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.ok(filesService.getDownloadUrl(
                parseFileId(fileId),
                extractRoleNames(authentication)
        )));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Void>> missingFileId() {
        throw new KeycloakAdminException(
                HttpStatus.NOT_FOUND,
                "not_found",
                "対象ファイルが見つかりません"
        );
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

    private KeycloakAdminException invalidRequest() {
        return new KeycloakAdminException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "リクエストパラメータが不正です"
        );
    }

    private Set<String> extractRoleNames(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }

        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_")
                        ? authority.substring("ROLE_".length())
                        : authority)
                .collect(Collectors.toSet());
    }
}
