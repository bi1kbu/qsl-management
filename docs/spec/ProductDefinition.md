# QSL 卡片管理系统产品定义

更新时间：2026-05-10
代码核验范围：`插件工程根目录` 后端、控制台前端、RBAC 模板与 `tools/CardPrint` 在线打印桥接
Halo 官方资料核验日期：2026-05-10

## 1. 产品一句话定义

面向业余无线电场景的 QSO/SWL、线上换卡、线下换卡与收卡闭环管理产品，覆盖“记录、制卡、发卡、送达确认、收卡编号、查询、申请、审计、统计、打印桥接”的可持久化业务流，并提供公开查询、换卡与签收页面。

## 2. 菜单组织

零级菜单分组：`QSL管理`（左侧菜单分组名，不是可点击功能页面）。

入口路由：`/qsl`，当前代码会重定向到第一个可用模块。

当前一级菜单按 `ui/src/index.ts` 与 `ui/src/constants/menu-modules.ts` 生效：

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
| 线上换卡业务 | 导入BH6SYX卡片广场数据 | `/qsl/online-exchange-business/online-bh6syx-import` | `online-bh6syx-import` |
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
3. `qsoAutoNotifyOnCardCreated/qsoAutoNotifyOnCardSent/qsoAutoNotifyOnCardReceived`、`onlineAutoNotifyOnCardCreated/onlineAutoNotifyOnCardSent/onlineAutoNotifyOnCardReceived/onlineAutoNotifyOnExchangeReviewed`、`offlineAutoNotifyOnCardReceived`：按通联、线上换卡、线下换卡拆分的邮件自动通知开关。旧的 `autoNotifyOnCardCreated/autoNotifyOnCardSent/autoNotifyOnCardReceived/autoNotifyOnExchangeReviewed` 仅作为已有配置读取兜底。邮件通知策略支持向 `StationProfile.spec.myEmail` 发送测试邮件，测试数据中对方呼号等字段临时使用本台资料，卡片类型固定为 `EYEBALL`，卡片编号固定为 `C0001`。通知备注来源为：制卡/发卡使用卡片备注，收卡回执使用签收备注，线上换卡审核使用审核说明。
4. `cardRecordSequence`：卡片编号序列。
5. `receiveRecordSequence`：收卡编号序列。

通信地址、本台设备、本台卡片分别落在 `StationProfile`、`StationEquipment`、`StationCard` 扩展资源中。本台卡片图案不直接写入 `StationCard`，必须通过后台图片选择入口打开 Halo 附件库弹窗，在弹窗内上传或选择图片后写入附件引用；`StationCard` 仅保存附件名称、访问地址与缩略图地址等引用信息；旧版 `imageUrl` base64 字段不再兼容，后台不再提供旧图片字段清理入口。备份导入导出必须覆盖上述配置资源，`all` 导出需同时包含业务数据与配置数据。

### 3.4 通联业务

通联业务包含通联日志、创建卡片、制卡签发、发信确认、送达确认。

1. 通联日志支持 `QSO/SWL` 场景切换，字段落在 `QsoRecord.spec`。
2. 创建卡片必须关联 QSO/SWL 记录，候选列表排除已写入 `CardRecord.spec.qsoRecordName` 的记录；若选择“不创建卡片”，写入不占用 `C{序号}` 的占位 `CardRecord`，该记录后续不再出现在候选列表中。卡片记录清单支持编辑与删除；编辑占位记录并保存时会重新分配正式卡片编号。
3. 制卡签发只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，不显示“不创建卡片”的无编号占位记录；确认制卡更新 `CardRecord.spec.cardIssued/cardIssuedAt`，信封打印或打包状态使用 `envelopePrinted`。
4. 发信确认只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，调用控制台接口更新 `cardSent/sentAt`，并可触发发卡邮件通知；通联业务只要 `cardSent=true`，系统会联动补齐 `cardIssued/cardIssuedAt/envelopePrinted`。
5. 送达确认只处理正式卡片编号为 `C{序号}` 的 `CardRecord`，调用控制台接口按 `callSign + cardType + sceneType` 匹配卡片；未匹配时按业务规则自动补建记录。

