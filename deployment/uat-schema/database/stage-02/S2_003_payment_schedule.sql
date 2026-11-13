-- Append-only extension of the already applied S2 core.
ALTER TABLE li_p0_trade_state ADD query_step INT NOT NULL DEFAULT 0, ADD next_query_at DATETIME(6) NULL;
CREATE INDEX ix_s2_trade_recovery ON li_p0_trade_state(status,next_query_at);
