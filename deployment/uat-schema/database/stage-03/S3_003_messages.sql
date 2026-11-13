-- Native message identities; Stage3 link supplies event provenance and recipient idempotency.
CREATE TABLE li_message (
 id BIGINT PRIMARY KEY, create_by VARCHAR(255) NULL, create_time DATETIME(6) NULL,
 delete_flag BIT(1) DEFAULT 0, update_by VARCHAR(255) NULL, update_time DATETIME(6) NULL,
 title VARCHAR(120) NOT NULL, content VARCHAR(1000) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE li_member_message (
 id BIGINT PRIMARY KEY, create_by VARCHAR(255) NULL, create_time DATETIME(6) NULL,
 delete_flag BIT(1) DEFAULT 0, update_by VARCHAR(255) NULL, update_time DATETIME(6) NULL,
 member_id BIGINT NOT NULL, member_name VARCHAR(255) NULL, title VARCHAR(120) NOT NULL,
 content VARCHAR(1000) NOT NULL, message_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'UN_READY'
) ENGINE=InnoDB;
CREATE TABLE li_store_message (
 id BIGINT PRIMARY KEY, create_by VARCHAR(255) NULL, create_time DATETIME(6) NULL,
 message_id BIGINT NOT NULL, store_id BIGINT NOT NULL, store_name VARCHAR(255) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'UN_READY'
) ENGINE=InnoDB;
CREATE TABLE li_p0_s3_message_link (
 id BIGINT PRIMARY KEY, event_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
 recipient_domain VARCHAR(16) NOT NULL, recipient_id BIGINT NOT NULL, template VARCHAR(32) NOT NULL,
 native_message_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_s3_message_delivery(event_id,recipient_domain,recipient_id,template),
 UNIQUE KEY uk_s3_native_receipt(recipient_domain,native_message_id),
 KEY ix_s3_message_recipient(recipient_domain,recipient_id,id)
) ENGINE=InnoDB;