### 3.5 线上换卡业务

线上换卡业务包含换卡申请审核、导入BH6SYX卡片广场数据、创建卡片、制卡签发、发信确认、送达确认。

1. 前台匿名提交线上换卡申请写入 `ExchangeRequest`，`sceneType=ONLINE_EYEBALL`；公开表单先填写呼号，再从本台卡片版本列表选择最多两张卡片，页面按 `StationCard.spec.sortOrder` 展示卡片预览缩略图、版本号、版本总量和库存余量，公开卡片版本列表使用短缓存并在 Extension 查询短暂失败时回退到上一次成功结果，随后选择“个人地址”或“卡片局地址”，地址类型默认不选。
2. 个人地址模式填写姓名、电话、邮编、通信地址，电子邮箱可选；卡片局地址模式从 `BureauEntry` 候选选择已有卡片局，或填写新的卡片局名称、邮编、地址。新的卡片局仅随本次申请保存，不由匿名接口写入卡片局管理主数据。
3. 同一呼号存在 `ONLINE_EYEBALL` 待审核换卡申请时，公开提交接口拒绝再次提交；待后台审核通过或审核拒绝后，才允许该呼号再次提交。当 `SystemSetting.spec.requiresExchangeReview=false` 时，公开提交成功后立即执行系统自动审批，审核说明固定为“系统自动审批通过”。
4. 线上换卡提交校验通过并成功写入 `ExchangeRequest` 后，提交接口返回本站通信地址，前台在成功提示中展示寄送信息。
5. 后台审核通过后自动创建 `ONLINE_EYEBALL` 场景卡片，并把 `ExchangeRequest.spec.cardVersion` 写入新建 `CardRecord.spec.cardVersion`；同时根据申请中的个人地址或卡片局地址复用/创建地址资源，并写入 `CardRecord.spec.addressEntryName`。审核通过只代表申请通过与我方待发卡，不代表我方已收到对方卡片，新建卡片的 `cardReceived=false`。
6. 换卡申请审核在同意或拒绝后显示“发送邮件通知”和“修改”操作；操作区提供“审核说明”按钮，审核前后均可编辑说明。手动同意时审核说明为空则保持为空，已有说明时以人工填写内容为准；手动拒绝时说明为空则默认“审批拒绝”。当 `SystemSetting.spec.onlineAutoNotifyOnExchangeReviewed=true` 时，审核同意或拒绝后自动发送审核结果邮件。邮件通知面向 `ExchangeRequest.spec.email`，发送结果写入 `ExchangeRequest.status.reviewMailStatus/reviewMailSentAt/reviewMailLastError/reviewMailTargetEmail`；状态为 `SENT` 时，服务端跳过重复发送，后台按钮禁用。无邮箱时按跳过处理；修改操作可调整申请信息与审核状态，并可删除本条换卡申请记录。
7. 导入BH6SYX卡片广场数据支持上传 `.xls/.xlsx` 文件，自动识别表头行，按“状态”仅保留 `对方已寄出，待我签收` 与 `待双方寄出`，字段缺失时留空。页面可批量设置默认卡片版本，也可逐行选择卡片版本；导入提交到服务端后直接创建 `ONLINE_EYEBALL` 场景 `CardRecord`，并按收件人、电话、地址、邮编和邮箱复用或创建 `AddressBookEntry`。该导入不创建 `ExchangeRequest`；BH6SYX 表格中的“交换ID”与“对方备注”仅用于源表识别或导入清单预览，不写入数据，后续流程统一围绕本站卡片编号处理。
8. 后续流程复用创建卡片、制卡签发、发信确认、送达确认组件。

