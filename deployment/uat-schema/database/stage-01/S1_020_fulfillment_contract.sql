-- REQUIREMENTS V0.4 canonical public code is STORE_REDEMPTION; preserve the executed 005 as history.
ALTER TABLE li_p0_point_control DROP CHECK li_p0_point_control_chk_1;
UPDATE li_p0_point_control SET type='STORE_REDEMPTION' WHERE type='IN_STORE_VERIFY';
ALTER TABLE li_p0_point_control ADD CONSTRAINT ck_s1_point_type CHECK(type IN ('COMMUNITY_PICKUP','STORE_REDEMPTION')),
 ADD opening_hours JSON NULL;
CREATE TABLE li_p0_fulfillment_history (
 store_id BIGINT NOT NULL, version BIGINT NOT NULL, methods JSON NOT NULL, actor_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL, PRIMARY KEY(store_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
