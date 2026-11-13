CREATE TABLE li_p0_fulfillment_config (
 store_id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0, methods JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_point_control (
 point_id BIGINT PRIMARY KEY, store_id BIGINT NOT NULL, type VARCHAR(24) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
 province_code VARCHAR(32) NOT NULL, city_code VARCHAR(32) NOT NULL, district_code VARCHAR(32) NOT NULL,
 contact_name VARCHAR(40) NOT NULL, opening_description VARCHAR(500) NOT NULL, instructions VARCHAR(500),
 created_at DATETIME(6) NOT NULL, KEY ix_s1_points(store_id,type,enabled),
 CHECK(type IN ('COMMUNITY_PICKUP','IN_STORE_VERIFY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_p0_point_history (
 point_id BIGINT NOT NULL, version BIGINT NOT NULL, snapshot JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(point_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
