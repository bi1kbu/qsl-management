# QSL 管理插件后端 API 合同

更新时间：2026-05-22
适用插件：`qsl-management`
目标 Halo 版本：插件声明 `>=2.23.0`，当前按 Halo 2.24 官方文档核验
API 版本：`v1alpha1`
代码核验范围：`src/main/java`、`src/main/resources/extensions/qsl-menu-role-templates.yaml`

## 1. 目标与范围

本文档描述当前代码实际提供的后端 API、认证鉴权、数据资源、公开页面与公开提交能力。若本文档与代码存在差异，以代码为准，并在同次交付中修正文档。

## 2. 官方依据

核验日期：2026-05-21

1. Halo Extension、自定义模型与自动 CRUD
   https://docs.halo.run/developer-guide/plugin/api-reference/server/extension
2. API 权限控制与角色模板  
   https://docs.halo.run/developer-guide/plugin/security/role-template
3. RBAC 与匿名聚合角色  
   https://docs.halo.run/developer-guide/plugin/security/rbac
4. RESTful API 分组与认证方式  
   https://docs.halo.run/developer-guide/restful-api/introduction
5. 插件 API 变更日志  
   https://docs.halo.run/developer-guide/plugin/api-changelog/

## 3. API 分组与路径

| API 分组 | group/version | 基础路径 | 范围 |
| --- | --- | --- | --- |
| 扩展资源自动 CRUD | `qsl-management.bi1kbu.com/v1alpha1` | `/apis/qsl-management.bi1kbu.com/v1alpha1` | 后台持久化资源 |
| 控制台自定义 API | `console.api.qsl-management.bi1kbu.com/v1alpha1` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1` | 后台聚合与动作接口 |
| 前台公开 API | `api.qsl-management.bi1kbu.com/v1alpha1` | `/apis/api.qsl-management.bi1kbu.com/v1alpha1` | 匿名页面、查询、申请、确认 |
| 个人中心 API | `uc.api.qsl-management.bi1kbu.com/v1alpha1` | 无当前实现 | 预留 |

## 4. 认证与鉴权

1. 控制台 API 必须已登录，支持 Console 会话态或 `Authorization: Bearer pat_xxx`。
2. 自动 CRUD 由 Halo Extension API 与 RBAC 模板控制。
3. 前台公开 API 聚合到 `anonymous`，允许匿名访问，但叠加限流与输入校验。
4. 服务端鉴权是安全边界；前端菜单权限仅用于导航与展示。
5. RBAC `rules.resources` 使用实际资源段，例如 `card-records`、`card-mutations`、`mail-send-confirms`、`exchange-online/requests`。
6. 本地卡片打印工具使用汇总权限模板 `qsl-management-card-print-tool`（显示名“卡片打印工具”），通过 `rbac.authorization.halo.run/dependencies` 引用通信地址/本台卡片只读、卡片记录编辑、通联记录只读、地址/卡片局只读权限，便于为个人令牌一次性授权。

## 5. 扩展资源自动 CRUD

统一路径模板：

| 方法 | 路径模板 | 说明 |
| --- | --- | --- |
| GET | `/{plural}` | 列表查询 |
| GET | `/{plural}/{name}` | 单条详情 |
| POST | `/{plural}` | 创建 |
| PUT | `/{plural}/{name}` | 全量更新 |
| DELETE | `/{plural}/{name}` | 删除 |

基础路径均为 `/apis/qsl-management.bi1kbu.com/v1alpha1`。列表查询参数遵循 Halo Extension 通用参数，如 `page`、`size`、`sort`、`labelSelector`、`fieldSelector`。

| 资源 | Kind | Plural | 权限节点 |
| --- | --- | --- | --- |
| 系统参数 | `SystemSetting` | `system-settings` | `system-settings` |
| 通信地址 | `StationProfile` | `station-profiles` | `station-profile` |
| 本台设备 | `StationEquipment` | `station-equipments` | `station-profile` |
| 本台卡片 | `StationCard` | `station-cards` | `station-profile` |
| 通联日志 | `QsoRecord` | `qso-records` | `qso-record` |
| 卡片记录 | `CardRecord` | `card-records` | `card-record`、`card-issue`、`card-mutation`、`card-print-tool` 等按菜单或工具复用 |
| 线下换卡卡片 | `OfflineExchangeCard` | `offline-exchange-cards` | `exchange-request-review`、后续独立 `offline-exchange-card` |
| 收卡记录 | `ReceiveRecord` | `receive-records` | `mail-receive-confirm`、`card-query`、后续独立 `receive-record` |
| 换卡申请 | `ExchangeRequest` | `exchange-requests` | `exchange-request-review` |
| 线下活动 | `OfflineActivity` | `offline-activities` | `exchange-request-review` |
| 地址管理 | `AddressBookEntry` | `address-book-entries` | `address-bureau` |
| 卡片局管理 | `BureauEntry` | `bureau-entries` | `address-bureau` |
| 设备库维护 | `EquipmentCatalogEntry` | `equipment-catalog-entries` | `equipment-catalog` |
| 审计日志 | `QslAuditLog` | `qsl-audit-logs` | `report-auditlog` |
| 导入导出任务 | `ImportExportJob` | `import-export-jobs` | `import-export` |

## 6. 控制台自定义 API

基础路径：`/apis/console.api.qsl-management.bi1kbu.com/v1alpha1`

### 6.1 总览与报表

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/overview/summary` | 总览看板聚合统计 | `overview-dashboard:view` |
| GET | `/reports/summary` | 审计统计报表聚合，包含月度收发卡片图表数据 | `report-auditlog:view` |

