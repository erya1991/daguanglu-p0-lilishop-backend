# 大光路 P0 Backend 研发入口

当前阶段：PREP-01 / IN_PROGRESS / NOT FROZEN。本仓是 P0 唯一商城业务后端，保持独立 Git 历史与 Maven 构建入口。

## 1. 开工必读

跨仓任务必须显式读取以下资料；兄弟仓 AGENTS 不会自动适用于本仓：

1. [总体需求大纲 V1.0](../project-ops/docs/product/source/00_大光路智慧社区_P0商城闭环_总体需求大纲_V1.0.md)：最高业务原则。
2. [正式开发总清单 V1.1](../project-ops/docs/product/current/01_大光路智慧社区_P0商城闭环_产品开发总清单_V1.1.md)：当前 APPROVED / CURRENT 产品清单。
3. 当前批准的阶段设计、PLAN、ACCEPTANCE 和测试用例；缺失时记录并停止依赖未知规则的实施。
4. [项目级 AGENTS](../project-ops/AGENTS.md) 与本 AGENTS；版本身份和 SHA 查阅[产品基线清单](../project-ops/docs/product/PRODUCT_BASELINE_MANIFEST.md)及[release-lock](../project-ops/release-lock.yml)。
5. 源码调查及现有实现。历史总清单、DRAFT 和旧 Mock 仅供追溯，不反向定义当前需求。

## 2. 固定来源与独立演进

初始源码基线为 `a8e49aed4279697cc949cfda199b4e658ab72174`；[LiLiShop 官方仓库](https://github.com/lilishop/lilishop)只作来源追溯。后续改造在项目仓独立实施，仅保留项目 origin，不配置官方 upstream，不自动拉取、同步或合并官方分支。保留原始 Git 历史、LICENSE 与作者版权；升级及安全修复须依据本项目批准的任务。

## 3. 后端业务护栏

- 保留 LiLiShop 商城内核；原生 Goods、SKU、Trade、Order 等模型及现有行为不自动等于 P0 已批准业务规则，先核对差异和接口契约。
- 关键交易、退款和分账金额使用整数分，展示元；服务端复核价格、库存及资格，明确原生金额字段的转换与精度，不借规范同步批量改表。
- 服务端在每个关键操作校验 Member、Store、身份、数据归属、权限、业务状态及前置条件；前端参数与权限隐藏不能扩大授权。
- 支付、履约、售后、退款、分账和回退独立管理，保留订单与资金历史快照；StoreFlow 不替代渠道最终事实，同意售后或渠道受理不等于退款成功。
- 关键写操作考虑幂等、并发、重复及乱序通知；UNKNOWN 先查询真实事实再决定合法重试。详细规则服从正式产品与当前阶段方案。

## 4. 工程保护与执行边界

先核对根目录、分支、HEAD、origin、工作区及暂存区；未知变更原样保护，不自动 reset、clean、stash 或切换分支。只读 Git 使用 `git --no-optional-locks`（等效禁用可选锁），必要时本次命令关闭 fsmonitor，不改持久 Git 配置。

安装依赖、构建、启动服务、数据库初始化/Migration、生产配置、正式渠道调用、Git 提交/推送/远端操作及部署须当前任务明确授权；未授权不执行。凭据与真实个人信息不进入文档、示例或日志。源码、构建、运行、人工和真实渠道证据分别登记；产品批准不等于运行兼容或商业许可通过，F01-08 仍 BLOCKED。任务完成停在批准批次，不自行进入 PREP-02 或 Stage 0。
