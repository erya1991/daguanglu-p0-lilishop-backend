-- Re-invitation preserves every prior grant source instead of replacing approval evidence.
ALTER TABLE li_p0_grant_evidence DROP INDEX uk_s1_grant_binding,
 ADD active_binding_key VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD KEY ix_s1_grant_binding(binding_id,binding_version,store_id);
UPDATE li_p0_grant_evidence SET active_binding_key=CONCAT(binding_id,':',binding_version,':',store_id) WHERE active=TRUE;
ALTER TABLE li_p0_grant_evidence ADD UNIQUE KEY uk_s1_active_grant(active_binding_key);
