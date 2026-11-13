-- Non-unique indexes constrain member-specific lookup and row locks during account changes.
-- Execute once via the migration ledger after checking for name/schema conflicts.
CREATE INDEX ix_p0_wechat_identity_member_app ON li_wechat_identity(member_id,app_id);
CREATE INDEX ix_p0_clerk_member ON li_clerk(member_id);
CREATE INDEX ix_p0_store_member ON li_store(member_id);