统计口径：卡片类统计只纳入正式 `C{序号}` 且呼号非空的 `CardRecord`。`sentTotal` 统计明确已发卡、已签收、有发卡时间的记录；线上换卡中 `cardIssued=true`、`envelopePrinted=true` 且 `createdMailStatus=SENT` 的历史记录也纳入已发，避免未回写 `cardSent` 时偏小。`receivedTotal` 优先按 `ReceiveRecord.spec.callSign` 去重，呼号为空时按 `ReceiveRecord.spec.outboundCardNames` 中的卡片 ID 去重。

`/reports/summary` 在上述汇总字段外返回 `charts.monthlyCardFlow`，每项包含 `month/sentTotal/receivedTotal`。月份范围从第一张正式卡片的 `CardRecord.spec.cardDate` 所在月份开始补齐至当前月份；月度发卡按同一已发统计口径取 `sentAt` 所在月份，缺失时回退 `cardDate`；月度收卡按 `ReceiveRecord.spec.receivedDate/receivedAt` 归月，并在月内按呼号或关联发卡 ID 去重。

### 6.2 业务动作

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/mail-send-confirms/{cardRecordName}/confirm` | 确认发信，更新 `cardSent/sentAt` | `mail-send-confirm:edit` |
| POST | `/mail-receive-confirms/confirm` | 收卡确认创建 `ReceiveRecord` 并尝试关联发卡记录；无法匹配时不再新建发卡编号 | `mail-receive-confirm:edit` |
| POST | `/receipt-confirms/{cardRecordName}/confirm` | 控制台确认对方已签收我方发出的卡片，写入 `CardRecord.spec.receiptConfirmed/publicReceiptRemarks`，不创建 `ReceiveRecord`，不分配收卡编号 | `mail-receive-confirm:edit` |
| POST | `/mail-receive-confirms/{cardRecordName}/received-date` | 修改卡片收卡日期，并同步更新关联收卡记录日期 | `mail-receive-confirm:edit` |
| POST | `/mail-receive-confirms/{cardRecordName}/received-record-code/migrate` | 将指定收卡记录从源卡片关联迁移到目标卡片关联 | `mail-receive-confirm:edit` |
| POST | `/receive-records/{receivedRecordCode}/link-outbound-card` | 将未关联发卡记录的收卡记录人工关联到匹配的正式发卡卡片，并刷新目标卡片已收状态 | `card-query:edit` |
| POST | `/receive-records/{receivedRecordCode}/create-online-card` | 为未匹配的线上换卡收卡记录创建正式发卡卡片，并写入 `ReceiveRecord.spec.outboundCardNames/matchStatus` | `mail-receive-confirm:edit` |
| POST | `/card-mutations/{cardRecordName}/resend` | 卡片重发：清理制卡、打包、发信及相关邮件状态，刷新流程状态，保留收卡事实 | `card-mutation:edit` |
| POST | `/card-mutations/{cardRecordName}/mark-error` | 发卡异常：将卡片类型标记为 `原类型（ERROR）`，可追加异常备注 | `card-mutation:edit` |
| POST | `/card-mutations/{cardRecordName}/mark-resend` | 标记重发：解除发卡异常，将卡片类型还原为原类型；前端随后调用卡片重发接口清理状态 | `card-mutation:edit` |
| POST | `/exchange-requests/{name}/approve` | 换卡申请通过；只更新审核状态，不创建线上换卡卡片 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/reject` | 换卡申请拒绝 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/create-card` | 已通过的换卡申请显式创建线上换卡卡片；服务端校验状态、场景和呼号并防止重复创建 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/mark-card-created` | 将已通过的换卡申请强制标记为已创建卡片；只写入 `ExchangeRequest.status.cardCreated/cardCreatedAt/cardCreatedBy`，不创建卡片记录 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/notify` | 发送线上换卡申请审核结果邮件 | `exchange-request-review:edit` |
| POST | `/online-card-imports` | 导入手工文本解析后的线上换卡数据，直接创建 `ONLINE_EYEBALL` 卡片记录并按需创建/绑定地址簿 | `online-bh6syx-import:edit` |
| POST | `/notification-mails/send` | 单条发送通知邮件 | `card-record:edit` 或相关业务编辑权限 |
| POST | `/notification-mails/apply-policy` | 按当前邮件策略自动处理单条邮件场景；`AUTO_SKIP` 只写入跳过状态，`AUTO_SEND` 才触发发送，`MANUAL` 不改数据 | `card-record:edit` 或相关业务编辑权限 |
| POST | `/notification-mails/batch-send` | 批量发送通知邮件 | `card-record:edit` 或相关业务编辑权限 |
| POST | `/notification-mails/test` | 向“本台电子邮件”发送测试通知邮件 | `system-settings:edit` |

### 6.3 AI 能力

AI 能力仅在控制台登录态下可用，不提供公开接口。AI API Key 写入 Halo `Secret`，`SystemSetting.spec` 只保存供应商、接口地址、模型、超时、并发上限、功能开关、完整提示词模板等非敏感配置；前端不回显 API Key。自定义提示词不允许改变服务端指定的 JSON Schema 返回结构；服务端在调用 OpenAI 兼容接口时仍通过 `response_format=json_schema` 约束返回，并按 `aiMaxConcurrentRequests` 控制第三方 AI 调用并发，429 限流错误会短延迟重试。AI 调用可能将姓名、电话、邮箱、邮编和通信地址发送到配置的第三方模型服务，因此功能默认关闭。

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/ai-config-tests` | 测试 OpenAI 兼容接口；请求中 `saveApiKey=true` 且 `apiKey` 非空时写入 Secret | `system-settings:edit` |
| POST | `/ai-address-normalizations/preview` | 对选中的 `AddressBookEntry` 地址做 AI 规范化预览，不落库 | `address-bureau:edit` |
| POST | `/ai-address-normalizations/apply` | 应用选中的 AI 地址整理结果，仅更新 `AddressBookEntry.spec.address` | `address-bureau:edit` |
| POST | `/ai-online-import-parses` | 将线上换卡单条/批量导入文本解析为导入预览行，不直接创建卡片 | `online-bh6syx-import:edit` |
| POST | `/qrz-credential-tests` | 测试并保存 QRZ.COM / QRZ.CN 非敏感配置；密码和自动刷新 Cookie 仅写入 Halo `Secret` | `system-settings:edit` |
| POST | `/qrz-address-lookups/preview` | 按呼号从 QRZ.COM 官方 XML 或 QRZ.CN 查询页面获取特征并调用 AI 解析，返回地址预览，不落库 | `address-bureau:edit` |

