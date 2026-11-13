-- Stage45 account-level reconciliation: no fabricated owning order; preserve original signed bill.
-- Target: dgl_p0_s45 only. Applied after S45_002; existing monetary facts remain unchanged.
ALTER TABLE li_p0_s45_audit MODIFY COLUMN order_id BIGINT NULL;
ALTER TABLE li_p0_s45_reconciliation ADD COLUMN source_json JSON NULL AFTER source_hash;
