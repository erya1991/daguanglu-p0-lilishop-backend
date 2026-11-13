-- New dgl_p0_s45_mock only; independent provider requests, not business success projections.
CREATE TABLE li_p0_mock_funds_request(
 request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 transaction_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,parent_request VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 amount_cent BIGINT NOT NULL,request_hash CHAR(64) NOT NULL,request_json JSON NOT NULL,
 state VARCHAR(24) NOT NULL,result_json JSON NOT NULL,send_count INT NOT NULL,
 created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,
 KEY ix_mock_funds_transaction(transaction_id,kind),KEY ix_mock_funds_parent(parent_request,kind),
 CHECK(amount_cent>=0),CHECK(kind IN ('REFUND','RETURN','SHARE','FINISH'))
) ENGINE=InnoDB;
CREATE TABLE li_p0_mock_funds_control(
 transaction_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,scenario VARCHAR(32) NOT NULL,
 PRIMARY KEY(transaction_id,kind)
) ENGINE=InnoDB;
CREATE TABLE li_p0_mock_funds_capacity(
 transaction_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,external_count INT NOT NULL,complete BIT NOT NULL,
 PRIMARY KEY(transaction_id,kind),CHECK(external_count>=0)
) ENGINE=InnoDB;
