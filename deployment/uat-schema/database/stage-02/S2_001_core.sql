-- Stage2 isolated schema only. Native model mappings, no dump/data/import from Stage1.
-- Monetary native compatibility columns are DECIMAL(20,2); authoritative P0 arithmetic is BIGINT cents.
CREATE TABLE `li_trade` (
  `id` BIGINT NOT NULL,
  `create_by` VARCHAR(255) NULL,
  `create_time` DATETIME(6) NULL,
  `delete_flag` BIT(1) NULL DEFAULT 0,
  `update_by` VARCHAR(255) NULL,
  `update_time` DATETIME(6) NULL,
  `sn` VARCHAR(64) NULL,
  `member_id` BIGINT NULL,
  `member_name` TEXT NULL,
  `payment_method` TEXT NULL,
  `pay_status` TEXT NULL,
  `flow_price` DECIMAL(20,2) NULL,
  `goods_price` DECIMAL(20,2) NULL,
  `freight_price` DECIMAL(20,2) NULL,
  `discount_price` DECIMAL(20,2) NULL,
  `delivery_method` TEXT NULL,
  `consignee_name` TEXT NULL,
  `consignee_mobile` TEXT NULL,
  `consignee_address_path` TEXT NULL,
  `consignee_address_id_path` TEXT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_li_trade_sn` (`sn`),
  KEY `ix_li_trade_member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE `li_order` (
  `id` BIGINT NOT NULL,
  `create_by` VARCHAR(255) NULL,
  `create_time` DATETIME(6) NULL,
  `delete_flag` BIT(1) NULL DEFAULT 0,
  `update_by` VARCHAR(255) NULL,
  `update_time` DATETIME(6) NULL,
  `sn` VARCHAR(64) NULL,
  `trade_sn` VARCHAR(64) NULL,
  `store_id` BIGINT NULL,
  `store_name` TEXT NULL,
  `member_id` BIGINT NULL,
  `member_name` TEXT NULL,
  `order_status` TEXT NULL,
  `pay_status` TEXT NULL,
  `deliver_status` TEXT NULL,
  `receivable_no` TEXT NULL,
  `payment_method` TEXT NULL,
  `deduction_method` TEXT NULL,
  `payment_time` DATETIME(6) NULL,
  `consignee_name` TEXT NULL,
  `consignee_mobile` TEXT NULL,
  `delivery_method` TEXT NULL,
  `consignee_address_path` TEXT NULL,
  `consignee_address_id_path` TEXT NULL,
  `consignee_detail` TEXT NULL,
  `flow_price` DECIMAL(20,2) NULL,
  `goods_price` DECIMAL(20,2) NULL,
  `freight_price` DECIMAL(20,2) NULL,
  `discount_price` DECIMAL(20,2) NULL,
  `update_price` DECIMAL(20,2) NULL,
  `logistics_no` TEXT NULL,
  `logistics_code` TEXT NULL,
  `logistics_name` TEXT NULL,
  `weight` DECIMAL(20,3) NULL,
  `goods_num` INT NULL,
  `remark` TEXT NULL,
  `seller_remark` TEXT NULL,
  `cancel_reason` TEXT NULL,
  `complete_time` DATETIME(6) NULL,
  `logistics_time` DATETIME(6) NULL,
  `pay_order_no` TEXT NULL,
  `client_type` TEXT NULL,
  `need_receipt` BIT(1) NULL,
  `parent_order_sn` VARCHAR(64) NULL,
  `promotion_id` TEXT NULL,
  `order_type` TEXT NULL,
  `order_promotion_type` TEXT NULL,
  `price_detail` TEXT NULL,
  `can_return` BIT(1) NULL,
  `verification_code` TEXT NULL,
  `distribution_id` TEXT NULL,
  `use_store_member_coupon_ids` TEXT NULL,
  `use_platform_member_coupon_id` TEXT NULL,
  `qr_code` TEXT NULL,
  `store_address_path` TEXT NULL,
  `store_address_mobile` TEXT NULL,
  `store_address_center` TEXT NULL,
  `live_room_id` TEXT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_li_order_sn` (`sn`),
  KEY `ix_li_order_member` (`member_id`),
  KEY `ix_li_order_trade` (`trade_sn`)
) ENGINE=InnoDB;

CREATE TABLE `li_order_item` (
  `id` BIGINT NOT NULL,
  `create_by` VARCHAR(255) NULL,
  `create_time` DATETIME(6) NULL,
  `delete_flag` BIT(1) NULL DEFAULT 0,
  `update_by` VARCHAR(255) NULL,
  `update_time` DATETIME(6) NULL,
  `order_sn` VARCHAR(64) NULL,
  `sn` VARCHAR(64) NULL,
  `unit_price` DECIMAL(20,2) NULL,
  `sub_total` DECIMAL(20,2) NULL,
  `goods_id` BIGINT NULL,
  `sku_id` BIGINT NULL,
  `num` INT NULL,
  `trade_sn` VARCHAR(64) NULL,
  `image` TEXT NULL,
  `goods_name` TEXT NULL,
  `category_id` TEXT NULL,
  `snapshot_id` BIGINT NULL,
  `specs` TEXT NULL,
  `promotion_type` TEXT NULL,
  `promotion_id` TEXT NULL,
  `goods_price` DECIMAL(20,2) NULL,
  `flow_price` DECIMAL(20,2) NULL,
  `comment_status` TEXT NULL,
  `after_sale_status` TEXT NULL,
  `price_detail` TEXT NULL,
  `complain_status` TEXT NULL,
  `complain_id` TEXT NULL,
  `return_goods_number` INT NULL,
  `is_refund` TEXT NULL,
  `refund_price` DECIMAL(20,2) NULL,
  `deliver_number` INT NULL,
  `card_key_delivered` BIT(1) NULL,
  `card_key_fulfill_status` TEXT NULL,
  `card_key_fulfill_message` TEXT NULL,
  `remain_deliver_number` INT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_li_order_item_sn` (`sn`),
  KEY `ix_li_order_item_trade` (`trade_sn`),
  KEY `ix_li_order_item_sku` (`sku_id`)
) ENGINE=InnoDB;

CREATE TABLE `li_order_log` (
  `id` BIGINT NOT NULL,
  `create_by` VARCHAR(255) NULL,
  `create_time` DATETIME(6) NULL,
  `delete_flag` BIT(1) NULL DEFAULT 0,
  `update_by` VARCHAR(255) NULL,
  `update_time` DATETIME(6) NULL,
  `order_sn` VARCHAR(64) NULL,
  `operator_id` TEXT NULL,
  `operator_type` TEXT NULL,
  `operator_name` TEXT NULL,
  `message` TEXT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE `li_member_address` (
  `id` BIGINT NOT NULL,
  `create_by` VARCHAR(255) NULL,
  `create_time` DATETIME(6) NULL,
  `delete_flag` BIT(1) NULL DEFAULT 0,
  `update_by` VARCHAR(255) NULL,
  `update_time` DATETIME(6) NULL,
  `member_id` BIGINT NULL,
  `name` TEXT NULL,
  `mobile` TEXT NULL,
  `consignee_address_path` TEXT NULL,
  `consignee_address_id_path` TEXT NULL,
  `detail` TEXT NULL,
  `is_default` BIT(1) NULL,
  `alias` TEXT NULL,
  `lon` TEXT NULL,
  `lat` TEXT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_li_member_address_member` (`member_id`)
) ENGINE=InnoDB;

CREATE TABLE li_p0_s2_member_scope (member_id BIGINT PRIMARY KEY, run_id VARCHAR(32) NOT NULL, permission VARCHAR(32) NOT NULL, evidence VARCHAR(200) NOT NULL) ENGINE=InnoDB;
CREATE TABLE li_p0_s2_sku_scope (sku_id BIGINT PRIMARY KEY, source_run VARCHAR(32) NOT NULL, target_run VARCHAR(32) NOT NULL, evidence VARCHAR(200) NOT NULL) ENGINE=InnoDB;
CREATE TABLE li_p0_cart_item (
 id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 quantity INT NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_cart_member_sku(member_id,sku_id), KEY ix_cart_member(member_id,deleted),
 CHECK(quantity BETWEEN 0 AND 99)
) ENGINE=InnoDB;
CREATE TABLE li_p0_cart_operation (member_id BIGINT NOT NULL, operation_id VARCHAR(64) NOT NULL, payload_hash CHAR(64) NOT NULL, response_json JSON NOT NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY(member_id,operation_id)) ENGINE=InnoDB;
CREATE TABLE li_p0_member_checkout (member_id BIGINT PRIMARY KEY, default_address_id BIGINT NULL, version BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB;
CREATE TABLE li_p0_address_control (address_id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0, province_code CHAR(6) NOT NULL, city_code CHAR(6) NOT NULL, district_code CHAR(6) NOT NULL, KEY ix_address_member(member_id)) ENGINE=InnoDB;
CREATE TABLE li_p0_quote (id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, payload_json JSON NOT NULL, content_hash CHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL, KEY ix_quote_owner(member_id,id)) ENGINE=InnoDB;
CREATE TABLE li_p0_checkout_intent (
 member_id BIGINT NOT NULL, intent_id VARCHAR(64) NOT NULL, payload_hash CHAR(64) NOT NULL, payload_json JSON NOT NULL,
 status VARCHAR(24) NOT NULL, trade_id BIGINT NULL, failure_code VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(member_id,intent_id), UNIQUE KEY uk_intent_trade(trade_id), KEY ix_intent_pending(status,updated_at)
) ENGINE=InnoDB;
CREATE TABLE li_p0_trade_state (
 trade_id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, intent_id VARCHAR(64) NOT NULL, mode VARCHAR(8) NOT NULL,
 status VARCHAR(24) NOT NULL, payment_status VARCHAR(24) NOT NULL, close_status VARCHAR(24) NOT NULL DEFAULT 'NONE',
 protection VARCHAR(64) NULL, amount_cent BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, pay_deadline DATETIME(6) NOT NULL, unknown_since DATETIME(6) NULL,
 attention_at DATETIME(6) NULL, verified_at DATETIME(6) NULL, UNIQUE KEY uk_trade_intent(member_id,intent_id),
 KEY ix_trade_member(member_id,status,created_at), CHECK(mode='MOCK'), CHECK(amount_cent>0)
) ENGINE=InnoDB;
CREATE TABLE li_p0_order_snapshot (
 order_id BIGINT PRIMARY KEY, trade_id BIGINT NOT NULL, member_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 payload_json JSON NOT NULL, content_hash CHAR(64) NOT NULL, goods_cent BIGINT NOT NULL, freight_cent BIGINT NOT NULL,
 amount_cent BIGINT NOT NULL, commission_cent BIGINT NOT NULL, merchant_cent BIGINT NOT NULL,
 UNIQUE KEY uk_order_trade_store(trade_id,store_id), CHECK(amount_cent=goods_cent+freight_cent), CHECK(merchant_cent+commission_cent=amount_cent)
) ENGINE=InnoDB;
CREATE TABLE li_p0_cart_consumption (
 cart_id BIGINT NOT NULL, cart_version BIGINT NOT NULL, member_id BIGINT NOT NULL, quantity INT NOT NULL,
 intent_id VARCHAR(64) NOT NULL, trade_id BIGINT NOT NULL, cleanup_version BIGINT NOT NULL,
 cleanup_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(cart_id,cart_version), KEY ix_consumption_trade(trade_id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_inventory_hold (
 id BIGINT PRIMARY KEY, trade_id BIGINT NOT NULL, order_item_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 quantity INT NOT NULL, status VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_hold_item(order_item_id), KEY ix_hold_sku_status(sku_id,status), KEY ix_hold_trade(trade_id), CHECK(quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB;
CREATE TABLE li_p0_inventory_event (
 id BIGINT PRIMARY KEY, sku_id BIGINT NOT NULL, trade_id BIGINT NULL, event_type VARCHAR(24) NOT NULL,
 before_a INT NOT NULL, after_a INT NOT NULL, before_h INT NOT NULL, after_h INT NOT NULL,
 inventory_version BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_inventory_event_version(sku_id,inventory_version), CHECK(after_a>=0), CHECK(after_h>=0), CHECK(after_a+after_h<=9999999)
) ENGINE=InnoDB;
CREATE TABLE li_p0_payment (
 trade_id BIGINT PRIMARY KEY, combine_no VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, request_json JSON NOT NULL,
 status VARCHAR(24) NOT NULL, dispatch_status VARCHAR(24) NOT NULL DEFAULT 'READY', version BIGINT NOT NULL DEFAULT 0,
 result_json JSON NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, UNIQUE KEY uk_payment_combine(combine_no)
) ENGINE=InnoDB;
CREATE TABLE li_p0_payment_operation (
 id BIGINT PRIMARY KEY, trade_id BIGINT NOT NULL, operation_type VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL,
 operation_id VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, lease_owner VARCHAR(64) NULL, lease_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_payment_operation(trade_id,operation_type,operation_id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_payment_evidence (
 event_id VARCHAR(100) PRIMARY KEY, trade_id BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL,
 payload_json JSON NOT NULL, validation VARCHAR(24) NOT NULL, created_at DATETIME(6) NOT NULL, KEY ix_evidence_trade(trade_id)
) ENGINE=InnoDB;
CREATE TABLE li_p0_s2_task (
 id BIGINT PRIMARY KEY, run_id VARCHAR(32) NOT NULL, task_type VARCHAR(24) NOT NULL, target_key VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL,
 lease_owner VARCHAR(64) NULL, lease_until DATETIME(6) NULL, last_reason VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_s2_task(task_type,target_key), KEY ix_s2_due(status,next_attempt_at)
) ENGINE=InnoDB;
