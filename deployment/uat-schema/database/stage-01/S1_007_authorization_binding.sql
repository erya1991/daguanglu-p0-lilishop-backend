-- Additional exact evidence and irrevocable session cutoffs identified while connecting Stage0 authorization.
-- No historical account is granted ownership by this migration.
ALTER TABLE li_p0_application ADD identity_application VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD binding_id BIGINT NULL, ADD binding_version BIGINT NULL;
ALTER TABLE li_p0_staff_invitation ADD identity_application VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD binding_id BIGINT NULL, ADD binding_version BIGINT NULL;
ALTER TABLE li_p0_clerk_control ADD session_cutoff DATETIME(6) NULL;
