-- Receiver evidence belongs to the same immutable channel configuration version as the latest sync.
ALTER TABLE li_p0_receiver_relation ADD config_version BIGINT NULL;
