# 公司 Linux 开发测试环境部署说明

本版本用于“住户端、商户端真实微信身份 + 商城 Mock 资金”。MySQL、Redis、文件和任务使用独立运行编号；支付、退款、分账、回退均不接真实资金。原有阶段环境保持原有准入规则，公司测试模式使用本次构建的自动验证结果准入。

技术验证范围和外部待验条件见 [VALIDATION.md](VALIDATION.md)。公司服务器与真实微信配置不由本次本机隔离测试代验。

## 1. 部署前条件

- Linux；Docker Engine / Compose；Node 22；JDK 21；Maven 3；Manager 使用 Yarn 1 的现有锁文件，双小程序使用 npm 锁文件。
- 允许访问软件和镜像源。后端必须能访问微信身份接口 `api.weixin.qq.com`，这与手机能否访问公司服务器是两个独立条件。
- 两个独立小程序的 AppID、AppSecret，以及有权限的微信开发／测试人员。AppSecret 只放服务器私有配置。
- 手机和微信开发者工具能够访问的 HTTPS 入口、相应证书和微信平台配置。公司内网机器不必仅因本方案开放公网，但能否使用选定的内网地址完成真机联调须实际验证。开发工具连接成功不能替代真机通过。
- 不准备真实支付商户私钥、真实退款或分账凭据。原生设置页面中的支付配置入口不代表本环境允许真实动款。

公司服务器先做无凭据的出站检查，再由手机检查入站访问；两者不能互相替代。下面的命令只检查 DNS／TLS／HTTP 连通性，不携带 AppSecret，HTTP 状态码本身也不代表微信登录成功。

```sh
curl --connect-timeout 5 --max-time 10 --silent --show-error \
  --output /dev/null --write-out 'wechat_http=%{http_code}\n' https://api.weixin.qq.com
```

平台配置参考 [微信小程序网络说明](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html) 和 [登录凭证校验](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)。内网调试与正式发布的网络条件分别核验；不要把关闭证书或域名检查作为本版本的默认构建设置。

## 2. 代码与构建

研发只需三个应用仓；项目级个人工作资料不构成部署依赖。分别克隆各仓 `uat` 分支，保留独立 Git 历史。部署时记录三仓完整提交和构建摘要。

```sh
# 示例目录；源代码、构建包和运行数据分开
mkdir -p /srv/dgl-uat/src /srv/dgl-builds /srv/dgl-web
cd /srv/dgl-uat/src
git clone -b uat https://github.com/erya1991/daguanglu-p0-lilishop-backend.git lilishop-backend
git clone -b uat https://github.com/erya1991/daguanglu-p0-lilishop-ui.git lilishop-ui
git clone -b uat https://github.com/erya1991/Smart-Community-miniapp.git smart-community-miniapp

BACKEND=/srv/dgl-uat/src/lilishop-backend
TOOLS=$BACKEND/deployment/uat-schema/tools

# 输出目录必须是新的目录，且位于后端源码仓之外
node "$TOOLS/build-test-server.mjs" --output /srv/dgl-builds/backend-001

cd /srv/dgl-uat/src/lilishop-ui/manager
yarn install --frozen-lockfile --non-interactive --ignore-engines
npm run build:wechat-test -- --output /srv/dgl-web/manager-001
```

后端构建脚本显式运行自动准入所需测试，校验构建前后源码摘要，打包 API、报告及 `build.json`。失败、跳过、缺少报告或构建期间源码变化均不能生成有效准入包。

双小程序的构建方式见该仓 `docs/wechat-test-build.md`。两个 AppID 分别传入，API 地址指向同一测试服务的 `https://测试入口/api`；AppSecret 不参与前端构建。

## 3. 新运行目录与数据库

先选定不会覆盖现有环境的绝对根目录、运行编号和空闲端口。不要结束占用端口的未知进程。

```sh
export P0_TEST_ROOT=/srv/dgl-uat
export P0_TEST_RUN_ID=UAT_20261113000000000  # 每次新环境换成实际唯一编号
export P0_RUN_ID=$P0_TEST_RUN_ID
export P0_RUN_DIR=$P0_TEST_ROOT/.uat-runtime/$P0_TEST_RUN_ID
export P0_MYSQL_PORT=19706
export P0_REDIS_PORT=19779
export P0_PROJECT=dgl-company-test-001

node "$TOOLS/prepare-test-run.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
docker compose -p "$P0_PROJECT" -f "$BACKEND/deployment/uat-schema/compose.test.yml" up -d
node "$TOOLS/register-test-resources.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID" --project "$P0_PROJECT"
```

