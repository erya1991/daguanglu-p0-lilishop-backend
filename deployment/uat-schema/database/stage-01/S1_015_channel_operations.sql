-- Versioned non-funding channel management. No payment, settlement, refund or profit-sharing order tables.
ALTER TABLE li_p0_channel_config ADD name VARCHAR(60) NOT NULL DEFAULT '',
 ADD check_state VARCHAR(24) NOT NULL DEFAULT 'NOT_CHECKED', ADD checked_at DATETIME(6) NULL,
 ADD change_reason VARCHAR(500) NULL, ADD active_route VARCHAR(24) NULL,
 ADD mock_max_rate_bps INT NULL, ADD CHECK(mock_max_rate_bps IS NULL OR mock_max_rate_bps BETWEEN 0 AND 10000),
 ADD UNIQUE KEY uk_s1_channel_route(active_route);
CREATE TABLE li_p0_channel_config_history (
 config_id BIGINT NOT NULL, version BIGINT NOT NULL, safe_snapshot JSON NOT NULL,
 actor_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(config_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE li_p0_submerchant_relation ADD material_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 ADD material_version BIGINT NOT NULL DEFAULT 0, ADD request_seq BIGINT NOT NULL DEFAULT 0,
 ADD accepted_seq BIGINT NOT NULL DEFAULT 0, ADD predecessor_id BIGINT NULL, ADD updated_at DATETIME(6) NULL;
ALTER TABLE li_p0_submerchant_relation ADD provider_snapshot VARCHAR(64) NULL, ADD appid_snapshot VARCHAR(128) NULL;
ALTER TABLE li_p0_receiver_relation ADD trust_source VARCHAR(40) NOT NULL DEFAULT 'UNVERIFIED',
 ADD request_seq BIGINT NOT NULL DEFAULT 0, ADD reason VARCHAR(500) NULL;
CREATE TABLE li_p0_channel_material_revision (
 application_id BIGINT NOT NULL, revision INT NOT NULL, encrypted_ref VARCHAR(100) NOT NULL,
 material_hash CHAR(64) NOT NULL, safe_summary JSON NOT NULL, actor_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(application_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE li_p0_channel_operation MODIFY application_id BIGINT NULL,
 ADD object_type VARCHAR(24) NOT NULL DEFAULT 'APPLICATION', ADD object_id BIGINT NULL,
 ADD actor_id BIGINT NULL, ADD request_id VARCHAR(64) NULL, ADD relation_version BIGINT NULL,
 ADD material_revision INT NULL, ADD response_ref VARCHAR(100) NULL, ADD completed_at DATETIME(6) NULL;
CREATE TABLE li_p0_channel_media (
 config_id BIGINT NOT NULL, config_version BIGINT NOT NULL, mode VARCHAR(8) NOT NULL,
 file_id BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, media_id VARCHAR(512) NOT NULL,
 created_at DATETIME(6) NOT NULL, PRIMARY KEY(config_id,config_version,mode,file_id,sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_channel_mock (
 config_id BIGINT NOT NULL, out_request_no VARCHAR(124) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, applyment_id VARCHAR(64) NOT NULL, sub_mchid VARCHAR(64) NULL,
 raw_state VARCHAR(40) NOT NULL, scenario VARCHAR(40) NOT NULL DEFAULT 'NORMAL',
 query_count INT NOT NULL DEFAULT 0, revision INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(config_id,out_request_no), UNIQUE KEY uk_s1_mock_applyment(config_id,applyment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
