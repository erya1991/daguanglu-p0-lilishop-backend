-- Preserve Stage3 message facts; native broadcast metadata is nullable for existing business notifications.
ALTER TABLE li_message ADD COLUMN message_range VARCHAR(16) NULL, ADD COLUMN message_client VARCHAR(16) NULL;
