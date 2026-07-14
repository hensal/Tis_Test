-- Convert the files table to the current files DB specification.
-- Run this in pgAdmin Query Tool connected to vr_view_db.
--
-- Important:
-- After this migration, the application expects MinIO objects to be stored as:
--   {file_id}/{file_name}
-- Example:
--   16/resume_dhanmaya.pdf
--
-- Existing older uploads may need to be re-uploaded or copied in MinIO because
-- older code stored object keys in external_file_id.

ALTER TABLE files
    ALTER COLUMN file_name TYPE VARCHAR(128),
    ALTER COLUMN file_type TYPE VARCHAR(32),
    ALTER COLUMN file_path TYPE VARCHAR(256);

ALTER TABLE files
    ADD COLUMN IF NOT EXISTS s3_bucket VARCHAR(512);

UPDATE files
   SET s3_bucket = COALESCE(
       s3_bucket,
       external_bucket,
       'http://localhost:9000/vr-view-bucket'
   )
 WHERE s3_bucket IS NULL;

ALTER TABLE files
    ALTER COLUMN s3_bucket SET NOT NULL;

ALTER TABLE files
    DROP COLUMN IF EXISTS storage_type,
    DROP COLUMN IF EXISTS external_file_id,
    DROP COLUMN IF EXISTS external_url,
    DROP COLUMN IF EXISTS external_bucket;

-- Check the final shape/data.
SELECT
    file_id,
    file_name,
    file_path,
    file_type,
    file_size,
    s3_bucket,
    created_at,
    deleted_at
FROM files
ORDER BY file_id DESC;
