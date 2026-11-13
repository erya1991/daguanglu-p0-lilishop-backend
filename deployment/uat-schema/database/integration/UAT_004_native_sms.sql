-- Native SMS local records only. No provider credentials, requests or fabricated delivery.
CREATE TABLE li_sms_sign (id BIGINT PRIMARY KEY,sign_name VARCHAR(255),sign_source INT,remark VARCHAR(1000),business_license VARCHAR(1000),license VARCHAR(1000),sign_status INT,reason VARCHAR(1000)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_sms_template (id BIGINT PRIMARY KEY,template_name VARCHAR(255),template_type INT,remark VARCHAR(1000),template_content TEXT,template_status INT,template_code VARCHAR(255),reason VARCHAR(1000)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE li_sms_reach (id BIGINT PRIMARY KEY,sign_name VARCHAR(255),sms_name VARCHAR(255),message_code VARCHAR(255),context TEXT,sms_range VARCHAR(16),num VARCHAR(64),create_time DATETIME(6)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Correct only untouched run-owned menu metadata. Native role grants are not modified.
UPDATE li_menu SET permission='/manager/sms/*' WHERE name='uat-sms' AND create_by='UAT_20261109033718462' AND update_by IS NULL AND permission='/manager/setting/setting/*';
UPDATE li_menu SET front_route='sys/message/sms',permission='/manager/sms/sign/*' WHERE name='uat-sms-sign' AND create_by='UAT_20261109033718462' AND update_by IS NULL AND front_route='sys/message/smsSign' AND permission='/manager/setting/setting/*';
