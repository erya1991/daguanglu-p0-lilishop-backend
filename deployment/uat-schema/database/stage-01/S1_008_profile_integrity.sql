-- Approved M03/M08 lengths and configurable qualification requirements; native records remain canonical.
ALTER TABLE li_store MODIFY store_desc VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD UNIQUE KEY uk_s1_store_member(member_id);
ALTER TABLE li_store_detail ADD UNIQUE KEY uk_s1_store_detail(store_id);
ALTER TABLE li_clerk ADD UNIQUE KEY uk_s1_member_clerk(member_id);
ALTER TABLE li_p0_store_control ADD profile_extra JSON NULL;
ALTER TABLE li_p0_qualification ADD details JSON NULL;
CREATE TABLE li_p0_qualification_type (
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, name VARCHAR(100) NOT NULL,
 scope VARCHAR(24) NOT NULL, required_fields JSON NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, source VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO li_p0_qualification_type(code,name,scope,required_fields,enabled,source) VALUES
 ('BUSINESS_LICENSE','S1_TEST营业执照','STORE','["number","holder","issuer"]',TRUE,'S1_TEST'),
 ('FOOD_OPERATION','S1_TEST食品经营材料','CATEGORY','["number","holder","issuer"]',TRUE,'S1_TEST');