以上只启动数据库和缓存。目录准备拒绝覆盖现有运行编号；登记检查容器、卷、回环端口、运行标识和实际 MySQL UUID，并首次创建业务库、Mock 账本库及专用账号。部分初始化失败会保留状态供排查，不会靠重复执行重置数据库。

数据库迁移固定为 50 项：前 49 项先执行，住户菜单在原生菜单根节点建立后执行。历史 SQL 保留原字节，执行工具检查清单与每项摘要。已完成同摘要迁移跳过，未知历史、摘要变化和未完成迁移会停止。

数据库命令通过本次登记的 MySQL 容器执行，不要求开放 root 的远程登录。使用随代码提供的 owned-mysql 包装入口完成下列迁移与初始化。

```sh
chmod +x "$TOOLS/owned-mysql.sh"
export P0_MYSQL_COMMAND=$TOOLS/owned-mysql.sh
sh "$TOOLS/migrate-mock-schema.sh" \
  --defaults-file "$P0_RUN_DIR/private/mysql-root.cnf" \
  --expected-server-uuid "$(node -p "JSON.parse(require('fs').readFileSync(process.env.P0_RUN_DIR+'/runtime/resources.json')).mysqlUuid")" \
  --business-db dgl_p0_uat --mock-db dgl_p0_uat_mock --confirm-test-mock

export P0_MYSQL_DEFAULTS=$P0_RUN_DIR/private/mysql-root.cnf
export P0_UI_ROOT=/srv/dgl-uat/src/lilishop-ui
export P0_BCRYPT_CLASSPATH="$HOME/.m2/repository/org/springframework/security/spring-security-crypto/7.0.4/spring-security-crypto-7.0.4.jar:$HOME/.m2/repository/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6.jar"
node "$TOOLS/bootstrap-test-manager.cjs"
node "$TOOLS/install-file-codecs.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
```

首次初始化准备主管理员、备用管理员、分层菜单和本地验证码素材。账号和随机密码仅位于 `private/manager-accounts.json`。重跑不会重置密码、恢复人工撤销的授权或覆盖负责人新增资料。此步骤不创建微信身份、不生成已付款订单。

迁移与引导脚本需要的 `P0_UI_ROOT`、`P0_MYSQL_COMMAND`、`P0_MYSQL_DEFAULTS` 应保留在当前部署终端。使用不同 Maven 本地仓库时，把 `P0_BCRYPT_CLASSPATH` 中的路径改为该仓库的实际位置。

## 4. 微信配置、安装与启动

```sh
cp "$BACKEND/deployment/uat-schema/wechat.example.json" "$P0_RUN_DIR/private/wechat.json"
chmod 600 "$P0_RUN_DIR/private/wechat.json"
# 由配置人员在本机编辑文件；不要把 AppSecret 发到聊天或写进 Git。
# 填写两端 AppID、AppSecret、publicOrigin 和空闲 API 端口。

node "$TOOLS/install-test-build.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID" --bundle /srv/dgl-builds/backend-001
node "$TOOLS/configure-test-server.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
node "$TOOLS/api-runtime.mjs" start --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
node "$TOOLS/api-runtime.mjs" status --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
```

API 固定绑定回环地址。配置开启双端真实微信，关闭本机测试密码登录；动态交易范围仅针对本次新建且通过服务端微信认证的住户。商户仍须通过申请、审核及经营配置取得相应店铺能力。

API 启停工具校验本次 PID、命令、产物和配置摘要。启动失败时保留日志及本次进程登记；先检查 `logs/`，使用同一 stop 命令收敛，不能杀未知进程。

## 5. HTTPS 与 Manager

把公司适用的证书与私钥分别准备到 `private/tls/cert.pem`、`private/tls/key.pem`，私钥保持 600 权限。由公司运维按实际域名、证书和防火墙策略配置入口。

