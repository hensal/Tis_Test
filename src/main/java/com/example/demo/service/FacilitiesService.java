package com.example.demo.service;

import com.example.demo.exception.KeycloakAdminException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FacilitiesService {

    private static final ZoneId JAPAN_ZONE = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final long FACILITY_EDIT_PERMISSION_ID = 10L;

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<FacilityRecord> facilityRowMapper = (rs, rowNum) -> {
        FacilityRecord facility = new FacilityRecord();
        facility.facilityId = rs.getLong("facility_id");
        facility.parentId = nullableLong(rs, "parent_id");
        facility.facilityName = rs.getString("facility_name");
        facility.treeLevel = nullableInteger(rs, "tree_level");
        facility.isLeaf = rs.getBoolean("is_leaf");
        facility.publishMode = toPublishMode(rs.getString("public_flag"));
        facility.isOutdoor = rs.getBoolean("outer_flag");
        facility.detail = rs.getString("facility_description");
        facility.hasPermission = rs.getBoolean("authority_setting_flg");
        facility.createdAt = toLocalDateTime(rs.getTimestamp("created_at"));
        facility.updatedAt = toLocalDateTime(rs.getTimestamp("updated_at"));
        facility.deletedAt = toLocalDateTime(rs.getTimestamp("deleted_at"));
        return facility;
    };

    public FacilitiesService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listFacilities(
            Long parentId,
            String keyword
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                  FROM facilities_tree
                 WHERE deleted_at IS NULL
                """);

        List<Object> params;

        if (parentId != null) {
            sql.append(" AND parent_id = ?");
            params = List.of(parentId);
        } else if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND facility_name LIKE ?");
            params = List.of("%" + keyword + "%");
        } else {
            params = Collections.emptyList();
        }

        sql.append(" ORDER BY sort_order ASC, facility_id ASC");

        return jdbcTemplate.query(sql.toString(), facilityRowMapper, params.toArray())
                .stream()
                .map(this::toListResponse)
                .toList();
    }

    public Map<String, Object> getFacility(Long facilityId) {
        return toDetailResponse(getActiveFacility(facilityId));
    }

    public Map<String, Object> getViewHtml(Long facilityId) {
        FacilityRecord facility = getActiveFacility(facilityId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facility.facilityId);
        data.put("content_html", "<h1>" + facility.facilityName
                + "</h1><p>" + defaultValue(facility.detail) + "</p>");
        data.put("updated_at", format(facility.updatedAt));

        return data;
    }

    @Transactional
    public Map<String, Object> createFacility(
            Map<String, Object> request,
            Set<String> userRoles
    ) {
        validateCreateRequestKeys(request);

        Long parentId = optionalLong(request.get("parent_id"));
        FacilityRecord parent = parentId == null ? null : getActiveFacility(parentId);
        ensureCreatePermission(parentId, userRoles);
        Long targetFacilityId = optionalLong(request.get("target_facility_id"));
        String facilityName = requiredFacilityName(request);
        String publicFlag = resolvePublicFlag(request);
        boolean isOutdoor = optionalBoolean(request.getOrDefault("outer_flag", request.get("is_outdoor")), false);
        boolean hasPermission = optionalBoolean(request.get("authority_setting_flag"), false);
        String detail = optionalDescription(request);
        int treeLevel = parent == null || parent.treeLevel == null ? 1 : parent.treeLevel + 1;
        int sortOrder = createSortOrder(parentId, targetFacilityId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO facilities_tree (
                        parent_id,
                        facility_name,
                        sort_order,
                        tree_level,
                        is_leaf,
                        public_flag,
                        outer_flag,
                        facility_description,
                        authority_setting_flg,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"facility_id"});
            if (parentId == null) {
                ps.setObject(1, null);
            } else {
                ps.setLong(1, parentId);
            }
            ps.setString(2, facilityName);
            ps.setInt(3, sortOrder);
            ps.setInt(4, treeLevel);
            ps.setString(5, publicFlag);
            ps.setBoolean(6, isOutdoor);
            ps.setString(7, detail);
            ps.setBoolean(8, hasPermission);
            return ps;
        }, keyHolder);

        Long facilityId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        insertCreateChildren(facilityId, parentId, request);
        updateParentLeafState(parentId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facilityId);
        return data;
    }

    @Transactional
    public Map<String, Object> updateFacility(
            Long facilityId,
            Map<String, Object> request
    ) {
        FacilityRecord facility = getActiveFacility(facilityId);
        ensureNotConflicted(facility, request);

        if (request.containsKey("facility_name")) {
            jdbcTemplate.update("""
                    UPDATE facilities_tree
                       SET facility_name = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE facility_id = ? AND deleted_at IS NULL
                    """, requiredFacilityName(request), facilityId);
        }

        if (request.containsKey("public_flag") || request.containsKey("publish_mode")) {
            jdbcTemplate.update("""
                    UPDATE facilities_tree
                       SET public_flag = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE facility_id = ? AND deleted_at IS NULL
                    """, resolvePublicFlag(request), facilityId);
        }

        if (request.containsKey("outer_flag") || request.containsKey("is_outdoor")) {
            jdbcTemplate.update("""
                    UPDATE facilities_tree
                       SET outer_flag = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE facility_id = ? AND deleted_at IS NULL
                    """, optionalBoolean(request.getOrDefault("outer_flag", request.get("is_outdoor")), false), facilityId);
        }

        if (request.containsKey("facility_description") || request.containsKey("detail")) {
            jdbcTemplate.update("""
                    UPDATE facilities_tree
                       SET facility_description = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE facility_id = ? AND deleted_at IS NULL
                    """, optionalDescription(request), facilityId);
        }

        if (request.containsKey("authority_setting_flag")) {
            jdbcTemplate.update("""
                    UPDATE facilities_tree
                       SET authority_setting_flg = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE facility_id = ? AND deleted_at IS NULL
                    """, optionalBoolean(request.get("authority_setting_flag"), false), facilityId);
        }

        if (request.containsKey("vr_image")) {
            softDeleteByFacility("facility_images", facilityId);
            insertVrImage(facilityId, objectList(request.get("vr_image"), "vr_image", 1));
        }

        if (request.containsKey("map")) {
            softDeleteByFacility("maps", facilityId);
            insertMap(facilityId, objectValue(request.get("map"), "map", false));
        }

        if (request.containsKey("roles")) {
            softDeleteByFacility("facility_role_permission", facilityId);
            insertRoles(facilityId, objectList(request.get("roles"), "roles", Integer.MAX_VALUE));
        }

        if (request.containsKey("equipments")) {
            softDeleteByFacility("equipments", facilityId);
            insertEquipments(facilityId, objectList(request.get("equipments"), "equipments", Integer.MAX_VALUE));
        }

        if (request.containsKey("map_points")) {
            softDeleteMapPointsForTarget(facilityId);
            insertMapPoint(facilityId, facility.parentId, objectList(request.get("map_points"), "map_points", 1));
        }

        return toDetailResponse(getActiveFacility(facilityId));
    }

    @Transactional
    public void deleteFacility(Long facilityId) {
        FacilityRecord facility = getActiveFacility(facilityId);

        jdbcTemplate.update("""
                UPDATE facilities_tree
                   SET deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE facility_id = ?
                    OR parent_id = ?
                """, facilityId, facilityId);

        updateParentLeafState(facility.parentId);
    }

    @Transactional
    public Map<String, Object> moveFacility(
            Long facilityId,
            Map<String, Object> request
    ) {
        FacilityRecord facility = getActiveFacility(facilityId);
        ensureNotConflicted(facility, request);
        Long parentId = optionalLong(request.get("parent_id"));
        FacilityRecord parent = parentId == null ? null : getActiveFacility(parentId);
        Long targetFacilityId = optionalLong(request.get("target_facility_id"));

        if (facilityId.equals(parentId) || facilityId.equals(targetFacilityId)) {
            throw invalidRequest("facility move target is invalid");
        }

        ensureNotDescendant(facilityId, parentId);
        int oldTreeLevel = facility.treeLevel == null ? 1 : facility.treeLevel;
        int treeLevel = parent == null || parent.treeLevel == null ? 1 : parent.treeLevel + 1;
        int sortOrder = createSortOrder(parentId, targetFacilityId);

        jdbcTemplate.update("""
                UPDATE facilities_tree
                   SET parent_id = ?,
                       sort_order = ?,
                       tree_level = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE facility_id = ? AND deleted_at IS NULL
                """, parentId, sortOrder, treeLevel, facilityId);

        updateDescendantTreeLevels(facilityId, treeLevel - oldTreeLevel);

        updateParentLeafState(facility.parentId);
        updateParentLeafState(parentId);

        return toDetailResponse(getActiveFacility(facilityId));
    }

    @Transactional
    public Map<String, Object> copyFacility(
            Long facilityId,
            Map<String, Object> request
    ) {
        FacilityRecord source = getActiveFacility(facilityId);
        ensureNotConflicted(source, request);
        Long parentId = optionalLong(request.get("parent_id"));
        FacilityRecord parent = parentId == null ? null : getActiveFacility(parentId);
        Long targetFacilityId = optionalLong(request.get("target_facility_id"));
        int sortOrder = createSortOrder(parentId, targetFacilityId);
        int treeLevel = parent == null || parent.treeLevel == null ? 1 : parent.treeLevel + 1;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO facilities_tree (
                        parent_id,
                        facility_name,
                        sort_order,
                        tree_level,
                        is_leaf,
                        public_flag,
                        outer_flag,
                        facility_description,
                        authority_setting_flg,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"facility_id"});
            if (parentId == null) {
                ps.setObject(1, null);
            } else {
                ps.setLong(1, parentId);
            }
            ps.setString(2, source.facilityName);
            ps.setInt(3, sortOrder);
            ps.setInt(4, treeLevel);
            ps.setString(5, toPublicFlag(source.publishMode));
            ps.setBoolean(6, Boolean.TRUE.equals(source.isOutdoor));
            ps.setString(7, source.detail);
            ps.setBoolean(8, Boolean.TRUE.equals(source.hasPermission));
            return ps;
        }, keyHolder);

        Long copiedFacilityId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        copyRelatedRows(facilityId, copiedFacilityId);
        updateParentLeafState(parentId);

        return toDetailResponse(getActiveFacility(copiedFacilityId));
    }

    public Map<String, Object> getVrView(Long facilityId) {
        FacilityRecord facility = getActiveFacility(facilityId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facility.facilityId);
        data.put("facility_name", facility.facilityName);
        data.put("has_vr_image", false);
        data.put("vr_image", null);
        data.put("move_points", List.of());
        data.put("annotation_points", List.of());
        data.put("equipment_points", List.of());
        data.put("mini_map", null);

        return data;
    }

    private Map<String, Object> toListResponse(FacilityRecord facility) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facility.facilityId);
        data.put("parent_id", facility.parentId);
        data.put("facility_name", facility.facilityName);
        data.put("publish_mode", facility.publishMode);
        data.put("is_outdoor", facility.isOutdoor);
        data.put("has_vr_image", hasVrImage(facility.facilityId));
        data.put("has_emergency_annotation", hasAnnotation(facility.facilityId, "02"));
        data.put("has_normal_annotation", hasNormalAnnotation(facility.facilityId));
        data.put("has_equipment", hasEquipment(facility.facilityId));
        data.put("created_at", format(facility.createdAt));
        data.put("updated_at", format(facility.updatedAt));
        data.put("deleted_at", format(facility.deletedAt));

        return data;
    }

    private Map<String, Object> toDetailResponse(FacilityRecord facility) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facility.facilityId);
        data.put("parent_id", facility.parentId);
        data.put("facility_name", facility.facilityName);
        data.put("tree_level", facility.treeLevel);
        data.put("is_leaf", facility.isLeaf);
        data.put("publish_mode", facility.publishMode);
        data.put("is_outdoor", facility.isOutdoor);
        data.put("detail", facility.detail);
        data.put("has_permission", facility.hasPermission);
        data.put("images", getImages(facility.facilityId));
        data.put("maps", getMaps(facility.facilityId));
        data.put("map_points", getMapPoints(facility.facilityId));
        data.put("roles", getRoles(facility.facilityId));
        data.put("equipments", getEquipments(facility.facilityId));
        data.put("annotations", getAnnotations(facility.facilityId));
        data.put("has_vr_image", hasVrImage(facility.facilityId));
        data.put("has_emergency_annotation", hasAnnotation(facility.facilityId, "02"));
        data.put("has_normal_annotation", hasNormalAnnotation(facility.facilityId));
        data.put("has_equipment", hasEquipment(facility.facilityId));
        data.put("created_at", format(facility.createdAt));
        data.put("updated_at", format(facility.updatedAt));
        data.put("deleted_at", format(facility.deletedAt));

        return data;
    }

    private List<Map<String, Object>> getImages(Long facilityId) {
        return safeQuery("""
                SELECT image_id,
                       file_id,
                       image_name,
                       shooting_height,
                       ceiling_height
                  FROM facility_images
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                 ORDER BY image_id ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("image_id", rs.getLong("image_id"));
            data.put("file_id", rs.getLong("file_id"));
            data.put("image_name", rs.getString("image_name"));
            data.put("shooting_height", rs.getInt("shooting_height"));
            data.put("ceiling_height", rs.getInt("ceiling_height"));
            return data;
        }, facilityId);
    }

    private List<Map<String, Object>> getMaps(Long facilityId) {
        return safeQuery("""
                SELECT m.map_id,
                       m.file_id,
                       m.map_name
                  FROM facilities_tree facility
                  LEFT JOIN facilities_tree parent
                    ON facility.parent_id = parent.facility_id
                  JOIN maps m
                    ON COALESCE(parent.facility_id, facility.facility_id) = m.facility_id
                 WHERE facility.facility_id = ?
                   AND facility.deleted_at IS NULL
                   AND (parent.facility_id IS NULL OR parent.deleted_at IS NULL)
                   AND m.deleted_at IS NULL
                 ORDER BY m.map_id ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("map_id", rs.getLong("map_id"));
            data.put("file_id", rs.getLong("file_id"));
            data.put("map_name", rs.getString("map_name"));
            return data;
        }, facilityId);
    }

    private List<Map<String, Object>> getMapPoints(Long facilityId) {
        return safeQuery("""
                SELECT mp.map_point_id,
                       mp.map_id,
                       mp.x,
                       mp.y
                  FROM map_points mp
                 WHERE mp.map_id IN (
                       SELECT m.map_id
                         FROM facilities_tree facility
                         LEFT JOIN facilities_tree parent
                           ON facility.parent_id = parent.facility_id
                         JOIN maps m
                           ON COALESCE(parent.facility_id, facility.facility_id) = m.facility_id
                        WHERE facility.facility_id = ?
                          AND facility.deleted_at IS NULL
                          AND (parent.facility_id IS NULL OR parent.deleted_at IS NULL)
                          AND m.deleted_at IS NULL
                 )
                   AND mp.deleted_at IS NULL
                 ORDER BY mp.map_id ASC, mp.map_point_id ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("map_point_id", rs.getLong("map_point_id"));
            data.put("map_id", rs.getLong("map_id"));
            data.put("x_coord", rs.getDouble("x"));
            data.put("y_coord", rs.getDouble("y"));
            return data;
        }, facilityId);
    }

    private List<Map<String, Object>> getRoles(Long facilityId) {
        return safeQuery("""
                SELECT frp.keycloak_role_id,
                       frp.keycloak_role_name,
                       pm.permission_name
                  FROM facility_role_permission frp
                  JOIN permission_master pm
                    ON frp.permission_id = pm.permission_id
                 WHERE frp.facility_id = ?
                   AND frp.deleted_at IS NULL
                   AND pm.deleted_at IS NULL
                 ORDER BY frp.facility_role_permission_id ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keycloak_role_id", rs.getString("keycloak_role_id"));
            data.put("keycloak_role_name", rs.getString("keycloak_role_name"));
            data.put("permission_code", rs.getString("permission_name"));
            return data;
        }, facilityId);
    }

    private List<Map<String, Object>> getEquipments(Long facilityId) {
        return safeQuery("""
                SELECT e.equipment_id,
                       em.equipment_name,
                       e.yaw,
                       e.pitch
                  FROM facilities_tree ft
                  JOIN equipments e
                    ON ft.facility_id = e.facility_id
                  JOIN equipment_masters em
                    ON e.equipment_master_id = em.equipment_master_id
                 WHERE ft.facility_id = ?
                   AND ft.deleted_at IS NULL
                   AND e.deleted_at IS NULL
                   AND em.deleted_at IS NULL
                 ORDER BY e.equipment_id ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("equipment_id", rs.getLong("equipment_id"));
            data.put("equipment_name", rs.getString("equipment_name"));
            data.put("vr_x", rs.getDouble("yaw"));
            data.put("vr_y", rs.getDouble("pitch"));
            return data;
        }, facilityId);
    }

    private List<Map<String, Object>> getAnnotations(Long facilityId) {
        return safeQuery("""
                SELECT a.annotation_id,
                       a.annotation_title,
                       a.annotation_type
                  FROM facilities_tree ft
                  JOIN annotations a
                    ON ft.facility_id = a.facility_id
                 WHERE ft.facility_id = ?
                   AND ft.deleted_at IS NULL
                   AND a.deleted_at IS NULL
                 ORDER BY a.annotation_type ASC, a.created_at ASC
                """, (rs, rowNum) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("annotation_id", rs.getLong("annotation_id"));
            data.put("title", rs.getString("annotation_title"));
            data.put("type", toAnnotationType(rs.getString("annotation_type")));
            return data;
        }, facilityId);
    }

    private boolean hasVrImage(Long facilityId) {
        return count("""
                SELECT COUNT(*)
                  FROM facility_images
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, facilityId) > 0;
    }

    private boolean hasAnnotation(Long facilityId, String annotationType) {
        return count("""
                SELECT COUNT(*)
                  FROM facilities_tree ft
                  JOIN annotations a
                    ON ft.facility_id = a.facility_id
                 WHERE ft.facility_id = ?
                   AND a.annotation_type = ?
                   AND ft.deleted_at IS NULL
                   AND a.deleted_at IS NULL
                """, facilityId, annotationType) > 0;
    }

    private boolean hasNormalAnnotation(Long facilityId) {
        return count("""
                SELECT COUNT(*)
                  FROM facilities_tree ft
                  JOIN annotations a
                    ON ft.facility_id = a.facility_id
                 WHERE ft.facility_id = ?
                   AND a.annotation_type <> '02'
                   AND ft.deleted_at IS NULL
                   AND a.deleted_at IS NULL
                """, facilityId) > 0;
    }

    private boolean hasEquipment(Long facilityId) {
        return count("""
                SELECT COUNT(*)
                  FROM facilities_tree ft
                  JOIN equipments e
                    ON ft.facility_id = e.facility_id
                 WHERE ft.facility_id = ?
                   AND ft.deleted_at IS NULL
                   AND e.deleted_at IS NULL
                """, facilityId) > 0;
    }

    private int count(String sql, Object... args) {
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return count == null ? 0 : count;
        } catch (BadSqlGrammarException exception) {
            return 0;
        }
    }

    private List<Map<String, Object>> safeQuery(
            String sql,
            RowMapper<Map<String, Object>> rowMapper,
            Object... args
    ) {
        try {
            return jdbcTemplate.query(sql, rowMapper, args);
        } catch (BadSqlGrammarException exception) {
            return List.of();
        }
    }

    private String toAnnotationType(String annotationType) {
        if ("02".equals(annotationType)) {
            return "emergency";
        }

        return "normal";
    }

    private FacilityRecord getActiveFacility(Long facilityId) {
        List<FacilityRecord> results = jdbcTemplate.query("""
                SELECT *
                  FROM facilities_tree
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, facilityRowMapper, facilityId);

        if (results.isEmpty()) {
            throw new KeycloakAdminException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "対象施設が見つかりません"
            );
        }

        return results.getFirst();
    }

    private void ensureCreatePermission(
            Long parentId,
            Set<String> userRoles
    ) {
        if (userRoles != null && userRoles.contains("SYS_ADMIN")) {
            return;
        }

        if (parentId == null || userRoles == null || userRoles.isEmpty()) {
            throw permissionDenied();
        }

        String placeholders = userRoles.stream()
                .map(role -> "?")
                .collect(Collectors.joining(", "));

        List<Object> params = new java.util.ArrayList<>();
        params.add(FACILITY_EDIT_PERMISSION_ID);
        params.add(parentId);
        params.addAll(userRoles);

        Integer permissionCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM facility_role_permission
                 WHERE permission_id = ?
                   AND facility_id = ?
                   AND keycloak_role_id IN (%s)
                   AND deleted_at IS NULL
                """.formatted(placeholders), Integer.class, params.toArray());

        if (permissionCount == null || permissionCount == 0) {
            throw permissionDenied();
        }
    }

    private void ensureNotConflicted(
            FacilityRecord facility,
            Map<String, Object> request
    ) {
        if (!request.containsKey("updated_at")) {
            return;
        }

        String requestedUpdatedAt = stringValue(request.get("updated_at"));
        String currentUpdatedAt = format(facility.updatedAt);

        if (!Objects.equals(requestedUpdatedAt, currentUpdatedAt)) {
            throw conflict();
        }
    }

    private int nextSortOrder(Long parentId) {
        Integer maxSortOrder;

        if (parentId == null) {
            maxSortOrder = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(sort_order), 0)
                      FROM facilities_tree
                     WHERE parent_id IS NULL
                       AND deleted_at IS NULL
                    """, Integer.class);
        } else {
            maxSortOrder = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(sort_order), 0)
                      FROM facilities_tree
                     WHERE parent_id = ?
                       AND deleted_at IS NULL
                    """, Integer.class, parentId);
        }

        return (maxSortOrder == null ? 0 : maxSortOrder) + 10;
    }

    private int createSortOrder(Long parentId, Long targetFacilityId) {
        if (targetFacilityId == null) {
            Integer maxSortOrder;

            if (parentId == null) {
                maxSortOrder = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(sort_order), 0)
                          FROM facilities_tree
                         WHERE parent_id IS NULL
                           AND deleted_at IS NULL
                        """, Integer.class);
            } else {
                maxSortOrder = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(sort_order), 0)
                          FROM facilities_tree
                         WHERE parent_id = ?
                           AND deleted_at IS NULL
                        """, Integer.class, parentId);
            }

            return (maxSortOrder == null ? 0 : maxSortOrder) + 10_000_000;
        }

        List<Integer> targetSortOrders = jdbcTemplate.query("""
                SELECT sort_order
                  FROM facilities_tree
                 WHERE facility_id = ?
                   AND ((? IS NULL AND parent_id IS NULL) OR parent_id = ?)
                   AND deleted_at IS NULL
                """, (rs, rowNum) -> rs.getInt("sort_order"), targetFacilityId, parentId, parentId);

        if (targetSortOrders.isEmpty()) {
            throw invalidRequest("target_facility_id is invalid");
        }

        Integer targetSortOrder = targetSortOrders.getFirst();

        Integer nextSortOrder = jdbcTemplate.queryForObject("""
                SELECT MIN(sort_order)
                  FROM facilities_tree
                 WHERE ((? IS NULL AND parent_id IS NULL) OR parent_id = ?)
                   AND sort_order > ?
                   AND deleted_at IS NULL
                """, Integer.class, parentId, parentId, targetSortOrder);

        if (nextSortOrder == null) {
            return targetSortOrder + 10_000_000;
        }

        return targetSortOrder + Math.max(1, (nextSortOrder - targetSortOrder) / 2);
    }

    private void insertCreateChildren(
            Long facilityId,
            Long parentId,
            Map<String, Object> request
    ) {
        insertVrImage(facilityId, objectList(request.get("vr_image"), "vr_image", 1));
        insertMap(facilityId, objectValue(request.get("map"), "map", false));
        insertRoles(facilityId, objectList(request.get("roles"), "roles", Integer.MAX_VALUE));
        insertEquipments(facilityId, objectList(request.get("equipments"), "equipments", Integer.MAX_VALUE));
        insertMapPoint(facilityId, parentId, objectList(request.get("map_points"), "map_points", 1));
    }

    private void insertVrImage(
            Long facilityId,
            List<Map<String, Object>> vrImages
    ) {
        for (Map<String, Object> image : vrImages) {
            Long fileId = requiredLong(image, "file_id");
            ensureFileExists(fileId);
            String imageName = requiredLimitedString(image, "image_name", 256);
            Integer shootingHeight = positiveInteger(image.get("shooting_height"), "shooting_height");
            Integer ceilingHeight = positiveInteger(image.get("ceiling_height"), "ceiling_height");

            jdbcTemplate.update("""
                    INSERT INTO facility_images (
                        facility_id,
                        file_id,
                        image_name,
                        shooting_height,
                        ceiling_height,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, facilityId, fileId, imageName, shootingHeight, ceilingHeight);
        }
    }

    private void insertMap(
            Long facilityId,
            Map<String, Object> map
    ) {
        if (map == null) {
            return;
        }

        Long fileId = requiredLong(map, "file_id");
        ensureFileExists(fileId);
        String mapName = requiredLimitedString(map, "map_name", 256);
        Integer imageWidth = positiveInteger(map.get("image_width"), "image_width");
        Integer imageHeight = positiveInteger(map.get("image_height"), "image_height");

        jdbcTemplate.update("""
                INSERT INTO maps (
                    facility_id,
                    file_id,
                    map_name,
                    image_width,
                    image_height,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, facilityId, fileId, mapName, imageWidth, imageHeight);
    }

    private void insertRoles(
            Long facilityId,
            List<Map<String, Object>> roles
    ) {
        for (Map<String, Object> role : roles) {
            String keycloakRoleId = requiredLimitedString(role, "keycloak_role_id", 64);

            if ("SYS_ADMIN".equals(keycloakRoleId)) {
                throw invalidRequest("keycloak_role_id is invalid");
            }

            Long permissionId = permissionId(requiredLong(role, "permission_code"));

            jdbcTemplate.update("""
                    INSERT INTO facility_role_permission (
                        permission_id,
                        facility_id,
                        keycloak_role_id,
                        keycloak_role_name,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, permissionId, facilityId, keycloakRoleId, keycloakRoleId);
        }
    }

    private void insertEquipments(
            Long facilityId,
            List<Map<String, Object>> equipments
    ) {
        for (Map<String, Object> equipment : equipments) {
            Long equipmentMasterId = requiredLong(equipment, "equipment_id");
            ensureEquipmentMasterExists(equipmentMasterId);
            double yaw = rangedDouble(equipment.get("yaw"), "yaw");
            double pitch = rangedDouble(equipment.get("pitch"), "pitch");

            jdbcTemplate.update("""
                    INSERT INTO equipments (
                        facility_id,
                        equipment_master_id,
                        yaw,
                        pitch,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, facilityId, equipmentMasterId, yaw, pitch);
        }
    }

    private void insertMapPoint(
            Long facilityId,
            Long parentId,
            List<Map<String, Object>> mapPoints
    ) {
        if (mapPoints.isEmpty()) {
            return;
        }

        if (parentId == null) {
            throw invalidRequest("map_points is invalid");
        }

        Long mapId = jdbcTemplate.query("""
                SELECT map_id
                  FROM maps
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                 ORDER BY map_id ASC
                 LIMIT 1
                """, rs -> rs.next() ? rs.getLong("map_id") : null, parentId);

        if (mapId == null) {
            throw invalidRequest("map_points is invalid");
        }

        Map<String, Object> point = mapPoints.getFirst();
        double x = coordinate(point.get("x"), "x");
        double y = coordinate(point.get("y"), "y");

        jdbcTemplate.update("""
                INSERT INTO map_points (
                    map_id,
                    facility_id,
                    x,
                    y,
                    target_id,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, mapId, parentId, x, y, facilityId);
    }

    private void softDeleteByFacility(String tableName, Long facilityId) {
        if (!List.of(
                "facility_images",
                "maps",
                "facility_role_permission",
                "equipments",
                "annotations"
        ).contains(tableName)) {
            throw invalidRequest("table is invalid");
        }

        jdbcTemplate.update("""
                UPDATE %s
                   SET deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """.formatted(tableName), facilityId);
    }

    private void softDeleteMapPointsForTarget(Long facilityId) {
        jdbcTemplate.update("""
                UPDATE map_points
                   SET deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE target_id = ?
                   AND deleted_at IS NULL
                """, facilityId);
    }

    private void ensureNotDescendant(Long facilityId, Long newParentId) {
        if (newParentId == null) {
            return;
        }

        Integer descendantCount = jdbcTemplate.queryForObject("""
                WITH RECURSIVE descendants AS (
                    SELECT facility_id
                      FROM facilities_tree
                     WHERE parent_id = ?
                       AND deleted_at IS NULL
                    UNION ALL
                    SELECT child.facility_id
                      FROM facilities_tree child
                      JOIN descendants parent
                        ON child.parent_id = parent.facility_id
                     WHERE child.deleted_at IS NULL
                )
                SELECT COUNT(*)
                  FROM descendants
                 WHERE facility_id = ?
                """, Integer.class, facilityId, newParentId);

        if (descendantCount != null && descendantCount > 0) {
            throw invalidRequest("parent_id is invalid");
        }
    }

    private void updateDescendantTreeLevels(Long facilityId, int treeLevelDelta) {
        if (treeLevelDelta == 0) {
            return;
        }

        jdbcTemplate.update("""
                WITH RECURSIVE descendants AS (
                    SELECT facility_id
                      FROM facilities_tree
                     WHERE parent_id = ?
                       AND deleted_at IS NULL
                    UNION ALL
                    SELECT child.facility_id
                      FROM facilities_tree child
                      JOIN descendants parent
                        ON child.parent_id = parent.facility_id
                     WHERE child.deleted_at IS NULL
                )
                UPDATE facilities_tree
                   SET tree_level = tree_level + ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE facility_id IN (SELECT facility_id FROM descendants)
                """, facilityId, treeLevelDelta);
    }

    private void copyRelatedRows(Long sourceFacilityId, Long copiedFacilityId) {
        copyFacilityImages(sourceFacilityId, copiedFacilityId);
        copyMaps(sourceFacilityId, copiedFacilityId);
        copyRoles(sourceFacilityId, copiedFacilityId);
        copyEquipments(sourceFacilityId, copiedFacilityId);
        copyAnnotations(sourceFacilityId, copiedFacilityId);
    }

    private void copyFacilityImages(Long sourceFacilityId, Long copiedFacilityId) {
        jdbcTemplate.update("""
                INSERT INTO facility_images (
                    facility_id,
                    file_id,
                    image_name,
                    shooting_height,
                    ceiling_height,
                    created_at,
                    updated_at
                )
                SELECT ?,
                       file_id,
                       image_name,
                       shooting_height,
                       ceiling_height,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM facility_images
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, copiedFacilityId, sourceFacilityId);
    }

    private void copyMaps(Long sourceFacilityId, Long copiedFacilityId) {
        jdbcTemplate.update("""
                INSERT INTO maps (
                    facility_id,
                    file_id,
                    map_name,
                    image_width,
                    image_height,
                    created_at,
                    updated_at
                )
                SELECT ?,
                       file_id,
                       map_name,
                       image_width,
                       image_height,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM maps
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, copiedFacilityId, sourceFacilityId);
    }

    private void copyRoles(Long sourceFacilityId, Long copiedFacilityId) {
        jdbcTemplate.update("""
                INSERT INTO facility_role_permission (
                    permission_id,
                    facility_id,
                    keycloak_role_id,
                    keycloak_role_name,
                    created_at,
                    updated_at
                )
                SELECT permission_id,
                       ?,
                       keycloak_role_id,
                       keycloak_role_name,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM facility_role_permission
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, copiedFacilityId, sourceFacilityId);
    }

    private void copyEquipments(Long sourceFacilityId, Long copiedFacilityId) {
        jdbcTemplate.update("""
                INSERT INTO equipments (
                    facility_id,
                    equipment_master_id,
                    yaw,
                    pitch,
                    created_at,
                    updated_at
                )
                SELECT ?,
                       equipment_master_id,
                       yaw,
                       pitch,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM equipments
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, copiedFacilityId, sourceFacilityId);
    }

    private void copyAnnotations(Long sourceFacilityId, Long copiedFacilityId) {
        jdbcTemplate.update("""
                INSERT INTO annotations (
                    facility_id,
                    annotation_type,
                    annotation_title,
                    annotation_content,
                    created_by,
                    display_expire_type,
                    display_expire_at,
                    yaw,
                    pitch,
                    created_at,
                    updated_at
                )
                SELECT ?,
                       annotation_type,
                       annotation_title,
                       annotation_content,
                       created_by,
                       display_expire_type,
                       display_expire_at,
                       yaw,
                       pitch,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM annotations
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, copiedFacilityId, sourceFacilityId);
    }

    private void validateCreateRequestKeys(Map<String, Object> request) {
        if (request.containsKey("annotations")) {
            throw invalidRequest("annotations is invalid");
        }
    }

    private String requiredFacilityName(Map<String, Object> request) {
        String facilityName = requiredString(request, "facility_name");

        if (facilityName.length() > 100
                || facilityName.chars().anyMatch(Character::isISOControl)) {
            throw invalidRequest("facility_name is invalid");
        }

        return facilityName;
    }

    private String optionalDescription(Map<String, Object> request) {
        String description = stringValue(request.getOrDefault("facility_description", request.get("detail")));

        if (description != null && description.length() > 500) {
            throw invalidRequest("facility_description is invalid");
        }

        return description;
    }

    private String resolvePublicFlag(Map<String, Object> request) {
        Object publicFlag = request.get("public_flag");

        if (publicFlag != null) {
            String value = publicFlag.toString();

            if (!List.of("01", "02", "03").contains(value)) {
                throw invalidRequest("public_flag is invalid");
            }

            return value;
        }

        return toPublicFlag(stringValue(request.get("publish_mode")));
    }

    private void ensureFileExists(Long fileId) {
        if (count("""
                SELECT COUNT(*)
                  FROM files
                 WHERE file_id = ?
                   AND deleted_at IS NULL
                """, fileId) == 0) {
            throw invalidRequest("file_id is invalid");
        }
    }

    private void ensureEquipmentMasterExists(Long equipmentMasterId) {
        if (count("""
                SELECT COUNT(*)
                  FROM equipment_masters
                 WHERE equipment_master_id = ?
                   AND deleted_at IS NULL
                """, equipmentMasterId) == 0) {
            throw invalidRequest("equipment_id is invalid");
        }
    }

    private Long permissionId(Long permissionCode) {
        List<Long> results = jdbcTemplate.query("""
                SELECT permission_id
                  FROM permission_master
                 WHERE permission_id = ?
                   AND deleted_at IS NULL
                """, (rs, rowNum) -> rs.getLong("permission_id"), permissionCode);

        if (results.isEmpty()) {
            throw invalidRequest("permission_code is invalid");
        }

        return results.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(
            Object value,
            String key,
            int maxSize
    ) {
        if (value == null) {
            return List.of();
        }

        List<Map<String, Object>> values;

        if (value instanceof Map<?, ?> map) {
            values = List.of((Map<String, Object>) map);
        } else if (value instanceof List<?> list) {
            values = list.stream()
                    .map(item -> {
                        if (!(item instanceof Map<?, ?> itemMap)) {
                            throw invalidRequest(key + " is invalid");
                        }

                        return (Map<String, Object>) itemMap;
                    })
                    .toList();
        } else {
            throw invalidRequest(key + " is invalid");
        }

        if (values.size() > maxSize) {
            throw invalidRequest(key + " is invalid");
        }

        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue(
            Object value,
            String key,
            boolean required
    ) {
        if (value == null) {
            if (required) {
                throw invalidRequest(key + " is required");
            }

            return null;
        }

        if (!(value instanceof Map<?, ?> map)) {
            throw invalidRequest(key + " is invalid");
        }

        return (Map<String, Object>) map;
    }

    private String requiredLimitedString(
            Map<String, Object> request,
            String key,
            int maxLength
    ) {
        String value = requiredString(request, key);

        if (value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidRequest(key + " is invalid");
        }

        return value;
    }

    private Integer positiveInteger(
            Object value,
            String key
    ) {
        Long number = requiredLongValue(value, key);

        if (number <= 0 || number > Integer.MAX_VALUE) {
            throw invalidRequest(key + " is invalid");
        }

        return number.intValue();
    }

    private double rangedDouble(
            Object value,
            String key
    ) {
        double number = requiredDouble(value, key);

        if (number < -180 || number > 180) {
            throw invalidRequest(key + " is invalid");
        }

        return number;
    }

    private double coordinate(
            Object value,
            String key
    ) {
        double number = requiredDouble(value, key);

        if (number < -100 || number > 100) {
            throw invalidRequest(key + " is invalid");
        }

        return number;
    }

    private double requiredDouble(
            Object value,
            String key
    ) {
        if (value == null) {
            throw invalidRequest(key + " is required");
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            throw invalidRequest(key + " is invalid");
        }
    }

    private Long optionalLong(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String string && string.isBlank()) {
            return null;
        }

        return asLong(value);
    }

    private Long requiredLongValue(
            Object value,
            String key
    ) {
        Long number = optionalLong(value);

        if (number == null) {
            throw invalidRequest(key + " is required");
        }

        return number;
    }

    private boolean optionalBoolean(
            Object value,
            boolean defaultValue
    ) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        String string = value.toString().toLowerCase(Locale.ROOT);

        if ("true".equals(string)) {
            return true;
        }

        if ("false".equals(string)) {
            return false;
        }

        throw invalidRequest("boolean value is invalid");
    }

    private void updateParentLeafState(Long parentId) {
        if (parentId == null) {
            return;
        }

        Integer childCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM facilities_tree
                 WHERE parent_id = ?
                   AND deleted_at IS NULL
                """, Integer.class, parentId);

        jdbcTemplate.update("""
                UPDATE facilities_tree
                   SET is_leaf = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE facility_id = ?
                   AND deleted_at IS NULL
                """, childCount == null || childCount == 0, parentId);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME_FORMATTER.format(value);
    }

    private String requiredString(
            Map<String, Object> request,
            String key
    ) {
        String value = stringValue(request.get(key));

        if (value == null || value.isBlank()) {
            throw invalidRequest(key + " is required");
        }

        return value;
    }

    private Long requiredLong(
            Map<String, Object> request,
            String key
    ) {
        Long value = asLong(request.get(key));

        if (value == null) {
            throw invalidRequest(key + " is required");
        }

        return value;
    }

    private KeycloakAdminException invalidRequest(String message) {
        return new KeycloakAdminException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "リクエストパラメータが不正です"
        );
    }

    private KeycloakAdminException permissionDenied() {
        return new KeycloakAdminException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                "施設を登録する権限がありません"
        );
    }

    private KeycloakAdminException conflict() {
        return new KeycloakAdminException(
                HttpStatus.CONFLICT,
                "conflict",
                "対象データが他ユーザにより更新されています"
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String defaultValue(String value) {
        return value == null ? "" : value;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw invalidRequest("number value is invalid");
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        return Boolean.parseBoolean(value.toString().toLowerCase(Locale.ROOT));
    }

    private String toPublicFlag(String publishMode) {
        if (publishMode == null || publishMode.isBlank()) {
            return "01";
        }

        return switch (publishMode) {
            case "public_self" -> "02";
            case "public_children" -> "03";
            default -> "01";
        };
    }

    private static String toPublishMode(String publicFlag) {
        if ("02".equals(publicFlag)) {
            return "public_self";
        }

        if ("03".equals(publicFlag)) {
            return "public_children";
        }

        return "private";
    }

    private static Long nullableLong(java.sql.ResultSet rs, String columnName)
            throws java.sql.SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String columnName)
            throws java.sql.SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return LocalDateTime.ofInstant(timestamp.toInstant(), JAPAN_ZONE);
    }

    private static class FacilityRecord {
        private Long facilityId;
        private Long parentId;
        private String facilityName;
        private Integer treeLevel;
        private Boolean isLeaf;
        private String publishMode;
        private Boolean isOutdoor;
        private String detail;
        private Boolean hasPermission;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime deletedAt;
    }
}
