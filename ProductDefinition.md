# QSL 卡片管理系统产品定义

更新时间：2026-05-02
代码核验范围：`qsl-plugin` 后端、控制台前端、RBAC 模板与 `tools/CardPrint` 在线打印桥接
Halo 官方资料核验日期：2026-05-02

## 1. 产品一句话定义

面向业余无线电场景的 QSO/SWL、线上换卡、线下换卡与收卡闭环管理产品，覆盖“记录、制卡、发卡、送达确认、收卡编号、查询、申请、审计、统计、打印桥接”的可持久化业务流，并提供公开查询、换卡与签收页面。

## 2. 菜单组织

零级菜单分组：`QSL管理`（左侧菜单分组名，不是可点击功能页面）。

入口路由：`/qsl`，当前代码会重定向到第一个可用模块。

当前一级菜单按 `qsl-plugin/ui/src/index.ts` 与 `qsl-plugin/ui/src/constants/menu-modules.ts` 生效：

1. 总览
2. 配置
3. 通联业务
4. 线上换卡业务
5. 线下换卡业务
6. 收卡业务
7. 审计
8. 数据

### 2.0.1 页面展示约定

1. 后台功能页不展示“前端权限定义”卡片与“页面状态”卡片。
2. 权限定义、依赖关系与路由映射以本文件和 `qsl-menu-role-templates.yaml` 为准。
3. 页面状态与实现进度通过输出性文档记录，不在页面内重复展示。
4. 支持详情展开的表格页面统一采用整行点击展开；行内按钮需阻止冒泡。
5. 同一业务组件可按 `sceneTypes/defaultSceneType/defaultCardType` 复用到不同菜单场景。

### 2.0.2 菜单与路由映射

| 一级菜单 | 二级菜单 | 控制台路由 | 权限节点 |
| --- | --- | --- | --- |
| 总览 | 总览看板 | `/qsl/overview/overview-dashboard` | `overview-dashboard` |
| 配置 | 系统参数 | `/qsl/settings/system-settings` | `system-settings` |
| 配置 | 通信地址 | `/qsl/settings/station-profile` | `station-profile` |
| 配置 | 本台设备 | `/qsl/settings/station-equipment` | `station-profile` |
| 配置 | 本台卡片 | `/qsl/settings/station-card` | `station-profile` |
| 通联业务 | 通联日志 | `/qsl/traffic-business/qso-record` | `qso-record` |
| 通联业务 | 创建卡片 | `/qsl/traffic-business/card-record` | `card-record` |
| 通联业务 | 制卡签发 | `/qsl/traffic-business/card-issue` | `card-issue` |
| 通联业务 | 发信确认 | `/qsl/traffic-business/mail-send-confirm` | `mail-send-confirm` |
| 通联业务 | 送达确认 | `/qsl/traffic-business/mail-receive-confirm` | `mail-receive-confirm` |
| 线上换卡业务 | 换卡申请审核 | `/qsl/online-exchange-business/online-exchange-request-review` | `exchange-request-review` |
| 线上换卡业务 | 创建卡片 | `/qsl/online-exchange-business/online-card-record` | `card-record` |
| 线上换卡业务 | 制卡签发 | `/qsl/online-exchange-business/online-card-issue` | `card-issue` |
| 线上换卡业务 | 发信确认 | `/qsl/online-exchange-business/online-mail-send-confirm` | `mail-send-confirm` |
| 线上换卡业务 | 送达确认 | `/qsl/online-exchange-business/online-delivery-confirm` | `mail-receive-confirm` |
| 线下换卡业务 | 创建活动 | `/qsl/offline-exchange-business/offline-activity` | `exchange-request-review` |
| 线下换卡业务 | 创建卡片 | `/qsl/offline-exchange-business/offline-card-record` | `card-record` |
| 线下换卡业务 | 制卡签发 | `/qsl/offline-exchange-business/offline-card-issue` | `card-issue` |
| 线下换卡业务 | 送达确认 | `/qsl/offline-exchange-business/offline-delivery-confirm` | `mail-receive-confirm` |
| 收卡业务 | 通联收卡 | `/qsl/receive-business/receive-qso` | `mail-receive-confirm` |
| 收卡业务 | 线上换卡收卡 | `/qsl/receive-business/receive-online-eyeball` | `mail-receive-confirm` |
| 收卡业务 | 线下换卡收卡 | `/qsl/receive-business/receive-eyeball` | `mail-receive-confirm` |
| 审计 | 通联记录查询 | `/qsl/audit/qso-query` | `qso-query` |
| 审计 | 卡片记录查询 | `/qsl/audit/card-query` | `card-query` |
| 审计 | 收卡记录查询 | `/qsl/audit/receive-record-query` | `card-query` |
| 审计 | 统计报表 | `/qsl/audit/report-auditlog` | `report-auditlog` |
| 审计 | 审计日志 | `/qsl/audit/audit-log` | `report-auditlog` |
| 数据 | 卡片异动 | `/qsl/data/card-mutation` | `card-mutation` |
| 数据 | 地址管理 | `/qsl/data/address-management` | `address-bureau` |
| 数据 | 卡片局管理 | `/qsl/data/bureau-management` | `address-bureau` |
| 数据 | 设备库维护 | `/qsl/data/equipment-catalog` | `equipment-catalog` |
| 数据 | 导入导出 | `/qsl/data/import-export` | `import-export` |

