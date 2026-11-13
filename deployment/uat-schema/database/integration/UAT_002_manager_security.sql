-- Append-only UAT integration security metadata. Grants remain in native tables.
CREATE TABLE li_p0_manager_guard (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO li_p0_manager_guard(id) VALUES(1);
CREATE TABLE li_p0_manager_security (
  admin_id VARCHAR(64) NOT NULL PRIMARY KEY,
  session_cutoff BIGINT NOT NULL
) ENGINE=InnoDB;
