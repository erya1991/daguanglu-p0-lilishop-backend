-- Immutable commercial clauses and actual publication/disable instants. No monetary execution facts.
ALTER TABLE li_p0_agreement ADD terms JSON NULL, ADD creator_id BIGINT NULL,
 ADD updated_at DATETIME(6) NULL, ADD predecessor_id BIGINT NULL;
ALTER TABLE li_p0_commission_rule ADD terms JSON NULL, ADD creator_id BIGINT NULL,
 ADD updated_at DATETIME(6) NULL, ADD published_at DATETIME(6) NULL, ADD disabled_at DATETIME(6) NULL,
 ADD predecessor_id BIGINT NULL;
