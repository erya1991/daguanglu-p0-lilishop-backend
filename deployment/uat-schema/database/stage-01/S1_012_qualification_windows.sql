-- Preserve approval, future renewal and revocation instants without extending a pending certificate.
ALTER TABLE li_p0_qualification ADD approved_at DATETIME(6) NULL,
 ADD superseded_at DATETIME(6) NULL, ADD revoked_at DATETIME(6) NULL,
 ADD version BIGINT NOT NULL DEFAULT 0, ADD revocation_reason VARCHAR(500) NULL;
UPDATE li_p0_qualification q JOIN li_p0_application a ON a.id=q.application_id
 SET q.approved_at=COALESCE(a.reviewed_at,q.created_at) WHERE q.approved_at IS NULL;
UPDATE li_p0_qualification q SET q.superseded_at=q.created_at WHERE q.status='SUPERSEDED';
