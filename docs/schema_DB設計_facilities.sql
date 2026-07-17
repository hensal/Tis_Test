-- VRView PostgreSQL schema based on DB設計.md.
-- Run this first, then run docs/sample_data_DB設計.sql.
-- This file drops and recreates the application tables.

BEGIN;

DROP TABLE IF EXISTS
  facility_role_permission,
  permission_master,
  annotation_type_master,
  equipment_files,
  annotation_files,
  annotation_comments,
  annotations,
  map_points,
  hotspots,
  maps,
  facility_images,
  equipments,
  equipment_masters,
  equipment_types,
  files,
  facilities_tree
CASCADE;

CREATE TABLE facilities_tree (
    facility_id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT REFERENCES facilities_tree(facility_id),
    facility_name VARCHAR(100) NOT NULL,
    sort_order BIGINT NOT NULL,
    tree_level INT,
    is_leaf BOOLEAN NOT NULL,
    public_flag CHAR(2) NOT NULL,
    outer_flag BOOLEAN NOT NULL,
    facility_description VARCHAR(500),
    authority_setting_flg BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE files (
    file_id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(128) NOT NULL,
    file_path VARCHAR(256),
    file_type VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL,
    s3_bucket VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE permission_master (
    permission_id BIGSERIAL PRIMARY KEY,
    permission_name VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE facility_role_permission (
    facility_role_permission_id BIGSERIAL PRIMARY KEY,
    permission_id BIGINT NOT NULL REFERENCES permission_master(permission_id),
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    -- DB設計.md says keycloak_role_id, while sample_data.sql uses keycloak_role_name.
    -- Keep both so the sample data and Keycloak role-name checks can work.
    keycloak_role_id VARCHAR(64),
    keycloak_role_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE equipment_types (
    equipment_type_id BIGSERIAL PRIMARY KEY,
    equipment_type_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE equipment_masters (
    equipment_master_id BIGSERIAL PRIMARY KEY,
    equipment_type_id BIGINT NOT NULL REFERENCES equipment_types(equipment_type_id),
    equipment_name VARCHAR(100) NOT NULL,
    equipment_info_json TEXT NOT NULL,
    equipment_info_html TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE equipments (
    equipment_id BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    equipment_master_id BIGINT NOT NULL REFERENCES equipment_masters(equipment_master_id),
    yaw DOUBLE PRECISION,
    pitch DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE facility_images (
    image_id BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    file_id BIGINT NOT NULL REFERENCES files(file_id),
    image_name VARCHAR(256),
    shooting_height INT NOT NULL,
    ceiling_height INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE maps (
    map_id BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    file_id BIGINT NOT NULL REFERENCES files(file_id),
    map_name VARCHAR(256) NOT NULL,
    image_width INT NOT NULL,
    image_height INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE map_points (
    map_point_id BIGSERIAL PRIMARY KEY,
    map_id BIGINT NOT NULL REFERENCES maps(map_id),
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    x INT NOT NULL,
    y INT NOT NULL,
    target_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE hotspots (
    hotspot_id BIGSERIAL PRIMARY KEY,
    image_id BIGINT NOT NULL REFERENCES facility_images(image_id),
    target_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    yaw DOUBLE PRECISION NOT NULL,
    pitch DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE annotation_type_master (
    annotation_type CHAR(2) PRIMARY KEY,
    annotation_type_name VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE annotations (
    annotation_id BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facilities_tree(facility_id),
    annotation_type CHAR(2) NOT NULL REFERENCES annotation_type_master(annotation_type),
    annotation_title VARCHAR(32) NOT NULL,
    annotation_content VARCHAR(256) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    display_expire_type BOOLEAN NOT NULL,
    display_expire_at TIMESTAMPTZ,
    yaw DOUBLE PRECISION,
    pitch DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE annotation_comments (
    annotation_comment_id BIGSERIAL PRIMARY KEY,
    annotation_id BIGINT NOT NULL REFERENCES annotations(annotation_id),
    comment_text VARCHAR(128) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE annotation_files (
    annotation_file_id BIGSERIAL PRIMARY KEY,
    annotation_id BIGINT NOT NULL REFERENCES annotations(annotation_id),
    file_id BIGINT NOT NULL REFERENCES files(file_id)
);

CREATE TABLE equipment_files (
    equipment_file_id BIGSERIAL PRIMARY KEY,
    equipment_master_id BIGINT NOT NULL REFERENCES equipment_masters(equipment_master_id),
    file_id BIGINT NOT NULL REFERENCES files(file_id)
);

CREATE INDEX idx_facilities_tree_parent_sort_deleted
    ON facilities_tree(parent_id, sort_order, deleted_at);

CREATE INDEX idx_facility_role_permission_facility_role_deleted
    ON facility_role_permission(facility_id, keycloak_role_id, deleted_at);

CREATE UNIQUE INDEX uq_facility_role_permission_active
    ON facility_role_permission(facility_id, COALESCE(keycloak_role_id, keycloak_role_name), permission_id)
    WHERE deleted_at IS NULL;

COMMIT;
