-- Only fresh dgl_p0_s45; retain grouped historical exception observations and projection observation time.
ALTER TABLE li_store_flow ADD COLUMN p0_projected_at DATETIME(6) NULL;
CREATE TABLE li_p0_s45_exception_observation(
 exception_id BIGINT NOT NULL, detail_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 detail_json JSON NOT NULL, first_observed_at DATETIME(6) NOT NULL, last_observed_at DATETIME(6) NOT NULL,
 observations BIGINT UNSIGNED NOT NULL DEFAULT 1,
 PRIMARY KEY(exception_id,detail_hash),CHECK(observations>0)
) ENGINE=InnoDB;
-- Preserve only the actually recorded current observation; no reconstruction of overwritten history.
INSERT INTO li_p0_s45_exception_observation
 SELECT id,SHA2(CAST(detail_json AS CHAR),256),detail_json,created_at,updated_at,1 FROM li_p0_s45_exception;
