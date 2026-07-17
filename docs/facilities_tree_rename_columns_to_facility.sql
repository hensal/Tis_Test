-- Rename facilities_tree columns to match API/test specification names.
-- Run this in pgAdmin Query Tool inside your vr_view_db database.

ALTER TABLE facilities_tree
RENAME COLUMN node_name TO facility_name;

ALTER TABLE facilities_tree
RENAME COLUMN node_description TO facility_description;

-- Check result:
SELECT
    facility_id,
    parent_id,
    facility_name,
    facility_description,
    sort_order,
    tree_level,
    is_leaf,
    public_flag,
    outer_flag,
    authority_setting_flg,
    created_at,
    updated_at,
    deleted_at
FROM facilities_tree
ORDER BY facility_id;