权限节点实际 UI 权限名统一为 `plugin:qsl-management:<权限节点>:view` 与 `plugin:qsl-management:<权限节点>:edit`。

## 3. 业务场景

### 3.1 场景主字段

当前持久化模型使用 `sceneType` 区分业务场景：

1. `QSO`：通联业务 QSO。
2. `SWL`：通联业务 SWL。
3. `ONLINE_EYEBALL`：线上换卡。
4. `EYEBALL`：线下换卡。

`QsoRecord.spec.sceneType`、`CardRecord.spec.sceneType`、`ExchangeRequest.spec.sceneType` 已按当前代码落地。

### 3.2 总览

总览看板通过控制台接口聚合统计，覆盖 QSO、卡片、待发、已发、签收、已收等指标。公开侧也提供匿名总览读取接口。

### 3.3 配置

系统参数当前包括：

1. `guestQueryPerMinute`：匿名公开接口每分钟限流阈值。
2. `requiresExchangeReview`：线上换卡申请是否需要审核。
3. `autoNotifyOnCardCreated`、`autoNotifyOnCardSent`、`autoNotifyOnCardReceived`：制卡、发卡、收卡节点邮件自动通知开关。
4. `cardRecordSequence`：卡片编号序列。
5. `receiveRecordSequence`：收卡编号序列。

通信地址、本台设备、本台卡片分别落在 `StationProfile`、`StationEquipment`、`StationCard` 扩展资源中。

### 3.4 通联业务

通联业务包含通联日志、创建卡片、制卡签发、发信确认、送达确认。

1. 通联日志支持 `QSO/SWL` 场景切换，字段落在 `QsoRecord.spec`。
2. 创建卡片必须关联 QSO/SWL 记录，候选列表排除已写入 `CardRecord.spec.qsoRecordName` 的记录；若选择“不创建卡片”，写入不占用 `C{序号}` 的占位 `CardRecord`，该记录后续不再出现在候选列表中。卡片记录清单支持编辑与删除；编辑占位记录并保存时会重新分配正式卡片编号。
3. 制卡签发只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，不显示“不创建卡片”的无编号占位记录；确认制卡更新 `CardRecord.spec.cardIssued/cardIssuedAt`，信封打印或打包状态使用 `envelopePrinted`。
4. 发信确认只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，调用控制台接口更新 `cardSent/sentAt`，并可触发发卡邮件通知。
5. 送达确认只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，调用控制台接口按 `callSign + cardType + sceneType` 匹配卡片；未匹配时按业务规则自动补建记录。

### 3.5 线上换卡业务

