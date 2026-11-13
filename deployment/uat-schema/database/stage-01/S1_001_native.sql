-- Stage1 isolated database only. A01 CREATE statements; NO historical data or DROP statements.
-- Schema compatibility: Stage0 P03 identity; current Goods schedule and File category fields.
CREATE TABLE `li_admin_user`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `avatar` varchar(1000) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '用户头像',
  `department_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '所属部门ID',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '备注',
  `email` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '邮件',
  `is_super` bit(1) NULL DEFAULT NULL COMMENT '是否是超级管理员 超级管理员/普通管理员',
  `mobile` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '手机',
  `nick_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '昵称',
  `password` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '密码',
  `sex` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `status` bit(1) NULL DEFAULT NULL COMMENT '状态 默认true正常 false禁用',
  `username` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '用户名',
  `role_ids` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '角色ID集合',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `UK_sh2dyl78jk1vxtlyohwr5wht9`(`username` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_menu`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '说明备注',
  `front_route` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '前端路由',
  `icon` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图标',
  `level` int NULL DEFAULT NULL COMMENT '层级',
  `name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '菜单/权限名称',
  `parent_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '父id',
  `path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '赋权API地址,正则表达式',
  `sort_order` decimal(10, 2) NULL DEFAULT NULL COMMENT '排序值',
  `title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '菜单标题',
  `front_component` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '文件地址',
  `permission` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '权限url',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `default_role` bit(1) NULL DEFAULT NULL COMMENT '是否为注册默认角色',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '备注',
  `name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '角色名',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_role_menu`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `is_super` bit(1) NULL DEFAULT NULL COMMENT '是否拥有操作数据权限',
  `menu_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '菜单',
  `role_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '角色ID',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_user_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `role_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '角色唯一id',
  `user_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '用户唯一id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_department`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `parent_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '父ID',
  `sort_order` decimal(10, 2) NULL DEFAULT NULL COMMENT '排序值',
  `title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '部门名称',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_department_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `department_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '部门ID',
  `role_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '角色ID',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_member`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `birthday` datetime(6) NULL DEFAULT NULL COMMENT '会员生日',
  `disabled` bit(1) NULL DEFAULT NULL COMMENT '会员状态',
  `face` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员头像',
  `have_store` bit(1) NULL DEFAULT NULL COMMENT '是否开通店铺',
  `mobile` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '手机号码',
  `nick_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '会员昵称',
  `password` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员密码',
  `point` bigint NULL DEFAULT 0 COMMENT '积分数量',
  `sex` int NOT NULL DEFAULT 0 COMMENT '会员性别',
  `store_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺ID',
  `username` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '会员用户名',
  `region` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员地址',
  `region_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员地址ID',
  `client_enum` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '客户端',
  `last_login_date` datetime(6) NULL DEFAULT NULL COMMENT '最后一次登录时间',
  `gradeId` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `experience` bigint NULL DEFAULT NULL,
  `grade_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `total_point` bigint NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE COMMENT 'username唯一索引'
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `member_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员Id',
  `member_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '会员名称',
  `self_operated` bit(1) NOT NULL COMMENT '是否自营',
  `store_disable` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺状态',
  `store_end_time` datetime(6) NULL DEFAULT NULL COMMENT '店铺关闭时间',
  `store_logo` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺logo',
  `store_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺名称',
  `store_address_detail` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '详细地址',
  `store_address_id_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '地址id',
  `store_address_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '地址名称',
  `store_center` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '经纬度',
  `store_desc` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺简介',
  `delivery_score` decimal(10, 2) NULL DEFAULT NULL COMMENT '物流评分',
  `description_score` decimal(10, 2) NULL DEFAULT NULL COMMENT '描述评分',
  `service_score` decimal(10, 2) NULL DEFAULT NULL COMMENT '服务评分',
  `goods_num` int NULL DEFAULT NULL COMMENT '商品数量',
  `collection_num` int NULL DEFAULT NULL COMMENT '收藏数量',
  `yzf_mp_sign` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `yzf_sign` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `merchant_euid` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `page_show` bit(1) NULL DEFAULT NULL COMMENT '默认页面是否开启',
  `self_pick_flag` bit(1) NULL DEFAULT NULL COMMENT '是否开启自提',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_detail`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `company_address` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '公司地址',
  `company_address_id_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '公司地址地区ID',
  `company_address_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '公司地址地区',
  `company_email` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '电子邮箱',
  `company_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '公司名称',
  `company_phone` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '公司电话',
  `dd_code` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '同城配送达达店铺编码',
  `employee_num` int NULL DEFAULT NULL COMMENT '员工总数',
  `goods_management_category` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '店铺经营类目',
  `legal_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '法人身份证',
  `legal_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '法人姓名',
  `legal_photo` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '法人身份证照片',
  `licence_photo` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '营业执照电子版',
  `license_num` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '营业执照号',
  `link_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '联系人姓名',
  `link_phone` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '联系人电话',
  `registered_capital` decimal(10, 2) NULL DEFAULT NULL COMMENT '注册资金',
  `scope` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '法定经营范围',
  `settlement_bank_account_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '结算银行开户行名称',
  `settlement_bank_account_num` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '结算银行开户账号',
  `settlement_bank_branch_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '结算银行开户支行名称',
  `settlement_bank_joint_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '结算银行支行联行号',
  `store_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺ID',
  `store_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺名称',
  `stock_warning` int NULL DEFAULT NULL COMMENT '库存预警数量',
  `settlement_cycle` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '结算周期',
  `sales_consignee_address_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '退货地址Id',
  `sales_consignee_address_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '退货地址名称',
  `sales_consignee_detail` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '退货详细地址',
  `sales_consignee_mobile` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '退货收件人手机',
  `sales_consignee_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '退货收货人姓名',
  `settlement_day` datetime(6) NULL DEFAULT NULL COMMENT '店铺上次结算日',
  `sales_consignor_address_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '发货地址id',
  `sales_consignor_address_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '发货地址名称',
  `sales_consignor_detail` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '发货详细地址',
  `sales_consignor_mobile` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '发货人手机',
  `sales_consignor_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '发货人姓名',
  `app_secret_key` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `merchant_number` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `app_merchant_key` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_clerk`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `clerk_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店员名称',
  `member_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '会员id',
  `store_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店铺id',
  `department_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '部门id',
  `role_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '角色',
  `shopkeeper` bit(1) NULL DEFAULT NULL COMMENT '是否是店主',
  `is_super` bit(1) NULL DEFAULT NULL COMMENT '是否是超级管理员 超级管理员/普通管理员',
  `status` bit(1) NULL DEFAULT NULL COMMENT '状态',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_clerk_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `clerk_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店员唯一id',
  `role_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '角色唯一id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '角色名称',
  `store_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店铺id',
  `default_role` bit(1) NULL DEFAULT NULL COMMENT '是否为注册默认角色',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_menu`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '说明备注',
  `front_route` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '前端路由',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '图标',
  `level` int NULL DEFAULT NULL COMMENT '层级',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '菜单/权限名称',
  `parent_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '父id',
  `path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '赋权API地址,正则表达式',
  `sort_order` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '排序值',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '菜单标题',
  `permission` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '权限url',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_menu_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `role_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '角色id',
  `menu_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '菜单',
  `store_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店铺id',
  `is_super` bit(1) NULL DEFAULT NULL COMMENT '是否拥有操作数据权限，为否则只有查看权限',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_department`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '部门名称',
  `store_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '店铺id',
  `parent_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '父id',
  `sort_order` decimal(20, 2) NULL DEFAULT NULL COMMENT '排序值',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_department_role`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `role_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '角色id',
  `department_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '部门id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_category`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `commission_rate` decimal(10, 2) NULL DEFAULT NULL COMMENT '佣金比例',
  `image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '分类图标',
  `level` int NULL DEFAULT NULL COMMENT '层级',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '分类名称',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父ID',
  `sort_order` int NULL DEFAULT 0 COMMENT '排序值',
  `support_channel` bit(1) NULL DEFAULT NULL COMMENT '是否支持频道',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_goods_unit`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '计量单位名称',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_goods`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `auth_message` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '审核信息',
  `brand_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '品牌ID',
  `buy_count` int NULL DEFAULT 0 COMMENT '购买数量',
  `category_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '分类路径',
  `comment_num` int NULL DEFAULT NULL COMMENT '评论数量',
  `cost` decimal(10, 2) NULL DEFAULT NULL COMMENT '成本价格',
  `goods_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品名称',
  `goods_unit` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '计量单位',
  `goods_video` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品视频',
  `grade` decimal(10, 2) NULL DEFAULT NULL COMMENT '商品好评率',
  `intro` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '商品详情',
  `auth_flag` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '审核状态',
  `market_enable` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '上架状态',
  `mobile_intro` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '商品移动端详情',
  `original` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '原图路径',
  `price` decimal(10, 2) NULL DEFAULT NULL COMMENT '商品价格',
  `quantity` int NULL DEFAULT 0 COMMENT '库存',
  `recommend` bit(1) NULL DEFAULT NULL COMMENT '是否为推荐商品',
  `sales_model` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '销售模式',
  `self_operated` bit(1) NULL DEFAULT NULL COMMENT '是否自营',
  `store_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺ID',
  `store_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺名称',
  `selling_point` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '卖点',
  `shop_category_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺分类',
  `small` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '小图路径',
  `sn` varchar(30) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品编号',
  `template_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '运费模板ID',
  `thumbnail` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '缩略图路径',
  `under_message` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '下架原因',
  `weight` decimal(10, 2) NULL DEFAULT NULL COMMENT '重量',
  `store_category_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺分类路径',
  `big` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '大图路径',
  `params` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL,
  `goods_type` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `store_id`(`store_id` ASC) USING BTREE COMMENT '店铺id索引'
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_goods_sku`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `auth_message` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '审核信息',
  `big` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '大图路径',
  `brand_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '品牌ID',
  `buy_count` int NULL DEFAULT NULL COMMENT '购买数量',
  `virtual_sales` int NULL DEFAULT 0 COMMENT '虚拟销量',
  `category_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '分类路径',
  `comment_num` int NULL DEFAULT NULL COMMENT '评价数量',
  `cost` decimal(10, 2) NULL DEFAULT NULL COMMENT '成本价格',
  `freight_payer` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '运费承担者',
  `freight_template_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '配送模版ID',
  `goods_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品ID',
  `goods_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品名称',
  `goods_unit` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '计量单位',
  `goods_video` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品视频',
  `grade` decimal(10, 2) NULL DEFAULT NULL COMMENT '商品好评率',
  `intro` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '商品详情',
  `auth_flag` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '审核状态',
  `promotion_flag` bit(1) NULL DEFAULT NULL COMMENT '是否是促销商品',
  `market_enable` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '上架状态',
  `mobile_intro` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '移动端商品详情',
  `original` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '原图路径',
  `price` decimal(10, 2) NULL DEFAULT NULL COMMENT '商品价格',
  `promotion_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '促销价',
  `quantity` int NULL DEFAULT NULL COMMENT '库存',
  `recommend` bit(1) NOT NULL COMMENT '是否为推荐商品',
  `sales_model` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '销售模式',
  `self_operated` bit(1) NULL DEFAULT NULL COMMENT '是否自营',
  `store_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺ID',
  `store_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺名称',
  `selling_point` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '卖点',
  `small` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '小图路径',
  `sn` varchar(30) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品编号',
  `specs` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '规格信息json',
  `template_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '运费模板id',
  `thumbnail` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '缩略图路径',
  `under_message` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '下架原因',
  `view_count` int NULL DEFAULT NULL COMMENT '浏览数量',
  `weight` decimal(10, 2) NULL DEFAULT NULL COMMENT '重量',
  `simple_specs` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '规格信息',
  `store_category_path` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '店铺分类路径',
  `goods_type` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL,
  `alert_quantity` int NULL DEFAULT NULL COMMENT '预警库存',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_goods_gallery`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `goods_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '商品ID',
  `is_default` int NULL DEFAULT NULL COMMENT '是否是默认图片',
  `original` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '原图路径',
  `small` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '小图路径',
  `sort` int NULL DEFAULT NULL COMMENT '排序',
  `thumbnail` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '缩略图路径',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_store_address`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '地址',
  `address_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '自提点名称',
  `center` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '经纬度',
  `mobile` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '电话',
  `store_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '店铺ID',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_region`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `ad_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '区域编码',
  `center` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '区域中心点经纬度',
  `city_code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '城市代码',
  `level` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '行政区划级别',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '名称',
  `order_num` int NULL DEFAULT NULL COMMENT '排序',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父ID',
  `path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '行政地区路径',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `parent_id`(`parent_id` ASC) USING BTREE COMMENT '父id'
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_file`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `file_key` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '存储文件名',
  `file_size` bigint NULL DEFAULT NULL COMMENT '大小',
  `file_type` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '文件类型',
  `name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '原文件名',
  `owner_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '拥有者ID',
  `url` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '路径',
  `user_enums` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '用户类型',
  `file_directory_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '文件夹ID',
  `owner_name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '拥有者名称',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_file_directory`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `delete_flag` tinyint(1) NULL DEFAULT 0 COMMENT '删除标志 true/false 删除/未删除',
  `directory_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '文件目录类型',
  `directory_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '拥有者名称',
  `owner_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '拥有者id',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父分类ID',
  `level` int NULL DEFAULT NULL COMMENT '层级',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1698937596963311619 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件夹' ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_setting`  (
  `id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `setting_value` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '配置值value',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_sensitive_words`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(6) NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(6) NULL DEFAULT NULL COMMENT '更新时间',
  `sensitive_word` varchar(20) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '敏感词名称',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_wholesale`  (
  `id` bigint NOT NULL,
  `create_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `create_time` datetime(6) NULL DEFAULT NULL,
  `delete_flag` bit(1) NULL DEFAULT NULL,
  `update_by` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `update_time` datetime(6) NULL DEFAULT NULL,
  `price` decimal(10, 2) NULL DEFAULT NULL COMMENT '价格',
  `goods_id` bigint NULL DEFAULT NULL COMMENT '商品id',
  `sku_id` bigint NULL DEFAULT NULL COMMENT '商品skuId',
  `num` int NULL DEFAULT NULL COMMENT '起购量',
  `template_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin COMMENT = '批发规则表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `li_brand`  (
  `id` bigint NOT NULL COMMENT 'ID',
  `create_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `delete_flag` bit(1) NULL DEFAULT NULL COMMENT '删除标志 true/false 删除/未删除',
  `update_by` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `logo` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '品牌图标',
  `name` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '品牌名称',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = COMPACT;

CREATE TABLE li_wechat_identity (
    id BIGINT NOT NULL,
    app_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    binding_version BIGINT NOT NULL DEFAULT 1,
    union_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    binding_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wechat_identity_app_open (app_id, open_id),
    CONSTRAINT chk_wechat_identity_version CHECK (binding_version >= 1),
    CONSTRAINT chk_wechat_identity_state CHECK (binding_state IN ('ACTIVE', 'PENDING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE li_member_identity_link (
    id BIGINT NOT NULL,
    mapping_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_member_id BIGINT NOT NULL,
    target_member_id BIGINT NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    proof_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proof_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consent_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consent_time DATETIME(6) NOT NULL,
    source_sid CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_sid CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_link_request (mapping_id, request_id),
    KEY idx_member_link_pending (mapping_id, state),
    CONSTRAINT chk_member_link_versions CHECK (before_version >= 1 AND after_version = before_version + 1),
    CONSTRAINT chk_member_link_state CHECK (state IN ('PENDING', 'DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE li_member
    ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN phone_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN phone_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE UNIQUE INDEX uk_member_mobile ON li_member (mobile);

CREATE TABLE `li_goods_search_param` (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'ID',
  `goods_id` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '商品ID，与 li_goods_sku.goods_id 同型同排序规则',
  `param_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '参数名',
  `param_value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '参数值',
  `param_sort` int NULL COMMENT '参数维度的排序，同一 (goods_id, param_name) 的各行取值相同（冗余列）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_li_goods_search_param_goods_name_value` (`goods_id`,`param_name`,`param_value`) USING BTREE,
  KEY `idx_li_goods_search_param_lookup` (`param_name`,`param_value`,`goods_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC COMMENT='商品参数倒排（搜索用）';

ALTER TABLE li_goods ADD scheduled_upper_time DATETIME NULL, ADD scheduled_upper_reason VARCHAR(255) NULL, ADD scheduled_down_time DATETIME NULL, ADD scheduled_down_reason VARCHAR(255) NULL;

ALTER TABLE li_file ADD file_category VARCHAR(32) NULL;

-- C23 raw/version4.9to5.0.sql:4, structural statement only.
CREATE TABLE li_system_log (
 id VARCHAR(64) NOT NULL PRIMARY KEY, create_time BIGINT, username VARCHAR(255), request_url VARCHAR(500),
 request_param TEXT, response_body TEXT, ip VARCHAR(64), name VARCHAR(255), request_type VARCHAR(32),
 customer_log TEXT, ip_info VARCHAR(500), cost_time INT, store_id BIGINT,
 KEY idx_li_system_log_store_id(store_id), KEY idx_li_system_log_create_time(create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
