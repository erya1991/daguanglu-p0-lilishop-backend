-- 大光路 P0 / Stage 0 / P03 最小身份结构；纯结构交付，人工审批后逐条执行。
-- 本文件于 2026-10-24 整理，不是自动 Migration，不授予数据库操作许可。
-- 目标仅为原 PREP 隔离库 daguanglu_f0103_dev，已核验 MySQL 8.4.11 / InnoDB。
-- B04-2 已于 2026-10-23 执行这四项变更；该已应用库禁止再次执行本文件。
-- 复演仅适用于另行获批且通过以下全部前置检查的相同起始结构。
--
-- 结构来源（新项目身份设计，不是从历史商城 SQL 推测的新表）：
-- 1. 已批准 Stage 0 / P01-C / P03 Entity、Mapper 与 B04-1 最小结构草案。
-- 2. B04-2 实际逐条执行记录：framework/target/b042/ddl-execution.json。
-- 3. B04-2 实际 DDL：framework/target/b042/ddl-01.sql 至 ddl-04.sql。
--    对应 SHA-256（依次为 01、02、03、04）：
--    2c523b07187dbd866c9b8d820e15b174ffb5f5470fbb69c65b12cf2f50a55ba7
--    21f7a7ef218e5751e70299a9ee5dfbe80e880e8409409e03f45a21373f9ca5d7
--    5bdbed57e8232dc920395f826f9d8e5321d19eaaf23db4a3cfbf5132cdee54fe
--    fe7c3d4639958786920ac3a376e97cce06dbe670fbe21e8ed0460b339a4e390f
-- 4. B04-2 每步 SHOW CREATE TABLE：framework/target/b042/ddl-01-actual.txt
--    至 ddl-04-actual.txt；上述 target 证据不作为源码或自动执行依赖。
--
-- 执行前提（需人工核验；本文件没有绕过检查或容错跳过机制）：
-- A. 核对批准实例、隔离网络、专用卷、实际库名、版本、最小账户权限；停止未知漂移。
-- B. 在专用私有且 Git 忽略目录形成可核验的受控备份和恢复材料；不输出敏感数据。
-- C. 确认两张新表均不存在；li_member 存在且 id 为有符号 BIGINT 主键，mobile 为
--    可空 VARCHAR(255)，username 唯一索引及既有列保持不变；三个新增列和手机号索引
--    均不存在。任何同名或不兼容结构都需停止，不按“已存在”自动跳过。
-- D. 对所有 Member（包含禁用和软删除记录）只做不返回号码/个人信息的聚合：
--    NULL 数量允许非零；空串、首尾空白及非规范号码数量必须为零；
--    按当前 mobile 列 Collation 分组的重复组数及受影响行数必须为零；
--    按已批准号码规范化口径（例如 +86 与其本地号码形式）分组也不能有冲突。
--    不规范数据或冲突只能另行审核处理，本文件不清洗、不填占位手机号、不改旧用户。
--    创建唯一索引前立即重做聚合检查，并保证检查期间没有并行资料写入。
-- E. 核对既有 Member 及必要引用的受控基线，只记录摘要/聚合，不导出个人信息到日志。
--
-- 数据约束说明：Java String ID 为应用生成的十进制 Snowflake ID，不使用自增。
-- AppID/OpenID 使用 ascii_bin 区分大小写；同一 Member 可以有多个应用映射。
-- 新 Entity 不继承 BaseEntity，时间由应用显式赋值，不附加 create_by/delete_flag。
-- Member mobile 保持可空；唯一索引允许多个 NULL，只约束存储值，不能替代号码规范化。
-- proof_reference 只保存审核引用，不能存密码、证明 bearer、微信 Code 或完整 Token。
--
-- 顺序及失败边界：按下列 1 至 4 逐条执行，每条后核对 SHOW CREATE TABLE、列、索引、
-- CHECK 的实际 ENFORCED 状态及退出码。MySQL DDL 会隐式提交，多条 DDL 不能整体 ROLLBACK。
-- 任一步失败立即停止，保留已应用结构和原始脱敏错误，不自动重试、删表或补改其他对象。
-- 已有新身份数据时应关闭受影响入口并保留数据，另行审批向前修复或受控备份恢复；
-- 不把删表、删列、回滚历史映射作为自动清理手段。
--
-- 本文件不恢复 li_connect、等级表或缺失的 12 张历史关系表，不导入任何种子或业务数据。
-- 应用本结构不自动批准 B 关联成功、真实微信、文件存储或任何 schema/history-ready 开关。

-- 1. 新微信应用身份映射。
CREATE TABLE daguanglu_f0103_dev.li_wechat_identity (
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

-- 2. 最小身份关联审计与 PENDING 恢复引用，不是通用账号合并中心。
CREATE TABLE daguanglu_f0103_dev.li_member_identity_link (
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

-- 3. 本人资料 CAS 及最近一次手机号请求引用；保留所有旧字段与资料。
ALTER TABLE daguanglu_f0103_dev.li_member
    ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN phone_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN phone_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- 4. 仅在立即重新完成上述手机号聚合检查后执行。
CREATE UNIQUE INDEX uk_member_mobile ON daguanglu_f0103_dev.li_member (mobile);
