-- Original contractual dates remain immutable. Future replacement is a separate auditable fact.
ALTER TABLE li_p0_agreement ADD replaced_at DATETIME(6) NULL;
ALTER TABLE li_p0_commission_rule ADD replaced_at DATETIME(6) NULL;
CREATE TABLE li_p0_cooperation_cutover (
 id BIGINT PRIMARY KEY, object_type VARCHAR(16) NOT NULL, previous_id BIGINT NOT NULL,
 replacement_id BIGINT NOT NULL, cutoff_at DATETIME(6) NOT NULL, audit_reference BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_s1_cooperation_cutover(object_type,previous_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