线上换卡业务包含换卡申请审核、创建卡片、制卡签发、发信确认、送达确认。

1. 前台匿名提交线上换卡申请写入 `ExchangeRequest`，`sceneType=ONLINE_EYEBALL`；公开表单先填写呼号，再从本台卡片版本列表选择最多两张卡片，页面按 `StationCard.spec.sortOrder` 展示卡片图案、版本号、版本总量和库存余量，随后选择“个人地址”或“卡片局地址”，地址类型默认不选。
2. 个人地址模式填写姓名、电话、邮编、通信地址，电子邮箱可选；卡片局地址模式从 `BureauEntry` 候选选择已有卡片局，或填写新的卡片局名称、邮编、地址。新的卡片局仅随本次申请保存，不由匿名接口写入卡片局管理主数据。
3. 后台审核通过后自动创建 `ONLINE_EYEBALL` 场景卡片，并把 `ExchangeRequest.spec.cardVersion` 写入新建 `CardRecord.spec.cardVersion`；同时根据申请中的个人地址或卡片局地址复用/创建地址资源，并写入 `CardRecord.spec.addressEntryName`。
4. 换卡申请审核在同意或拒绝后显示“发送邮件通知”和“修改”操作；邮件通知面向 `ExchangeRequest.spec.email`，无邮箱时按跳过处理；修改操作可调整申请信息与审核状态，并可删除本条换卡申请记录。
5. 后续流程复用创建卡片、制卡签发、发信确认、送达确认组件。

### 3.6 线下换卡业务

线下换卡业务包含创建活动、创建卡片、制卡签发、送达确认。

1. 创建活动写入 `OfflineActivity`。
2. 线下创建卡片可关联 `offlineActivityName`，允许先生成待填写呼号的活动卡片。
3. 线下送达确认允许补录呼号并触发对应状态变化。
4. 线下换卡公开页面不在 HTML 中直接暴露本站通信地址、收件人、电话或邮箱；匿名提交校验通过并成功写入后，提交接口才返回本站通信地址用于寄送提示。

### 3.7 收卡业务

收卡业务包含通联收卡、线上换卡收卡、线下换卡收卡。

1. 三类收卡菜单复用送达确认组件，以 `sceneType` 限定业务范围。
2. 收卡编号写入 `CardRecord.spec.receivedRecordCodes`，允许同一卡片关联多个收卡编号。
3. 收卡业务提供基本确认、已收卡片、批量编辑与单条编辑能力；修改收卡日期时会同步重新赋予收卡编号。

### 3.8 审计与数据

审计包含通联记录查询、卡片记录查询、收卡记录查询、统计报表、审计日志。写操作通过 `QslAuditLog` 追加记录。

数据包含卡片异动、地址管理、卡片局管理、设备库维护、导入导出。卡片异动提供卡片记录修正能力，权限节点为 `card-mutation`。导入导出当前由服务端执行预检、导入、导出任务，并持久化 `ImportExportJob`。

## 4. 前台公开能力

公开接口 API Group：`api.qsl-management.halo.run/v1alpha1`。

当前公开页面与短码：

| 能力 | 公开页面 | 短码 |
| --- | --- | --- |
| 公开查询 | `/apis/api.qsl-management.halo.run/v1alpha1/cards/page` | `[qsl-card]` |
| 线上换卡申请 | `/apis/api.qsl-management.halo.run/v1alpha1/ONLINE_EYEBALL`、`/apis/api.qsl-management.halo.run/v1alpha1/ONLINE_EYEBALL/{cardId}` | `[qsl-online-exchange-card]` |
| 线下换卡确认 | `/apis/api.qsl-management.halo.run/v1alpha1/EYEBALL`、`/apis/api.qsl-management.halo.run/v1alpha1/EYEBALL/{cardId}` | `[qsl-offline-exchange-card]` |
| 公开签收 | `/apis/api.qsl-management.halo.run/v1alpha1/receipt-public`、`/apis/api.qsl-management.halo.run/v1alpha1/receipt-public/{cardId}` | `[qsl-receipt-card]` |

