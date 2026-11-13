-- Preserve the business reason separately after final close clears protection.
ALTER TABLE li_p0_trade_state ADD COLUMN close_reason VARCHAR(64) NULL;
