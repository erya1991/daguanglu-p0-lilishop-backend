-- Prevent a worker finishing an old generation from losing a concurrent wake-up.
ALTER TABLE li_p0_s2_task ADD generation BIGINT NOT NULL DEFAULT 0;
