-- M02 immutable authorization snapshots; native Clerk/Role remain current authority.
CREATE TABLE li_p0_staff_grant_history (
 clerk_id BIGINT NOT NULL, version BIGINT NOT NULL, store_id BIGINT NOT NULL,
 role_ids JSON NOT NULL, state VARCHAR(24) NOT NULL, source_action VARCHAR(40) NOT NULL,
 audit_reference BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(clerk_id,version), KEY ix_s1_staff_history(store_id,clerk_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
