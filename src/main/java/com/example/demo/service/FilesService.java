package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class FilesService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final int SIGNED_URL_EXPIRATION_SECONDS = 15 * 60;
    private static final Set<String> SUPPORTED_FILE_TYPES = Set.of(
            "annotation",
            "vr_image",
            "map_image"
    );

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final String minioEndpoint;
    private final String minioBucket;

    public FilesService(
            JdbcTemplate jdbcTemplate,
            @Value("${minio.endpoint:http://localhost:9000}") String minioEndpoint,
            @Value("${minio.access-key:minioadmin}") String minioAccessKey,
            @Value("${minio.secret-key:minioadmin123}") String minioSecretKey,
            @Value("${minio.bucket:vr-view-bucket}") String minioBucket
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.minioEndpoint = trimTrailingSlash(minioEndpoint);
        this.minioBucket = minioBucket;
        this.minioClient = MinioClient.builder()
                .endpoint(this.minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
    }

    public boolean isSupportedFileType(String fileType) {
        return fileType != null && SUPPORTED_FILE_TYPES.contains(fileType.trim());
    }

    public Map<String, Object> upload(MultipartFile multipartFile, String fileType) {
        String originalFileName = sanitizeFileName(multipartFile.getOriginalFilename());
        String contentType = defaultContentType(multipartFile.getContentType());
        Long fileId = createFileRecord(originalFileName, fileType.trim(), multipartFile.getSize());
        String filePath = "/" + fileId;
        String objectName = fileId + "/" + UUID.randomUUID();

        try {
            ensureBucketExists();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioBucket)
                            .object(objectName)
                            .stream(
                                    multipartFile.getInputStream(),
                                    multipartFile.getSize(),
                                    -1
                            )
                            .contentType(contentType)
                            .build()
            );
            jdbcTemplate.update("""
                    UPDATE files
                       SET file_path = ?,
                           external_file_id = ?,
                           external_url = ?,
                           external_bucket = ?
                     WHERE file_id = ?
                    """,
                    filePath,
                    objectName,
                    minioEndpoint + "/" + minioBucket + "/" + objectName,
                    getS3BucketUrl(),
                    fileId
            );
        } catch (Exception exception) {
            markDeletedQuietly(fileId);
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "file_upload_failed",
                    "ファイルのアップロードに失敗しました"
            );
        }

        return toUploadResponse(getActiveFile(fileId));
    }

    public Map<String, Object> getDownloadUrl(Long fileId, Set<String> userRoles) {
        StoredFile file = getActiveFile(fileId);
        validateDownloadPermission(file, userRoles);

        String bucket = minioBucket;
        String objectName = file.externalFileId() == null ? file.filePath() : file.externalFileId();

        if (objectName == null || objectName.isBlank()) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象ファイルが見つかりません"
            );
        }

        try {
            String downloadUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectName)
                            .expiry(SIGNED_URL_EXPIRATION_SECONDS)
                            .build()
            );

            LocalDateTime expiresAt = LocalDateTime.now().plus(SIGNED_URL_EXPIRATION_SECONDS, ChronoUnit.SECONDS);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("file_id", file.fileId());
            data.put("file_url", downloadUrl);
            data.put("file_name", file.fileName());
            data.put("file_type", file.fileType());
            data.put("file_size", file.fileSize());
            data.put("created_at", format(file.createdAt()));
            data.put("expires_at", format(expiresAt));
            return data;
        } catch (ErrorResponseException exception) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象ファイルが見つかりません"
            );
        } catch (Exception exception) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "file_download_failed",
                    "ファイル取得URLの発行に失敗しました"
            );
        }
    }

    private void validateDownloadPermission(StoredFile file, Set<String> userRoles) {
        if (userRoles.contains("SYS_ADMIN")) {
            return;
        }

        if (userRoles.isEmpty() || !hasFilePermission(file, userRoles)) {
            throw new KeycloakAdminException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "ファイルを取得する権限がありません"
            );
        }
    }

    private boolean hasFilePermission(StoredFile file, Set<String> userRoles) {
        return switch (file.fileType()) {
            case "map_image" -> hasMapImagePermission(file.fileId(), userRoles);
            case "vr_image" -> hasVrImagePermission(file.fileId(), userRoles);
            case "annotation" -> hasAnnotationPermission(file.fileId(), userRoles);
            default -> false;
        };
    }

    private boolean hasMapImagePermission(Long fileId, Set<String> userRoles) {
        return permissionCount("""
                SELECT COUNT(*)
                  FROM maps m
                  JOIN facilities_tree ft
                    ON m.facility_id = ft.parent_id
                  JOIN facility_role_permission frp
                    ON frp.facility_id = ft.facility_id
                  JOIN permission_master pm
                    ON pm.permission_id = frp.permission_id
                 WHERE m.file_id = ?
                   AND pm.permission_name = 'vr_image_view'
                   AND frp.keycloak_role_id IN (%s)
                   AND ft.deleted_at IS NULL
                   AND frp.deleted_at IS NULL
                   AND pm.deleted_at IS NULL
                   AND m.deleted_at IS NULL
                """, fileId, userRoles) > 0;
    }

    private boolean hasVrImagePermission(Long fileId, Set<String> userRoles) {
        return permissionCount("""
                SELECT COUNT(*)
                  FROM facility_images fi
                  JOIN facilities_tree ft
                    ON fi.facility_id = ft.facility_id
                  JOIN facility_role_permission frp
                    ON frp.facility_id = ft.facility_id
                  JOIN permission_master pm
                    ON pm.permission_id = frp.permission_id
                 WHERE fi.file_id = ?
                   AND pm.permission_name = 'vr_image_view'
                   AND frp.keycloak_role_id IN (%s)
                   AND ft.deleted_at IS NULL
                   AND frp.deleted_at IS NULL
                   AND pm.deleted_at IS NULL
                   AND fi.deleted_at IS NULL
                """, fileId, userRoles) > 0;
    }

    private boolean hasAnnotationPermission(Long fileId, Set<String> userRoles) {
        try {
            return permissionCount("""
                    SELECT COUNT(*)
                      FROM annotation_files af
                      JOIN annotations a
                        ON af.annotation_id = a.annotation_id
                      JOIN facilities_tree ft
                        ON a.facility_id = ft.facility_id
                      JOIN facility_role_permission frp
                        ON frp.facility_id = ft.facility_id
                      JOIN permission_master pm
                        ON pm.permission_id = frp.permission_id
                     WHERE af.file_id = ?
                       AND pm.permission_name IN ('annotation_view', 'annotation_manage')
                       AND frp.keycloak_role_id IN (%s)
                       AND ft.deleted_at IS NULL
                       AND frp.deleted_at IS NULL
                       AND pm.deleted_at IS NULL
                       AND a.deleted_at IS NULL
                       AND af.deleted_at IS NULL
                    """, fileId, userRoles) > 0;
        } catch (BadSqlGrammarException exception) {
            return false;
        }
    }

    private int permissionCount(String sqlTemplate, Long fileId, Set<String> userRoles) {
        String placeholders = userRoles.stream()
                .map(role -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = sqlTemplate.formatted(placeholders);
        List<Object> params = new java.util.ArrayList<>();
        params.add(fileId);
        params.addAll(userRoles);

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    private Long createFileRecord(String originalFileName, String fileType, long fileSize) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO files (
                            file_name,
                            file_path,
                            file_type,
                            file_size,
                            storage_type,
                            external_bucket,
                            created_at
                        )
                        VALUES (?, '', ?, ?, 'minio', ?, CURRENT_TIMESTAMP)
                        """, new String[]{"file_id"});
                ps.setString(1, originalFileName);
                ps.setString(2, fileType);
                ps.setLong(3, fileSize);
                ps.setString(4, getS3BucketUrl());
                return ps;
            }, keyHolder);

            return Objects.requireNonNull(keyHolder.getKey()).longValue();
        } catch (DataAccessException exception) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "file_upload_failed",
                    "ファイル情報の保存に失敗しました"
            );
        }
    }

    private StoredFile getActiveFile(Long fileId) {
        List<StoredFile> results = jdbcTemplate.query("""
                SELECT file_id,
                       file_name,
                       file_path,
                       file_type,
                       file_size,
                       storage_type,
                       external_file_id,
                       external_url,
                       external_bucket,
                       created_at
                  FROM files
                 WHERE file_id = ?
                   AND deleted_at IS NULL
                """, (rs, rowNum) -> new StoredFile(
                rs.getLong("file_id"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getString("file_type"),
                rs.getLong("file_size"),
                rs.getString("storage_type"),
                rs.getString("external_file_id"),
                rs.getString("external_url"),
                rs.getString("external_bucket"),
                toLocalDateTime(rs.getTimestamp("created_at"))
        ), fileId);

        if (results.isEmpty()) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象ファイルが見つかりません"
            );
        }

        return results.getFirst();
    }

    private Map<String, Object> toUploadResponse(StoredFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_id", file.fileId());
        data.put("file_name", file.fileName());
        data.put("file_path", file.filePath());
        data.put("file_type", file.fileType());
        data.put("file_size", file.fileSize());
        data.put("s3_bucket", file.externalBucket() == null ? getS3BucketUrl() : file.externalBucket());
        data.put("created_at", format(file.createdAt()));
        return data;
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioBucket)
                        .build()
        );

        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioBucket)
                            .build()
            );
        }
    }

    private void markDeletedQuietly(Long fileId) {
        try {
            jdbcTemplate.update(
                    "UPDATE files SET deleted_at = CURRENT_TIMESTAMP WHERE file_id = ?",
                    fileId
            );
        } catch (Exception ignored) {
            // The API should still return the upload error if cleanup fails.
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.bin";
        }

        String sanitized = Path.of(fileName).getFileName().toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        if (sanitized.isBlank()) {
            return "upload.bin";
        }

        return sanitized.length() > 128 ? sanitized.substring(0, 128) : sanitized;
    }

    private String defaultContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }

        return contentType.toLowerCase(Locale.ROOT);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME_FORMATTER.format(value);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:9000";
        }

        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String getS3BucketUrl() {
        return minioEndpoint + "/" + minioBucket;
    }

    public record StoredFile(
            Long fileId,
            String fileName,
            String filePath,
            String fileType,
            Long fileSize,
            String storageType,
            String externalFileId,
            String externalUrl,
            String externalBucket,
            LocalDateTime createdAt
    ) {
    }
}
