-- A shared/exclusive lock coordinates global policy changes with short Store mutations.
CREATE TABLE li_p0_consistency_guard (id INT PRIMARY KEY, purpose VARCHAR(80) NOT NULL) ENGINE=InnoDB;
INSERT INTO li_p0_consistency_guard VALUES(1,'Stage1 global channel and catalog facts');
ALTER TABLE li_p0_channel_operation ADD failure_code VARCHAR(80) NULL;
