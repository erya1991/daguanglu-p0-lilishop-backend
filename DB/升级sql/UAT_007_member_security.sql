-- Persistent member-wide session revocation; grants and identity mappings remain unchanged.
CREATE TABLE li_p0_member_security (
  member_id BIGINT NOT NULL PRIMARY KEY,
  session_cutoff BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
