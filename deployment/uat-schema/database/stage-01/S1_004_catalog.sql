ALTER TABLE li_goods MODIFY sn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, MODIFY weight DECIMAL(11,3) NULL;
ALTER TABLE li_goods_sku MODIFY sn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, MODIFY weight DECIMAL(11,3) NULL;
CREATE TABLE li_p0_goods_control (
 goods_id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, content_version BIGINT NOT NULL DEFAULT 0,
 approved_version BIGINT NULL, review_status VARCHAR(24) NOT NULL, forced_off BOOLEAN NOT NULL DEFAULT FALSE,
 forced_reason VARCHAR(500), version BIGINT NOT NULL DEFAULT 0, methods JSON NOT NULL,
 unit_id BIGINT, content_hash CHAR(64), submitted_at DATETIME(6), created_at DATETIME(6) NOT NULL,
 KEY ix_s1_goods_store(store_id,review_status,goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_goods_draft (
 goods_id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 payload JSON NOT NULL, file_refs JSON NOT NULL, archived BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL, KEY ix_s1_goods_drafts(store_id,archived,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_goods_review (
 goods_id BIGINT NOT NULL, content_version BIGINT NOT NULL, payload JSON NOT NULL, content_hash CHAR(64) NOT NULL,
 submitted_at DATETIME(6) NOT NULL, decision VARCHAR(24), reviewer_id BIGINT, reviewed_at DATETIME(6), reason VARCHAR(500),
 PRIMARY KEY(goods_id,content_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_sku_control (
 sku_id BIGINT PRIMARY KEY, goods_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 inventory_version BIGINT NOT NULL DEFAULT 0, cost_version BIGINT NOT NULL DEFAULT 0,
 cost_provided BOOLEAN NOT NULL DEFAULT FALSE, weight_provided BOOLEAN NOT NULL DEFAULT FALSE,
 submitted_once BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 normalized_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, spec_key CHAR(64) NOT NULL,
 historical_reference BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_s1_sku_code(store_id,normalized_code), UNIQUE KEY uk_s1_sku_spec(goods_id,spec_key),
 KEY ix_s1_sku_goods(goods_id,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_inventory_adjustment (
 id BIGINT PRIMARY KEY, sku_id BIGINT NOT NULL, store_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_quantity INT NOT NULL, after_quantity INT NOT NULL, delta_quantity INT NOT NULL,
 before_version BIGINT NOT NULL, after_version BIGINT NOT NULL, reason VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_s1_stock_request(sku_id,actor_id,request_id),
 CHECK(after_quantity BETWEEN 0 AND 9999999), CHECK(after_version=before_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_catalog_control (
 object_type VARCHAR(12) NOT NULL, object_id BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, normalized_name VARCHAR(60) NOT NULL, parent_id BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(object_type,object_id), UNIQUE KEY uk_s1_catalog_name(object_type,parent_id,normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
