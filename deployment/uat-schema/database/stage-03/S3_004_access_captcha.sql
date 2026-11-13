-- Exact Stage3 TEST-only permission catalogue; no existing person gains ownership or a new write grant.
CREATE TABLE li_verification_source (
 id BIGINT PRIMARY KEY,create_by VARCHAR(255) NULL,create_time DATETIME(6) NULL,delete_flag BIT(1) DEFAULT 0,
 update_by VARCHAR(255) NULL,update_time DATETIME(6) NULL,
 name VARCHAR(120) NOT NULL,resource VARCHAR(1000) NOT NULL,type VARCHAR(16) NOT NULL
) ENGINE=InnoDB;
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order,front_route,front_component)
VALUES(730000000000000001,0,'s3-orders','商城订单','s3.order.read','0',1,30,'/s3/orders','p0-stage3/Orders'),
(730000000000000002,0,'s3-verifications','核销记录','s3.verification.read','0',1,31,'/s3/verifications','p0-stage3/Verifications');
INSERT INTO li_role(id,delete_flag,default_role,name,description) VALUES(730000000000000010,0,0,'S3_TEST_ORDER_OBSERVER','S3_TEST');
INSERT INTO li_role_menu(id,delete_flag,is_super,menu_id,role_id) VALUES
(730000000000000011,0,0,'730000000000000001','730000000000000010'),
(730000000000000012,0,0,'730000000000000002','730000000000000010');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001000',0,'S3_order.read','order.read','s3.order.read','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001001',0,'S3_fulfillment.write','fulfillment.write','s3.fulfillment.write','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001002',0,'S3_order.exception','order.exception','s3.order.exception','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001003',0,'S3_verification.lookup','verification.lookup','s3.verification.lookup','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001004',0,'S3_verification.confirm','verification.confirm','s3.verification.confirm','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES('730000000000001005',0,'S3_message.read','message.read','s3.message.read','0',1,'0');
