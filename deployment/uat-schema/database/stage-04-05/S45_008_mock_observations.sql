-- Only dgl_p0_s45_mock. Independent provider observations, never business success fixtures.
CREATE TABLE li_p0_mock_funds_observation(
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NOT NULL,observed_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_mock_funds_observation(request_no,result_hash),
 KEY ix_mock_funds_asof(request_no,observed_at,id)
) ENGINE=InnoDB;
-- These are exact existing provider results at their recorded update time, not invented earlier states.
INSERT INTO li_p0_mock_funds_observation(request_no,result_hash,result_json,observed_at)
 SELECT request_no,SHA2(CAST(result_json AS CHAR),256),result_json,updated_at FROM li_p0_mock_funds_request;