```sh
node "$TOOLS/write-nginx-config.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID" --manager-root /srv/dgl-web/manager-001
# 将 runtime/nginx-site.conf 安装到公司 Nginx 配置目录
# 检查该 server block 与已有监听、域名没有冲突
sudo nginx -t
# 仅在检查通过后由运维 reload Nginx
```

Nginx 工作用户需能读取 Manager 构建目录和确需公开的附件目录；`private/` 保持私有。生成配置只代理本次 API 的 manager、store、buyer、common 前缀，不公开 actuator 或私有目录。

Manager 从 `publicOrigin` 打开；双小程序使用 `publicOrigin + /api`。三端必须核对同一运行编号和当前版本，不能混用旧 Stage 地址。

## 6. 身份与首轮验证

- 后台账号用于物业／平台管理人员，使用正常密码与验证码登录。初始管理员可在“原生设置 → 角色管理／后台账号”自行创建角色和普通账号。
- 住户首次真实微信登录创建原生 Member 与该小程序的微信绑定。后台“用户管理 → 住户账号”查看和启停；该身份并不自动表示房屋或业主资格已认证。
- 商户端微信登录先建立申请身份，审核通过后由当前 Member、Clerk、Store 关系取得店铺能力。负责人通过正常邀请确认工作人员，后台从商户档案查看关系。
- 双端 AppID 独立；不依据相同昵称、手机号或 unionId 静默合并两个账号。现有受控身份关联机制按其契约使用。
- 新微信身份、停用后会话失效、跨住户／跨店拒绝均需用实际公司环境验证。
- Mock 资金配置使用 Mock 渠道标识。页面显示模拟支付，业务结果由后端正式业务服务和独立账本确认，不调用微信真实收款。
- 自动构建验证通过只代表技术准入。真实微信、真机、负责人验收及真实资金结论分别记录。

第一轮建议按顺序保留证据：管理员正常登录及原生自建角色／普通账号 → 住户真实微信首次、重复、退出和停用登录 → 商户申请、后台审核、重新进入店铺与工作人员邀请 → 商品审核上架、住户下单及 Mock 支付 → 四种履约、售后、分账回退与对账。每条主线使用页面新建的数据；未执行的步骤单独记录，不用数据库探针或合成身份代替。

## 7. 启停与升级

```sh
# 应用（两个 API，以及本次 Manager 进程内的 P0 worker）
node "$TOOLS/api-runtime.mjs" stop --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
node "$TOOLS/api-runtime.mjs" start --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"

# 仅数据库和缓存；不会启动或停止 API / Nginx
docker compose -p "$P0_PROJECT" -f "$BACKEND/deployment/uat-schema/compose.test.yml" stop
docker compose -p "$P0_PROJECT" -f "$BACKEND/deployment/uat-schema/compose.test.yml" start
```

升级前先停止本次 API、备份本次业务库和 Mock 账本及附件／私有配置，再安装新验证构建、重新配置并启动。不得使用 `down -v` 作为日常停止命令，也不清全局 Redis。旧版本和报告留在各自独立版本目录中。

备份前先停止本次 API，再执行：

```sh
node "$TOOLS/backup-test-server.mjs" --root "$P0_TEST_ROOT" --run-id "$P0_TEST_RUN_ID"
```

备份位于本 run 的 `backups/<时间>/`，包含同一事务快照中的业务库、Mock 库，以及附件、私有配置和版本引用。备份也包含凭据，目录需保持私有，不进入 Git 或普通共享文档。Redis 会话不作为可恢复登录凭据保存；重建缓存后重新登录。

恢复演练必须另建独立 MySQL 容器和新卷，先核对目标 UUID 与备份源 UUID 不同，并确认目标没有这两个业务库，再导入 `business-and-mock.sql`。比较迁移台账、关键账号及业务／Mock 账本数据。附件和私有配置只用于匹配核验，不能将源资源登记、PID 或旧会话复制成新环境的运行身份。应用恢复需保留匹配的源码和构建包。

本次已验证备份工具能在独立恢复目标保留两个库的模拟探针数据；这不是实际资金业务恢复或真实渠道验收。既有数据库升级、历史 Mock 账号与真实微信身份关联、公司数据迁移必须先备份和核对，不执行空库管理员引导来覆盖已有人员配置。
