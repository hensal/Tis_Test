package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private final JdbcTemplate jdbcTemplate;
    private final Path storageDirectory;
    private final MinioClient minioClient;
    private final String minioEndpoint;
    private final String minioBucket;

    public FilesService(
            JdbcTemplate jdbcTemplate,
            @Value("${files.storage-directory:uploads/files}") String storageDirectory,
            @Value("${minio.endpoint:http://localhost:9000}") String minioEndpoint,
            @Value("${minio.access-key:minioadmin}") String minioAccessKey,
            @Value("${minio.secret-key:minioadmin123}") String minioSecretKey,
            @Value("${minio.bucket:vr-view-bucket}") String minioBucket
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.minioEndpoint = trimTrailingSlash(minioEndpoint);
        this.minioBucket = minioBucket;
        this.minioClient = MinioClient.builder()
                .endpoint(this.minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
    }

    public Map<String, Object> upload(MultipartFile multipartFile) {
        String originalFileName = sanitizeFileName(multipartFile.getOriginalFilename());
        String contentType = defaultContentType(multipartFile.getContentType());
        String objectName = "files/" + UUID.randomUUID() + "_" + originalFileName;
        String externalUrl = minioEndpoint + "/" + minioBucket + "/" + objectName;

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
        } catch (Exception exception) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "file_upload_failed",
                    "ファイルのアップロードに失敗しました"
            );
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO files (
                        file_name,
                        file_path,
                        file_type,
                        file_size,
                        storage_type,
                        external_file_id,
                        external_url,
                        external_bucket,
                        created_at
                    )
                    VALUES (?, ?, ?, ?, 'minio', ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"file_id"});
            ps.setString(1, originalFileName);
            ps.setString(2, objectName);
            ps.setString(3, contentType);
            ps.setLong(4, multipartFile.getSize());
            ps.setString(5, objectName);
            ps.setString(6, externalUrl);
            ps.setString(7, minioBucket);
            return ps;
        }, keyHolder);

        Long fileId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return toResponse(getActiveFile(fileId));
    }

    public StoredFile getFile(Long fileId) {
        StoredFile file = getActiveFile(fileId);
        Resource resource;

        if ("minio".equalsIgnoreCase(file.storageType())) {
            resource = getMinioResource(file);
        } else {
            resource = getLocalResource(file);
        }

        return new StoredFile(
                file.fileId(),
                file.fileName(),
                file.filePath(),
                file.fileType(),
                file.fileSize(),
                file.storageType(),
                file.externalFileId(),
                file.externalUrl(),
                file.externalBucket(),
                file.createdAt(),
                resource
        );
    }

    private Resource getMinioResource(StoredFile file) {
        String bucket = file.externalBucket() == null ? minioBucket : file.externalBucket();
        String objectName = file.externalFileId() == null ? file.filePath() : file.externalFileId();

        if (objectName == null || objectName.isBlank()) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象ファイルが見つかりません"
            );
        }

        try {
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );

            return new InputStreamResource(inputStream);
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
                    "ファイルの取得に失敗しました"
            );
        }
    }

    private Resource getLocalResource(StoredFile file) {
        Resource resource = new FileSystemResource(file.filePath());

        if (!resource.exists() || !resource.isReadable()) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象ファイルが見つかりません"
            );
        }

        return resource;
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
                toLocalDateTime(rs.getTimestamp("created_at")),
                null
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

    private Map<String, Object> toResponse(StoredFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_id", file.fileId());
        data.put("file_name", file.fileName());
        data.put("file_type", file.fileType());
        data.put("file_size", file.fileSize());
        data.put("storage_type", file.storageType());
        data.put("external_file_id", file.externalFileId());
        data.put("external_url", file.externalUrl());
        data.put("external_bucket", file.externalBucket());
        data.put("created_at", format(file.createdAt()));
        data.put("updated_at", format(file.createdAt()));
        return data;
    }

    private void ensureStorageDirectoryExists() {
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException exception) {
            throw new KeycloakAdminException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "file_upload_failed",
                    "ファイル保存先の作成に失敗しました"
            );
        }
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

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.bin";
        }

        String sanitized = Path.of(fileName).getFileName().toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        return sanitized.isBlank() ? "upload.bin" : sanitized;
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
            LocalDateTime createdAt,
            Resource resource
    ) {
    }
}
