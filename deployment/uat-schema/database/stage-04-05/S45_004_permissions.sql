-- Stage45 native permission catalog only; no grants to old identities or stores.
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001000,0,'S45_channel.read','channel.read','s45.channel.read','0',1,0);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001001,0,'S45_channel.sync','channel.sync','s45.channel.sync','0',1,1);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001002,0,'S45_rule.read','rule.read','s45.rule.read','0',1,2);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001003,0,'S45_aftersale.read','aftersale.read','s45.aftersale.read','0',1,3);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001004,0,'S45_aftersale.resolve','aftersale.resolve','s45.aftersale.resolve','0',1,4);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001005,0,'S45_evaluation.read','evaluation.read','s45.evaluation.read','0',1,5);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001006,0,'S45_evaluation.moderate','evaluation.moderate','s45.evaluation.moderate','0',1,6);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001007,0,'S45_funds.read','funds.read','s45.funds.read','0',1,7);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001008,0,'S45_funds.recover','funds.recover','s45.funds.recover','0',1,8);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001009,0,'S45_reconciliation.read','reconciliation.read','s45.reconciliation.read','0',1,9);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001010,0,'S45_reconciliation.create','reconciliation.create','s45.reconciliation.create','0',1,10);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001011,0,'S45_reconciliation.review','reconciliation.review','s45.reconciliation.review','0',1,11);
INSERT INTO li_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000001012,0,'S45_message.read','message.read','s45.message.read','0',1,12);
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002000,0,'S45_aftersale.read','aftersale.read','s45.aftersale.read','0',1,'0');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002001,0,'S45_aftersale.review','aftersale.review','s45.aftersale.review','0',1,'1');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002002,0,'S45_aftersale.receive','aftersale.receive','s45.aftersale.receive','0',1,'2');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002003,0,'S45_evaluation.read','evaluation.read','s45.evaluation.read','0',1,'3');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002004,0,'S45_funds.read','funds.read','s45.funds.read','0',1,'4');
INSERT INTO li_store_menu(id,delete_flag,name,title,permission,parent_id,level,sort_order) VALUES(744500000000002005,0,'S45_message.read','message.read','s45.message.read','0',1,'5');
-- Operational role/identity assignment belongs only to separately marked new-run synthetic fixtures.
INSERT INTO li_after_sale_reason(id,delete_flag,reason,service_type) VALUES(744500000000003001,0,'商品问题','RETURN_MONEY');
INSERT INTO li_after_sale_reason(id,delete_flag,reason,service_type) VALUES(744500000000003002,0,'未收到商品','RETURN_MONEY');
INSERT INTO li_after_sale_reason(id,delete_flag,reason,service_type) VALUES(744500000000003003,0,'其他','RETURN_MONEY');
INSERT INTO li_after_sale_reason(id,delete_flag,reason,service_type) VALUES(744500000000003004,0,'商品问题','RETURN_GOODS');
INSERT INTO li_after_sale_reason(id,delete_flag,reason,service_type) VALUES(744500000000003005,0,'其他','RETURN_GOODS');