当前公开数据接口：

1. `GET /qso-public/records`
2. `GET /overview-public/summary`
3. `GET /exchange-online/-/bureaus`
4. `GET /exchange-online/-/station-cards`
5. `GET /exchange-offline/-/activities`
6. `POST /exchange-online/-/requests`
7. `POST /exchange-offline/-/confirm`
8. `POST /receipt-public/-/confirm`

公开接口允许匿名访问，但必须通过输入校验与限流。限流维度为 `endpoint + clientIp`，阈值来自 `SystemSetting.spec.guestQueryPerMinute`，未配置时使用默认值 `30`。

## 5. 角色模型与权限节点

### 5.1 角色模型

1. 游客：仅可访问聚合到 `anonymous` 的公开页面与公开接口。
2. HAM 用户、操作员：当前仅保留业务规划，不作为一期差异化授权落地。
3. 管理员/超级管理员：当前后台能力的主要使用对象。

### 5.2 权限设计原则

1. 后台每个业务能力至少包含 `view` 与 `edit`。
2. 服务端 RBAC 为安全边界，前端权限隐藏仅作为用户体验。
3. 多菜单可复用同一权限节点，例如 `station-profile`、`card-record`、`mail-receive-confirm`。
4. `edit` 默认依赖同项或相关 `view` 权限。

### 5.3 当前权限节点

| 权限节点 | 复用菜单或能力 | 主要依赖 |
| --- | --- | --- |
| `overview-dashboard` | 总览看板 | 通联、卡片、发信、送达读取 |
| `system-settings` | 系统参数 | 无 |
| `station-profile` | 通信地址、本台设备、本台卡片 | 设备库读取 |
| `qso-record` | 通联日志 | 设备库、本台配置读取 |
| `card-record` | 各场景创建卡片 | 通联、本台配置读取 |
| `card-issue` | 各场景制卡签发 | 卡片、地址读取 |
| `mail-send-confirm` | 发信确认 | 卡片读取 |
| `mail-receive-confirm` | 送达确认、三类收卡 | 卡片、通联读取 |
| `exchange-request-review` | 线上审核、线下活动 | 地址/卡片读取，编辑依赖卡片写入 |
| `card-mutation` | 卡片异动 | 卡片写入、通联与地址读取 |
| `qso-query` | 通联记录查询 | 通联读取 |
| `card-query` | 卡片记录查询、收卡记录查询 | 卡片读取 |
| `report-auditlog` | 统计报表、审计日志 | 查询与业务读取 |
| `address-bureau` | 地址管理、卡片局管理 | 无 |
| `equipment-catalog` | 设备库维护 | 无 |
| `import-export` | 导入导出 | 查询、审核、地址、设备读取；编辑依赖相关写权限 |

RBAC 模板实际落点：`qsl-plugin/src/main/resources/extensions/qsl-menu-role-templates.yaml`。

## 6. 本地打印工具

`tools/CardPrint` 当前作为独立 Python 工具存在，入口为：

```powershell
python -m cardprint.cli ui online
```

当前在线桥接代码事实：

1. 默认地址为 `http://localhost:8090`，但配置中可传入其他 `base_url`。
2. 读取 `card-records` 生成卡片/信封队列。
3. 信封打印会额外读取 `station-profiles`、`address-book-entries`、`bureau-entries` 做本台地址和收件地址补全。
4. 卡片版本以 `station-cards` 为准。
5. 打印状态通过人工确认回写 `CardRecord.spec.cardIssued/cardIssuedAt` 或 `envelopePrinted`。

## 7. 当前一致性说明

1. 本文件已按 2026-05-02 代码事实同步。
2. 若本文件与 `qsl-plugin` 代码不一致，以代码事实为准，并在同次改动中更新本文档。
3. 若后续新增 API、权限节点、模型字段、菜单或打印桥接契约，必须同步更新本文件、`BackendApiContract.md` 与 `docs/spec/项目信息结构化清单.md`。
