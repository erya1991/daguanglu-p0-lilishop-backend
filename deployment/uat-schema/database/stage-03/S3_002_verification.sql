-- Candidate DDL. Credential plaintext is never stored here.
CREATE TABLE li_p0_s3_credential (
 order_id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, point_id BIGINT NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_cipher VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME(6) NOT NULL, used_at DATETIME(6) NULL,
 UNIQUE KEY uk_s3_credential_hash(token_hash)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_verification (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, store_id BIGINT NOT NULL, point_id BIGINT NOT NULL,
 credential_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operator_id BIGINT NOT NULL, clerk_id BIGINT NOT NULL, operation_id VARCHAR(64) NOT NULL,
 point_snapshot JSON NOT NULL, completed_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s3_verified_order(order_id), UNIQUE KEY uk_s3_verified_credential(credential_hash),
 KEY ix_s3_verification_store(store_id,completed_at,id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_verification_audit (
 id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 order_id BIGINT NULL, point_id BIGINT NULL, credential_ref CHAR(16) NULL,
 result VARCHAR(32) NOT NULL, created_at DATETIME(6) NOT NULL,
 KEY ix_s3_verification_audit(store_id,created_at,id)
) ENGINE=InnoDB;
