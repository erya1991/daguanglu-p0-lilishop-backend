-- REVIEW ONLY. Requires fresh Stage45 schema and S1/S2/S3 inherited migrations.
CREATE TABLE li_p0_s45_order (
 order_id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0, current_batch_id BIGINT NULL,
 fulfillment_ended BIT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_aftersale (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, item_id BIGINT NOT NULL, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 type VARCHAR(20) NOT NULL, quantity INT NOT NULL, goods_cent BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL, dispute VARCHAR(24) NOT NULL DEFAULT 'NONE', version BIGINT NOT NULL DEFAULT 0,
 delivered BIT NOT NULL, reason VARCHAR(500) NOT NULL, evidence_json JSON NOT NULL,
 return_snapshot JSON NULL, return_submission JSON NULL, share_numbers JSON NOT NULL,
 approved_at DATETIME(6) NULL, refunded_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 KEY ix_s45_as_store(store_id,state,id), KEY ix_s45_as_member(member_id,id), KEY ix_s45_as_order(order_id,id),
 CHECK(quantity BETWEEN 1 AND 99 AND goods_cent BETWEEN 1 AND 9007199254740991), CHECK(type IN ('REFUND','RETURN_REFUND'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_share (
 item_id BIGINT NOT NULL, share_no INT NOT NULL, amount_cent BIGINT NOT NULL,
 after_sale_id BIGINT NULL, state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
 PRIMARY KEY(item_id,share_no), KEY ix_s45_share_owner(after_sale_id),
 CHECK(amount_cent>=0), CHECK(state IN ('AVAILABLE','HELD','CONSUMED')),
 CHECK((state='AVAILABLE' AND after_sale_id IS NULL) OR (state<>'AVAILABLE' AND after_sale_id IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_obligation (
 id BIGINT PRIMARY KEY, source_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 order_id BIGINT NOT NULL, after_sale_id BIGINT NULL, kind VARCHAR(16) NOT NULL,
 amount_cent BIGINT NOT NULL, quantity INT NOT NULL DEFAULT 0, basis_json JSON NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'READY', batch_id BIGINT NULL, succeeded_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL,
 KEY ix_s45_obligation_order(order_id,state,id), CHECK(amount_cent>0),
 CHECK(kind IN ('GOODS','FEE','LATE_PAYMENT')), CHECK(state IN ('READY','CURRENT','SUCCEEDED'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_batch (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, payment_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, goods_cent BIGINT NOT NULL, fee_cent BIGINT NOT NULL,
 late_cent BIGINT NOT NULL DEFAULT 0, target_commission BIGINT NOT NULL, required_return BIGINT NOT NULL, snapshot_hash CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL, settled_at DATETIME(6) NULL,
 active_order_id BIGINT GENERATED ALWAYS AS (IF(state='SETTLED',NULL,order_id)) STORED,
 UNIQUE KEY uk_s45_current_batch(active_order_id),
 active_payment_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (IF(state='SETTLED',NULL,payment_key)) STORED,
 UNIQUE KEY uk_s45_current_payment(active_payment_key),
 KEY ix_s45_batch_order(order_id,id), CHECK(goods_cent>=0 AND fee_cent>=0 AND late_cent>=0 AND goods_cent+fee_cent+late_cent>0)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_task (
 id BIGINT PRIMARY KEY, business_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 order_id BIGINT NOT NULL, batch_id BIGINT NULL, kind VARCHAR(16) NOT NULL,
 request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 request_json JSON NOT NULL, request_hash CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING', result_json JSON NULL, applied BIT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(64) NULL,
 lease_token VARCHAR(64) NULL, lease_until DATETIME(6) NULL, attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 KEY ix_s45_task_due(state,next_attempt_at,id), KEY ix_s45_task_order(order_id,kind,id),
 CHECK(kind IN ('SHARE','RETURN','REFUND','FINISH'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_channel_evidence (
 id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, source VARCHAR(16) NOT NULL,
 result_json JSON NOT NULL, result_hash CHAR(64) NOT NULL, observed_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s45_evidence(task_id,result_hash)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_effect (
 effect_key VARCHAR(140) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 order_id BIGINT NOT NULL, source_id BIGINT NOT NULL, kind VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_operation (
 identity_domain VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL, store_scope BIGINT NOT NULL,
 operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(40) NOT NULL, target_id BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL,
 response_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(identity_domain,actor_id,store_scope,operation_id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_audit (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, subject_type VARCHAR(24) NOT NULL, subject_id BIGINT NOT NULL,
 actor_domain VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL, action VARCHAR(40) NOT NULL,
 reason VARCHAR(500) NULL, before_json JSON NULL, after_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 KEY ix_s45_audit_subject(subject_type,subject_id,id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_exception (
 id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, source_key VARCHAR(120) NOT NULL, code VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'OPEN', detail_json JSON NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s45_exception(source_key,code), KEY ix_s45_exception_order(order_id,state,id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_event_link (
 event_id BIGINT PRIMARY KEY, subject_type VARCHAR(24) NOT NULL, subject_id BIGINT NOT NULL,
 required_store_action VARCHAR(32) NOT NULL, resident_visible BIT NOT NULL DEFAULT 1
) ENGINE=InnoDB;
ALTER TABLE li_member_evaluation
 ADD COLUMN star TINYINT NULL,
 ADD COLUMN p0_order_item_id BIGINT NULL,
 ADD COLUMN p0_version BIGINT NOT NULL DEFAULT 0,
 ADD UNIQUE KEY uk_s45_evaluation_item(p0_order_item_id),
 ADD CONSTRAINT ck_s45_evaluation_star CHECK(star IS NULL OR star BETWEEN 1 AND 5);
ALTER TABLE li_store_flow ADD COLUMN p0_source_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL, ADD UNIQUE KEY uk_s45_store_flow_source(p0_source_key);
ALTER TABLE li_member_evaluation MODIFY content VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
-- History is intentionally not backfilled. Nullable legacy grade/content/scores retain original semantics.
CREATE TABLE li_p0_s45_reconciliation (
 id BIGINT PRIMARY KEY, mode VARCHAR(8) NOT NULL, account VARCHAR(64) NOT NULL, currency CHAR(3) NOT NULL,
 business_date DATE NOT NULL, zone_id VARCHAR(64) NOT NULL, cutoff_at DATETIME(6) NOT NULL,
 source_type VARCHAR(24) NOT NULL, source_hash CHAR(64) NOT NULL, coverage_json JSON NOT NULL,
 state VARCHAR(24) NOT NULL, created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_reconciliation_line (
 id BIGINT PRIMARY KEY, reconciliation_id BIGINT NOT NULL, request_no VARCHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL, local_cent BIGINT NULL, channel_cent BIGINT NULL, bill_cent BIGINT NULL,
 fee_cent BIGINT NULL, classification VARCHAR(32) NOT NULL, detail_json JSON NOT NULL,
 reviewed_by BIGINT NULL, review_reason VARCHAR(500) NULL, reviewed_at DATETIME(6) NULL,
 UNIQUE KEY uk_s45_reconciliation_line(reconciliation_id,request_no,kind)
) ENGINE=InnoDB;

-- Reuse native file metadata; this table supplies exact order/subject authorization.
CREATE TABLE li_p0_s45_attachment(file_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,subject_type VARCHAR(24) NOT NULL,subject_id BIGINT NULL,KEY ix_s45_attachment_subject(subject_type,subject_id)) ENGINE=InnoDB;
CREATE TABLE li_p0_s45_inventory_return(after_sale_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,order_item_id BIGINT NOT NULL,quantity INT NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',last_error VARCHAR(64) NULL,created_at DATETIME(6) NOT NULL,applied_at DATETIME(6) NULL) ENGINE=InnoDB;
-- Integer-cent bounds fit these native decimal projections without truncation.
ALTER TABLE li_after_sale MODIFY actual_refund_price DECIMAL(18,2) NULL, MODIFY apply_refund_price DECIMAL(18,2) NULL, MODIFY flow_price DECIMAL(18,2) NULL;
ALTER TABLE li_store_flow MODIFY bill_price DECIMAL(18,2) NULL, MODIFY commission_price DECIMAL(18,2) NULL, MODIFY final_price DECIMAL(18,2) NULL;
