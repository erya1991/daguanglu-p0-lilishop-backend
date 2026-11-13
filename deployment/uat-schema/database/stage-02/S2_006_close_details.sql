-- Resident-safe affected Store and field categories; immutable order snapshots remain untouched.
ALTER TABLE li_p0_trade_state ADD COLUMN close_details JSON NULL;