### 3.6 线下换卡业务

线下换卡业务包含创建活动、创建卡片、制卡签发、送达确认。

1. 创建活动写入 `OfflineActivity`。
2. 线下创建卡片可关联 `offlineActivityName`，允许先生成待填写呼号的活动卡片。
3. 线下送达确认允许补录呼号并触发对应状态变化；前台线下换卡确认提交成功后同步写入 `cardSent=true`，使线下换卡业务送达确认中的发卡状态显示为“是”。线下换卡业务收到对方“已签收”动作时，如果尚未发卡，会联动补齐 `cardSent/sentAt`。线下换卡收卡基本功能必须选择收卡归属活动，该选择同时联动过滤清单；服务端按呼号、卡片类型、场景和关联活动共同匹配待收卡记录。若因呼号暂未匹配旧卡片而自动创建临时收卡卡片，必须记录当前关联活动；当前台线下换卡确认激活旧卡片编号时，仅在呼号与关联活动同时匹配时，把临时卡片上的收卡编号原样迁移到旧卡片，并删除临时卡片。
4. 线下换卡公开页面不在 HTML 中直接暴露本站通信地址、收件人、电话或邮箱；匿名提交校验通过并成功写入后，提交接口才返回本站通信地址用于寄送提示。前台线下换卡确认备注写入 `CardRecord.spec.publicReceiptRemarks`，后台线下换卡制卡签发、创建卡片历史详情、送达确认清单等备注查看位置必须展示该字段。

### 3.7 收卡业务

收卡业务包含通联收卡、线上换卡收卡、线下换卡收卡。

1. 三类收卡菜单复用送达确认组件，以 `sceneType` 限定业务范围。
2. 收卡编号写入 `CardRecord.spec.receivedRecordCodes`，允许同一卡片关联多个收卡编号。
3. 收卡业务提供基本确认、已收卡片、批量编辑与单条编辑能力；基本功能的“确认收信”和清单内“确认收卡”必须使用页面上方填写的收卡日期，未填写日期时前端弹窗提示且服务端拒绝提交，不再默认使用当前日期；该日期在本次浏览会话内按收卡子菜单记忆，提交后不清空。同一呼号、卡片类型和场景存在多条卡片记录时，收卡确认只匹配未结束收卡的正式卡片记录，并按卡片编号序号从小到大绑定；已收卡但尚未点击“结束收卡”的记录继续追加收卡编号，结束后才绑定下一条。修改收卡日期时会同步重新赋予收卡编号。已收卡片清单支持将指定收卡编号从源卡片迁移到其他正式卡片 ID 的收卡清单中，迁移后按源/目标剩余编号同步维护收卡状态与收卡邮件状态。
4. 线上换卡业务收到对方“已签收”动作时，如果未制卡、未打包或未发卡，会联动补齐 `cardIssued/cardIssuedAt/envelopePrinted/cardSent/sentAt`；通联业务和线上换卡业务只要 `cardSent=true`，也会联动补齐制卡和打包状态。状态机被批量编辑为否或空时，对应时间、邮件状态、邮件时间和邮件错误同步置空。

### 3.8 审计与数据

审计包含通联记录查询、卡片记录查询、收卡记录查询、统计报表、审计日志。写操作通过 `QslAuditLog` 追加记录。
总览看板与统计报表中的卡片类统计项（卡片总数、眼球总数、待发卡片、已发卡片、发卡签收、已收卡片）统一排除“线下换卡场景且呼号为空”的 `CardRecord` 记录，避免将线下换卡占位卡纳入统计。

数据包含卡片异动、地址管理、卡片局管理、设备库维护、导入导出。卡片异动提供卡片记录修正能力，权限节点为 `card-mutation`。导入导出当前由服务端执行预检、导入、导出任务，并持久化 `ImportExportJob`；备份范围包含线下换卡活动清单，以及系统参数、通知策略、通信地址、本台设备、本台卡片等配置数据。