QRZ.COM 地址获取使用官方 XML 接口，配置项为用户名、Secret 名称与 XML Base URL；QRZ.COM XML 中的 `call/name_fmt/fname/name/addr1/addr2/state/country/zip/email` 等字段优先由服务端结构化解析，结构化字段足够时不调用 AI，避免 AI 漏掉邮箱、邮编或详细地址；字段不足时才将预处理后的 XML 特征交给 AI 兜底。QRZ.CN 地址获取使用可配置查询 URL 模板。服务端只允许访问 QRZ.COM / QRZ.CN 官方域名，凭据测试先用临时配置完成远程校验，通过后才写入系统参数与 Secret。QRZ.CN 查询优先用用户名和密码登录获取 Cookie，再用 Cookie 抓取查询页面。服务端识别 QRZ.CN `/call/` 页面时，会先按配置 URL 原样请求，再按站内表单提交呼号，兼容 `q` 与 `callsign` 字段；由于 QRZ.CN 旧站存在概率性返回呼号搜索页的问题，服务端会对每次请求结果校验目标呼号并重试；若页面已明确提示“呼号没有在数据库中/添加该呼号信息”，立即返回呼号资料不存在，不再重试或调用 AI；无有效目标呼号资料时明确返回错误，不把空搜索页交给 AI 解析。有效页面会在交给 AI 前抽取页面主体、呼号附近内容和图片文件名线索，降低无关导航、图片化字段和旧页面编码对解析的影响。两类查询均默认关闭，查询结果只用于地址管理页面预览，必须由用户手动确认填入地址表单并再次保存后才写入 `AddressBookEntry`。QRZ 密码、Cookie 与 AI API Key 不进入导入导出数据包。

