-- REVIEW ONLY. S3_004 creates this table; S2_007 MUST NOT be replayed in this chain.
-- Preserve the longer S3 resource field; permit native nullable fields without reducing lengths.
ALTER TABLE li_verification_source
 MODIFY name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 MODIFY resource VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 MODIFY type VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
