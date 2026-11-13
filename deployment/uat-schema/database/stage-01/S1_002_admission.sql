-- Stage1 additions. Native Store/Member/Clerk remain the only identity and ownership records.
CREATE TABLE li_p0_store_control (
 store_id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
 admission_state VARCHAR(24) NOT NULL, legal_code_normalized VARCHAR(18) CHARACTER SET ascii COLLATE ascii_bin NULL,
 owner_member_id BIGINT NOT NULL, category_ids JSON NOT NULL, closed_reason VARCHAR(500), closed_at DATETIME(6),
 UNIQUE KEY uk_s1_legal_code(legal_code_normalized), UNIQUE KEY uk_s1_owner(owner_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_application (
 id BIGINT PRIMARY KEY, applicant_id BIGINT NOT NULL, store_id BIGINT NULL, kind VARCHAR(32) NOT NULL,
 status VARCHAR(24) NOT NULL, revision INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 active_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL, draft_payload JSON NOT NULL,
 submitted_at DATETIME(6), reviewed_at DATETIME(6), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_active_application(active_key), KEY ix_s1_application_queue(status,updated_at,id), KEY ix_s1_application_person(applicant_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_application_revision (
 application_id BIGINT NOT NULL, revision INT NOT NULL, payload JSON NOT NULL, content_hash CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, reason VARCHAR(500), reviewer_id BIGINT, reviewed_at DATETIME(6), created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(application_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_qualification (
 id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, qualification_key VARCHAR(64) NOT NULL, revision INT NOT NULL,
 type VARCHAR(64) NOT NULL, scope VARCHAR(24) NOT NULL, category_ids JSON NOT NULL, status VARCHAR(24) NOT NULL,
 valid_from DATETIME(6), valid_to DATETIME(6), long_term BOOLEAN NOT NULL, attachment_refs JSON NOT NULL,
 application_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_qualification_revision(store_id,qualification_key,revision), KEY ix_s1_qualification_current(store_id,status,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_category_qualification (
 category_id BIGINT NOT NULL, qualification_type VARCHAR(64) NOT NULL, required BOOLEAN NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(category_id,qualification_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_staff_invitation (
 id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, creator_id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL,
 roles JSON NOT NULL, status VARCHAR(24) NOT NULL, expires_at DATETIME(6) NOT NULL,
 accepted_member_id BIGINT, accepted_at DATETIME(6), confirm_deadline DATETIME(6), version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_s1_invite_token(token_hash), KEY ix_s1_invite_store(store_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_grant_evidence (
 id BIGINT PRIMARY KEY, application VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_id BIGINT NOT NULL, binding_version BIGINT NOT NULL, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 clerk_id BIGINT NOT NULL, source_type VARCHAR(24) NOT NULL, source_id BIGINT NOT NULL, source_revision INT NOT NULL,
 audit_reference VARCHAR(128) NOT NULL, active BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_grant_binding(binding_id,binding_version,store_id), KEY ix_s1_grant_member(member_id,active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_clerk_control (
 clerk_id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0, revoked BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_operation (
 identity_domain VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL, request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(80) NOT NULL, store_id BIGINT NULL, payload_hash CHAR(64) NOT NULL, response_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(identity_domain,actor_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_audit (
 id BIGINT PRIMARY KEY, actor_id BIGINT NOT NULL, identity_domain VARCHAR(16) NOT NULL, action VARCHAR(80) NOT NULL,
 store_id BIGINT NULL, object_id BIGINT NULL, request_id VARCHAR(64) NOT NULL, before_version BIGINT NULL, after_version BIGINT NULL,
 reason VARCHAR(500) NULL, created_at DATETIME(6) NOT NULL, KEY ix_s1_audit_object(store_id,object_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_outbox (
 id BIGINT PRIMARY KEY, store_id BIGINT NULL, object_id BIGINT NULL, event_type VARCHAR(40) NOT NULL,
 version BIGINT NOT NULL, status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL, completed_at DATETIME(6), KEY ix_s1_outbox_pending(status,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_file_control (
 file_id BIGINT PRIMARY KEY, actor_id BIGINT NOT NULL, identity_domain VARCHAR(16) NOT NULL, store_id BIGINT NULL,
 purpose VARCHAR(24) NOT NULL, private_file BOOLEAN NOT NULL, storage_key VARCHAR(100) NOT NULL,
 mime_type VARCHAR(80) NOT NULL, byte_size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NULL, bound_object_id BIGINT NULL,
 UNIQUE KEY uk_s1_file_storage(storage_key), KEY ix_s1_file_owner(actor_id,purpose,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