### 6.4 导入导出

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/imports/precheck` | 导入预检，不落库 | `import-export:edit` |
| POST | `/imports/jobs` | 创建并执行导入任务 | `import-export:edit` |
| GET | `/imports/jobs/{jobName}` | 查询导入任务 | `import-export:view` |
| GET | `/imports/jobs/{jobName}/errors` | 查询导入错误明细 | `import-export:view` |
| GET | `/imports/jobs/{jobName}/errors/download` | 下载导入错误 CSV | `import-export:view` |
| POST | `/legacy-migrations/precheck` | 旧版本数据模型原地迁移预检，不写入数据 | `import-export:edit` |
| POST | `/legacy-migrations/execute` | 旧版本数据模型原地迁移，将旧卡片收卡字段拆分为 `ReceiveRecord`，并清理旧临时记录 | `import-export:edit` |
| POST | `/exports/jobs` | 创建导出任务 | `import-export:edit` |
| GET | `/exports/jobs/{jobName}` | 查询导出任务 | `import-export:view` |
| GET | `/exports/jobs/{jobName}/download` | 下载导出文件 | `import-export:view` |

### 6.5 控制台请求体

收卡确认：

```json
{
  "callSign": "BI1KBU",
  "cardType": "QSO",
  "sceneType": "QSO",
  "receivedDate": "2026-05-02",
  "receiptRemarks": "签收备注",
  "offlineActivityName": "202604ACT01",
  "targetCardRecordName": "C1001"
}
```

`cardType` 允许：`QSO`、`SWL`、`EYEBALL`。`sceneType` 允许：`QSO`、`SWL`、`ONLINE_EYEBALL`、`EYEBALL`。`receivedDate` 必填，格式为 `yyyy-MM-dd`，用于生成收卡编号日期段并写入收卡时间；不填返回 `QSL-400-0001`，不再默认使用当前日期时间。2.0.0 起，服务端先创建 `ReceiveRecord`，再尝试关联已有发卡记录；收卡编号来源统一为 `ReceiveRecord.metadata.name`。线下换卡按活动、呼号、卡片类型匹配，允许同活动空呼号卡片在收卡时补写呼号。找不到发卡记录时，`ReceiveRecord.spec.matchStatus=未匹配`，不再自动创建 `CardRecord`。

控制台签收确认：

```json
{
  "receiptRemarks": "签收备注"
}
```

`cardRecordName` 必须是正式 `C{序号}` 卡片编号。服务端将 `receiptConfirmed` 置为 `true`，追加 `publicReceiptRemarks`，并按场景联动补齐发卡状态。该接口用于线上换卡“签收确认”，不创建 `ReceiveRecord`，不分配 `receivedRecordCode`。

修改收卡日期：

```json
{
  "receivedDate": "2026-05-02"
}
```

服务端会将 `cardReceived` 置为 `true`，按 `receivedDate` 更新 `receivedAt` 日期段，并同步更新通过 `ReceiveRecord.spec.outboundCardNames` 关联到该卡片的 `ReceiveRecord.spec.receivedDate/receivedAt`。2.0.7 起不再读取或改写 `CardRecord.spec.receivedRecordCodes`。

迁移收卡编号：
```json
{
  "receivedRecordCode": "R0001-20260502",
  "targetCardRecordName": "C1002"
}
```
`cardRecordName` 为源卡片 ID，源卡片与目标卡片都必须是正式 `C{序号}` 卡片记录。服务端会读取指定 `ReceiveRecord`，将 `ReceiveRecord.spec.outboundCardNames` 中的源卡片编号替换为目标卡片编号，并按新的关联结果刷新源卡片与目标卡片的收卡状态和收卡时间。2.0.7 起不再迁移 `CardRecord.spec.receivedRecordCodes` 字段内容。

卡片重发：

无需请求体。`cardRecordName` 必须是正式 `C{序号}` 卡片编号。服务端会将 `cardIssued/cardIssuedAt/envelopePrinted/cardSent/sentAt` 以及制卡、发卡相关邮件状态清空，并按状态机刷新 `status.flowStatus`。`cardReceived/receivedAt` 与 `ReceiveRecord.spec.outboundCardNames` 收卡关联事实保持不变。

发卡异常：

```json
{
  "remarks": "打印错版"
}
```

`cardRecordName` 必须是正式 `C{序号}` 卡片编号。服务端将当前 `CardRecord.spec.cardType` 追加中文后缀 `（ERROR）`；若已存在 `（ERROR）` 或 `(ERROR)` 后缀，返回 `QSL-422-0001`，避免重复标记。`remarks` 可选，最长 500 字符，填写后追加到 `businessRemarks`。

标记重发：

无需请求体。`cardRecordName` 必须是正式 `C{序号}` 卡片编号，且当前 `CardRecord.spec.cardType` 必须为 `原类型（ERROR）` 或 `原类型(ERROR)`。服务端会移除 ERROR 后缀，还原原始卡片类型。控制台“标记重发”按钮在该接口成功后继续调用 `/card-mutations/{cardRecordName}/resend`，完成制卡、打包、发信及相关邮件状态清理，使记录进入卡片重发清单。

换卡申请拒绝：

```json
{
  "reason": "审批拒绝"
}
```

通知邮件发送：

```json
{
  "cardRecordName": "C1001",
  "scene": "created",
  "source": "manual"
}
```

`scene` 当前前端使用：`created`、`sent`、`received`。

测试邮件发送：

```json
{
  "scene": "exchange-reviewed"
}
```

`scene` 允许：`created`、`sent`、`received`、`exchange-reviewed`。服务端使用 `StationProfile.spec.myEmail` 作为收件地址，测试数据中对方呼号等字段临时使用本台资料，卡片类型固定为 `EYEBALL`，卡片编号固定为 `C0001`。

线上换卡数据导入：

```json
{
  "defaultCardVersion": "默认卡片A",
  "source": "手工文本导入",
  "rows": [
    {
      "callSign": "BI1KBU",
      "status": "对方已寄出，待我签收",
      "recipientName": "测试台",
      "telephone": "138****0000",
      "address": "北京市某区某路",
      "postalCode": "100000",
      "email": "bi1kbu@example.test",
      "cardVersion": ""
    }
  ]
}
```

`source` 只允许 `手工文本导入` 或 `BH6SYX卡片广场`，两类导入统一提交到 `/online-card-imports`，由 `source` 区分来源。服务端只允许 `status` 为 `对方已寄出，待我签收` 或 `待双方寄出` 的行创建卡片。缺失可选字段按空值处理；缺呼号或缺卡片版本的行失败。成功行直接创建 `CardRecord`，固定 `cardType=EYEBALL`、`sceneType=ONLINE_EYEBALL`、`cardReceived=false`，以本站卡片编号 `CardRecord.metadata.name` 作为后续流程主键，并将收件信息写入或复用 `AddressBookEntry` 后绑定 `CardRecord.spec.addressEntryName`。地址管理导入导出字段包含 `destinationCountry`（去向国，非必填），线上换卡导入暂不从来源数据自动填充该字段。线上换卡创建卡片默认写入卡片备注“期待与您空中相遇。\nLooking forward to meeting you on the air.”。BH6SYX 表格中的“交换ID”不提交服务端、不写入数据、不参与去重；“对方备注”仅在前端导入清单预览中显示，不提交服务端，也不写入卡片或地址数据。

导入预检/导入任务：

```json
{
  "format": "csv",
  "strategy": "skip",
  "sourceFile": "qso-record.csv",
  "datasets": [
    {
      "dataset": "qso-record",
      "rows": [
        {
          "id": "QSO1001",
          "callSign": "BI1KBU",
          "sceneType": "QSO",
          "date": "2026-05-01"
        }
      ]
    }
  ]
}
```

导出任务：

```json
{
  "dataset": "all",
  "format": "zip"
}
```

当前导入导出数据集：`qso-record`、`card-record`、`receive-record`、`exchange-request-review`、`offline-activity`、`offline-exchange-card`、`address-management`、`bureau-management`、`equipment-catalog`、`system-setting`、`station-profile`、`station-equipment`、`station-card`。`all` 仅用于导出聚合，覆盖业务数据、收卡事实、线下换卡活动卡与配置菜单数据。2.0.0 前导出包需要先转换：从旧 `card-record.receivedRecordCodes` 聚合生成 `receive-record.csv`，从旧线下 `card-record.offlineActivityName` 聚合生成 `offline-exchange-card.csv`，并清理旧卡片记录中已迁出的收卡字段，过滤旧导出中误写入 `card-record.csv` 的 `qsl-station-card-*` 本台卡片版本占位记录，避免同一收卡事实或非业务卡片在新模型中重复统计。

旧版本一键迁移用于处理“卸载插件后 Extension 数据仍保留”的场景。迁移范围为当前 Halo 存储中的旧版 QSL 数据，执行前必须先导出全部数据备份；执行接口要求请求体 `confirmText` 固定为“确认迁移旧版本数据”。迁移过程按旧版 `CardRecord.spec.receivedRecordCodes` 聚合创建 `ReceiveRecord`，按旧版线下 `CardRecord.spec.offlineActivityName` 创建 `OfflineExchangeCard`，删除 `qsl-station-card-*` 本台卡片版本占位记录和旧版自动收卡临时卡片，清空已迁出的收卡字段，并修正 `SystemSetting.spec.cardRecordSequence/receiveRecordSequence`。迁移接口具备幂等性：同名 `ReceiveRecord` 或 `OfflineExchangeCard` 已存在时跳过创建。

## 7. 前台公开 API 与页面

基础路径：`/apis/api.qsl-management.bi1kbu.com/v1alpha1`

### 7.1 公开接口

| 方法 | 路径 | 说明 | 匿名访问 |
| --- | --- | --- | --- |
| GET | `/qso-public/records` | 按呼号查询公开通联/卡片信息，可带 `sceneType` | 是 |
| GET | `/overview-public/summary` | 公开总览 | 是 |
| GET | `/exchange-online/-/bureaus` | 公开线上换卡卡片局候选，只返回卡片局名称、邮编、地址 | 是 |
| GET | `/exchange-online/-/station-cards` | 公开线上换卡本台卡片版本候选，按配置顺序返回图案、版本号、版本总量、库存余量 | 是 |
| GET | `/exchange-offline/-/activities` | 公开线下活动列表 | 是 |
| POST | `/exchange-online/-/requests` | 匿名提交线上换卡申请；同呼号存在待审核申请时返回 `409/QSL-409-0001`；提交写入成功后返回本站通信地址用于寄送提示 | 是 |
| POST | `/exchange-offline/-/confirm` | 匿名确认线下换卡；提交校验通过并写入卡片后才返回本站通信地址 | 是 |
| POST | `/receipt-public/-/confirm` | 匿名签收确认 | 是 |

公开 `POST /exchange-offline/-/confirm` 和 `POST /receipt-public/-/confirm` 均受 `QslPublicRateLimitService` 限制，限流键分别为 `exchange-offline-confirm` 与 `receipt-public-confirm`。线上换卡申请资源名由服务端扫描现有 `EX####` 并生成下一号，例如 `EX0001`、`EX0002`；历史 `exchange-request-*` 名称不参与新序列计算。前端“不创建卡片”占位记录使用 `NC####` 递增资源名，后台可持久化，前台作为空白卡片 ID 展示。

