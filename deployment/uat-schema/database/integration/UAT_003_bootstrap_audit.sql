-- One-time bootstrap receipt only; never a permission source.
CREATE TABLE li_p0_uat_bootstrap (run_id VARCHAR(64) PRIMARY KEY, completed_at DATETIME(6) NOT NULL, source VARCHAR(64) NOT NULL) ENGINE=InnoDB;
