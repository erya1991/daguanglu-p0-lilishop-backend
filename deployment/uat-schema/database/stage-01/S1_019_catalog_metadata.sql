-- Native product balances remain in li_goods/li_goods_sku. Sidecars store control and provenance only.
ALTER TABLE li_p0_goods_control ADD category_id BIGINT NULL, ADD ever_listed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE li_p0_sku_control ADD source_run VARCHAR(32) NULL;
CREATE TABLE li_p0_catalog_history (
 object_type VARCHAR(12) NOT NULL, object_id BIGINT NOT NULL, version BIGINT NOT NULL,
 snapshot JSON NOT NULL, actor_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(object_type,object_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE li_goods CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE li_goods_sku CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE li_goods_unit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE li_goods_gallery CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE li_goods MODIFY sn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE li_goods_sku MODIFY sn VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
INSERT INTO li_menu(id,create_by,delete_flag,title,name,permission,level,sort_order,parent_id)
 VALUES(710000000000009001,'S1_TEST',0,'S1 商品查阅','s1-goods-read','s1.goods.read',1,1,0);
INSERT INTO li_role_menu(id,create_by,delete_flag,role_id,menu_id,is_super)
 VALUES(710000000000009002,'S1_TEST',0,710000000000000001,710000000000009001,0),
 (710000000000009003,'S1_TEST',0,710000000000000002,710000000000009001,0);