### 7.2 公开页面

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/cards/page` | 公开查询页面，支持 `callSign`、`sceneType`、`embed`、`embedId` |
| GET | `/receipt-public` | 公开签收页面，支持查询参数预填 |
| GET | `/receipt-public/{cardId}` | 公开签收页面，按卡片编号与呼号二次校验后预填 |
| GET | `/ONLINE_EYEBALL` | 线上换卡申请页面 |
| GET | `/ONLINE_EYEBALL/{cardId}` | 线上换卡申请页面，按卡片编号与呼号二次校验后预填 |
| GET | `/EYEBALL` | 线下换卡确认页面 |
| GET | `/EYEBALL/{cardId}` | 线下换卡确认页面，按卡片编号预填 |

线下换卡页面 HTML 不直接包含本站通信地址、收件人、电话或邮箱；本站联系信息只在 `POST /exchange-offline/-/confirm` 成功写入后随响应返回，用于页面展示提交成功后的寄送提示。线下换卡卡片不绑定通信地址或卡片局地址，服务端不得为线下换卡卡片写入 `CardRecord.spec.addressEntryName`。

### 7.3 短码

当前编辑器和前台内容转换支持：

1. `[qsl-card]`
2. `[qsl-card callSign="BI1KBU"]`
3. `[qsl-receipt-card]`
4. `[qsl-receipt-card callSign="BI1KBU" cardId="C1001"]`
5. `[qsl-online-exchange-card]`
6. `[qsl-offline-exchange-card]`

### 7.4 限流与输入校验

1. 限流维度：`endpoint + clientIp`。
2. `clientIp` 解析顺序：`X-Forwarded-For` 首段、`X-Real-IP`、`remoteAddress`。
3. 窗口：固定窗口 1 分钟。
4. 阈值来源：`SystemSetting.spec.guestQueryPerMinute`，默认 `30`。
5. 触发限流：`HTTP 429`，错误码 `QSL-429-0001`。
6. `callSign` 仅允许大写字母、数字、`/`、`-`，长度 `3-16`。
7. 线上换卡申请请求体新增 `cardVersion`，至少一个、最多两个版本，多个版本以 `、` 分隔；服务端会校验版本存在且库存余量大于 0。
8. 公开写入接口会校验邮箱、电话、邮编、备注长度等字段。

## 8. 响应约定

自动 CRUD 返回 Halo Extension 标准对象或列表。

自定义 API 成功响应：

```json
{
  "code": "QSL-0000",
  "message": "成功",
  "data": {}
}
```

自定义 API 失败响应：

```json
{
  "code": "QSL-4XX-XXXX",
  "message": "中文可读错误信息",
  "data": null
}
```

当前错误码基线：

| 错误码 | 场景 |
| --- | --- |
| `QSL-400-0001` | 参数校验失败 |
| `QSL-400-0002` | 文件格式不支持 |
| `QSL-400-0003` | 数据集或 CSV 类型识别失败 |
| `QSL-401-0001` | 未认证 |
| `QSL-403-0001` | 无权限 |
| `QSL-404-0001` | 资源不存在 |
| `QSL-409-0001` | 版本冲突 |
| `QSL-422-0001` | 业务规则不满足 |
| `QSL-429-0001` | 请求过于频繁 |
| `QSL-500-0001` | 服务端内部错误 |

## 9. 安全与审计

1. 所有后台控制台动作接口必须已登录。
2. 所有公开接口必须经过输入校验与限流。
3. 关键写操作追加 `QslAuditLog`。
4. 审计日志不提供业务删除能力。
5. 导入任务由服务端执行，记录预检/执行结果与错误明细，避免前端静默写入脏数据。

## 10. 当前实现说明

1. 菜单已按 `通联业务/线上换卡业务/线下换卡业务/收卡业务` 场景拆分。
2. `CardRecord` 已承担制卡、信封打印、发信、签收、收卡编号、邮件状态等核心状态字段。
3. 换卡公开页面当前使用 `/ONLINE_EYEBALL`、`/EYEBALL`，与 `sceneType` 保持一致；签收公开页面使用 `/receipt-public`。
4. 线下活动使用 `OfflineActivity` 持久化。
5. 通知邮件接口已落地在 `/notification-mails/send` 与 `/notification-mails/batch-send`。
6. RBAC 模板已覆盖控制台 CustomEndpoint、扩展资源 CRUD 与公开匿名接口。
7. 线上换卡业务的“导入数据”菜单包含单条导入、批量导入、BH6SYX卡片广场导入。服务端直接创建线上换卡卡片记录，不创建 `ExchangeRequest`；普通文本导入写入“手工文本导入”来源，BH6SYX导入写入“BH6SYX卡片广场”来源。
8. 2.0.0 起新增 `ReceiveRecord` 与 `OfflineExchangeCard`，收卡事实与线下换卡活动卡开始从 `CardRecord` 中解耦。
9. 审计卡片记录查询和收卡记录查询按业务场景 tab 展示；卡片记录查询聚合 `CardRecord`，收卡记录查询聚合 `ReceiveRecord`，收卡编号以 `ReceiveRecord.metadata.name` 为准。


