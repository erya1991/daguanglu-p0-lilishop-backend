CREATE TABLE li_p0_agreement (
 id BIGINT PRIMARY KEY, agreement_no VARCHAR(64) NOT NULL, revision INT NOT NULL, store_id BIGINT NOT NULL,
 business_type VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 subject_refs JSON NOT NULL, scope JSON NOT NULL, file_refs JSON NOT NULL, confirmation_reference VARCHAR(255),
 effective_from DATETIME(6), effective_to DATETIME(6), confirmed_at DATETIME(6), terminated_at DATETIME(6), cancelled_at DATETIME(6),
 termination_reason VARCHAR(500), created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_agreement_revision(agreement_no,revision), KEY ix_s1_agreement_window(store_id,business_type,status,effective_from,effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_commission_rule (
 id BIGINT PRIMARY KEY, rule_key VARCHAR(64) NOT NULL, revision INT NOT NULL, store_id BIGINT NOT NULL,
 agreement_id BIGINT NOT NULL, agreement_revision INT NOT NULL, business_type VARCHAR(24) NOT NULL,
 rate_bps INT NULL, base_code VARCHAR(50) NOT NULL, round_code VARCHAR(24) NOT NULL,
 effective_from DATETIME(6), effective_to DATETIME(6), status VARCHAR(24) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_rule_revision(rule_key,revision), KEY ix_s1_rule_window(store_id,business_type,status,effective_from,effective_to),
 CHECK(rate_bps IS NULL OR rate_bps BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_channel_config (
 id BIGINT PRIMARY KEY, mode VARCHAR(8) NOT NULL, environment VARCHAR(24) NOT NULL, provider VARCHAR(24) NOT NULL,
 provider_mchid VARCHAR(64), payment_appid VARCHAR(128), secret_ref VARCHAR(100), config_version BIGINT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(6) NOT NULL,
 CHECK(mode IN ('MOCK','REAL')), CHECK(NOT(environment='PRODUCTION' AND mode='MOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_submerchant_relation (
 id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, config_id BIGINT NOT NULL, config_version BIGINT NOT NULL,
 mode VARCHAR(8) NOT NULL, sub_mchid VARCHAR(64), application_id BIGINT,
 capability_state VARCHAR(24) NOT NULL, capability_evidence JSON, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 last_sync_state VARCHAR(16) NOT NULL, verified_at DATETIME(6), evidence_version BIGINT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, active_store_key VARCHAR(100) NULL, active_account_key VARCHAR(160) NULL,
 receiver_id BIGINT, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_active_store_relation(active_store_key), UNIQUE KEY uk_s1_active_submerchant(active_account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_channel_application (
 id BIGINT PRIMARY KEY, relation_id BIGINT NOT NULL, config_id BIGINT NOT NULL, mode VARCHAR(8) NOT NULL,
 out_request_no VARCHAR(124) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, applyment_id VARCHAR(64), revision INT NOT NULL,
 state VARCHAR(40) NOT NULL, raw_state VARCHAR(80), material_hash CHAR(64), encrypted_payload_ref VARCHAR(100),
 safe_material_summary JSON NOT NULL, required_actions JSON, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_channel_request(config_id,mode,out_request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_channel_operation (
 id BIGINT PRIMARY KEY, application_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL, request_hash CHAR(64) NOT NULL,
 request_seq BIGINT NOT NULL, config_version BIGINT NOT NULL, status VARCHAR(24) NOT NULL,
 attempt INT NOT NULL DEFAULT 0, next_retry_at DATETIME(6), safe_response JSON, trace_id VARCHAR(128), created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s1_channel_sequence(application_id,request_seq), KEY ix_s1_channel_retry(status,next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_receiver_relation (
 id BIGINT PRIMARY KEY, config_id BIGINT NOT NULL, mode VARCHAR(8) NOT NULL, receiver_type VARCHAR(24) NOT NULL,
 account_ref VARCHAR(100) NOT NULL, account_hash CHAR(64) NOT NULL, display_name VARCHAR(120) NOT NULL,
 relation_type VARCHAR(24) NOT NULL, source_kind VARCHAR(24) NOT NULL, state VARCHAR(24) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, verified_at DATETIME(6), version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_s1_platform_receiver(config_id,mode,receiver_type,account_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