## 4. 前台公开能力

公开接口 API Group：`api.qsl-management.bi1kbu.com/v1alpha1`。

当前公开页面与短码：

| 能力 | 公开页面 | 短码 |
| --- | --- | --- |
| 公开查询 | `/apis/api.qsl-management.bi1kbu.com/v1alpha1/cards/page` | `[qsl-card]` |
| 线上换卡申请 | `/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL`、`/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL/{cardId}` | `[qsl-online-exchange-card]` |
| 线下换卡确认 | `/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL`、`/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL/{cardId}` | `[qsl-offline-exchange-card]` |
| 公开签收 | `/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public`、`/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public/{cardId}` | `[qsl-receipt-card]` |

线上换卡与线下换卡公开页面的服务端 endpoint 与渲染服务必须分离实现；两者路径可同属 `api.qsl-management.bi1kbu.com/v1alpha1`，但页面字段、脚本、提交逻辑不再通过同一个模板的场景开关复用。

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
| `card-print-tool` | 本地卡片打印工具令牌授权 | 通信地址/本台卡片读取、卡片记录编辑、通联读取、地址/卡片局读取、线下换卡活动读取 |

RBAC 模板实际落点：`src/main/resources/extensions/qsl-menu-role-templates.yaml`。

## 6. 本地打印工具

`tools/CardPrint` 当前作为独立 Python 工具存在，入口为：

```powershell
python -m cardprint.cli ui online
```

当前在线桥接代码事实：

1. 默认地址为 `http://localhost:8090`，但配置中可传入其他 `base_url`。
2. 读取 `card-records` 生成卡片/信封队列，卡片打印会按 `CardRecord.spec.qsoRecordName` 额外读取 `qso-records` 并注入 `qsoInfo`。
3. 信封打印会额外读取 `station-profiles`、`address-book-entries`、`bureau-entries` 做本台地址和收件地址补全。
4. 卡片版本以 `station-cards` 为准；本地打印工具通过公开接口 `GET /apis/api.qsl-management.bi1kbu.com/v1alpha1/exchange-online/-/station-cards` 拉取版本列表，并按 `sortOrder` 与版本号排序，不从 `card-records` 反推。
5. 卡片二维码拼接支持 `qrcode.path_mappings` 短路径映射，在线打印配置页可在站点地址下方配置线下换卡、线上换卡、签收确认三类短路径；默认将公开页面长路径映射为 `/EYEBALL`、`/ONLINE_EYEBALL`、`/rp` 后再追加卡片 ID 与 `cs` 参数。
6. 在线打印工具登录并拉取卡片版本时使用后台线程执行网络请求，只补齐空白本台通信地址字段，不覆盖已有本台姓名、电话、邮编、地址；受保护接口返回登录页或 HTML 时明确提示认证或权限问题，不静默解析为空数据。
7. 打印状态通过人工确认回写 `CardRecord.spec.cardIssued/cardIssuedAt` 或 `envelopePrinted`，`cardIssuedAt` 使用 `yyyy-MM-dd HH:mm:ss` 文本格式；回写会同步刷新 `CardRecord.status.flowStatus`，并按后台状态联动规则补齐或清理相关状态字段。

线下换卡业务不需要打包、制卡邮件流程和收件地址展示；后台线下换卡制卡签发页面只保留确认制卡，线下换卡创建卡片的批量编辑允许修改制卡状态。

## 7. 当前一致性说明

1. 本文件已按 2026-05-02 代码事实同步。
2. 若本文件与 `插件工程根目录` 代码不一致，以代码事实为准，并在同次改动中更新本文档。
3. 若后续新增 API、权限节点、模型字段、菜单或打印桥接契约，必须同步更新本文件、`docs/spec/BackendApiContract.md` 与 `docs/spec/项目信息结构化清单.md`。

