-- Candidate DDL. Execute only after exact checksum/target approval; never in S1/S2.
CREATE TABLE li_p0_s3_fulfillment (
 order_id BIGINT PRIMARY KEY, trade_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 method VARCHAR(24) NOT NULL, phase VARCHAR(24) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 admitted_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, completed_at DATETIME(6) NULL,
 KEY ix_s3_store_phase(store_id,phase,order_id),
 CHECK(method IN ('MERCHANT_DELIVERY','EXPRESS','COMMUNITY_PICKUP','STORE_REDEMPTION'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_operation (
 identity_domain VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL, store_scope BIGINT NOT NULL,
 operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id BIGINT NOT NULL, action VARCHAR(32) NOT NULL, payload_hash CHAR(64) NOT NULL,
 response_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(identity_domain,actor_id,store_scope,operation_id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_event (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, trade_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL, version BIGINT NOT NULL, event_type VARCHAR(32) NOT NULL,
 actor_id BIGINT NULL, actor_domain VARCHAR(16) NOT NULL, point_id BIGINT NULL,
 payload_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 completion_order_id BIGINT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL, last_reason VARCHAR(64) NULL,
 UNIQUE KEY uk_s3_transition(order_id,version,event_type),
 UNIQUE KEY uk_s3_completion(completion_order_id),
 KEY ix_s3_event_due(status,next_attempt_at,id),
 CHECK(completion_order_id IS NULL OR (completion_order_id=order_id AND event_type='COMPLETED'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_exception (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, store_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 exception_type VARCHAR(32) NOT NULL, reason VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL,
 KEY ix_s3_exception_order(order_id,id)
) ENGINE=InnoDB;
