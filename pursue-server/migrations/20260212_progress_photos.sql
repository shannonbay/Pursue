-- Progress photo attachments with 7-day expiry
-- Photos are stored in GCS and deleted via lifecycle rules after 7 days.
-- This table tracks metadata and enables signed URL generation.

CREATE TABLE progress_photos (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  progress_entry_id UUID NOT NULL UNIQUE REFERENCES progress_entries(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  gcs_object_path VARCHAR(512) NOT NULL,
  width_px INTEGER NOT NULL,
  height_px INTEGER NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
  gcs_deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for looking up photo by progress entry
CREATE INDEX idx_photos_entry ON progress_photos(progress_entry_id);

-- Index for looking up all photos by user (for account deletion)
CREATE INDEX idx_photos_user ON progress_photos(user_id);

-- Index for finding non-expired photos (used when generating signed URLs)
CREATE INDEX idx_photos_expiry ON progress_photos(expires_at) WHERE gcs_deleted_at IS NULL;

-- Permanent upload log for quota enforcement (rolling 7-day window)
-- This table is never pruned - keeps record of all uploads for quota calculation
CREATE TABLE photo_upload_log (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for efficient quota queries (count uploads in last 7 days per user)
CREATE INDEX idx_upload_log_user_time ON photo_upload_log(user_id, uploaded_at DESC);
