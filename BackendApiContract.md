# QSL 管理插件后端 API 合同

更新时间：2026-05-01  
适用插件：`qsl-management`  
目标 Halo 版本：`2.23.0`  
API 版本：`v1alpha1`  
代码核验范围：`qsl-plugin/src/main/java`、`qsl-plugin/src/main/resources/extensions/qsl-menu-role-templates.yaml`

## 1. 目标与范围

本文档描述当前代码实际提供的后端 API、认证鉴权、数据资源、公开页面与公开提交能力。若本文档与代码存在差异，以代码为准，并在同次交付中修正文档。

## 2. 官方依据

核验日期：2026-05-01

1. Halo 2.23 Extension、自定义模型与自动 CRUD  
   https://docs.halo.run/2.23/developer-guide/plugin/api-reference/server/extension
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
| 扩展资源自动 CRUD | `qsl-management.halo.run/v1alpha1` | `/apis/qsl-management.halo.run/v1alpha1` | 后台持久化资源 |
| 控制台自定义 API | `console.api.qsl-management.halo.run/v1alpha1` | `/apis/console.api.qsl-management.halo.run/v1alpha1` | 后台聚合与动作接口 |
| 前台公开 API | `api.qsl-management.halo.run/v1alpha1` | `/apis/api.qsl-management.halo.run/v1alpha1` | 匿名页面、查询、申请、确认 |
| 个人中心 API | `uc.api.qsl-management.halo.run/v1alpha1` | 无当前实现 | 预留 |

## 4. 认证与鉴权

1. 控制台 API 必须已登录，支持 Console 会话态或 `Authorization: Bearer pat_xxx`。
2. 自动 CRUD 由 Halo Extension API 与 RBAC 模板控制。
3. 前台公开 API 聚合到 `anonymous`，允许匿名访问，但叠加限流与输入校验。
4. 服务端鉴权是安全边界；前端菜单权限仅用于导航与展示。
5. RBAC `rules.resources` 使用实际资源段，例如 `card-records`、`mail-send-confirms`、`exchange-online/requests`。

## 5. 扩展资源自动 CRUD

统一路径模板：

| 方法 | 路径模板 | 说明 |
| --- | --- | --- |
| GET | `/{plural}` | 列表查询 |
| GET | `/{plural}/{name}` | 单条详情 |
| POST | `/{plural}` | 创建 |
| PUT | `/{plural}/{name}` | 全量更新 |
| DELETE | `/{plural}/{name}` | 删除 |

基础路径均为 `/apis/qsl-management.halo.run/v1alpha1`。列表查询参数遵循 Halo Extension 通用参数，如 `page`、`size`、`sort`、`labelSelector`、`fieldSelector`。

| 资源 | Kind | Plural | 权限节点 |
| --- | --- | --- | --- |
| 系统参数 | `SystemSetting` | `system-settings` | `system-settings` |
| 通信地址 | `StationProfile` | `station-profiles` | `station-profile` |
| 本台设备 | `StationEquipment` | `station-equipments` | `station-profile` |
| 本台卡片 | `StationCard` | `station-cards` | `station-profile` |
| 通联日志 | `QsoRecord` | `qso-records` | `qso-record` |
| 卡片记录 | `CardRecord` | `card-records` | `card-record`、`card-issue`、`card-mutation` 等按菜单复用 |
| 换卡申请 | `ExchangeRequest` | `exchange-requests` | `exchange-request-review` |
| 线下活动 | `OfflineActivity` | `offline-activities` | `exchange-request-review` |
| 地址管理 | `AddressBookEntry` | `address-book-entries` | `address-bureau` |
| 卡片局管理 | `BureauEntry` | `bureau-entries` | `address-bureau` |
| 设备库维护 | `EquipmentCatalogEntry` | `equipment-catalog-entries` | `equipment-catalog` |
| 审计日志 | `QslAuditLog` | `qsl-audit-logs` | `report-auditlog` |
| 导入导出任务 | `ImportExportJob` | `import-export-jobs` | `import-export` |

## 6. 控制台自定义 API

基础路径：`/apis/console.api.qsl-management.halo.run/v1alpha1`

### 6.1 总览与报表

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/overview/summary` | 总览看板聚合统计 | `overview-dashboard:view` |
| GET | `/reports/summary` | 审计统计报表聚合 | `report-auditlog:view` |

### 6.2 业务动作

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/mail-send-confirms/{cardRecordName}/confirm` | 确认发信，更新 `cardSent/sentAt` | `mail-send-confirm:edit` |
| POST | `/mail-receive-confirms/confirm` | 送达确认/收卡确认，按 `callSign + cardType + sceneType` 匹配或自动补建记录 | `mail-receive-confirm:edit` |
| POST | `/exchange-requests/{name}/approve` | 换卡申请通过并创建线上换卡卡片 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/reject` | 换卡申请拒绝 | `exchange-request-review:edit` |
| POST | `/exchange-requests/{name}/notify` | 发送线上换卡申请审核结果邮件 | `exchange-request-review:edit` |
| POST | `/notification-mails/send` | 单条发送通知邮件 | `card-record:edit` 或相关业务编辑权限 |
| POST | `/notification-mails/batch-send` | 批量发送通知邮件 | `card-record:edit` 或相关业务编辑权限 |

### 6.3 导入导出

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/imports/precheck` | 导入预检，不落库 | `import-export:edit` |
| POST | `/imports/jobs` | 创建并执行导入任务 | `import-export:edit` |
| GET | `/imports/jobs/{jobName}` | 查询导入任务 | `import-export:view` |
| GET | `/imports/jobs/{jobName}/errors` | 查询导入错误明细 | `import-export:view` |
| GET | `/imports/jobs/{jobName}/errors/download` | 下载导入错误 CSV | `import-export:view` |
| POST | `/exports/jobs` | 创建导出任务 | `import-export:edit` |
| GET | `/exports/jobs/{jobName}` | 查询导出任务 | `import-export:view` |
| GET | `/exports/jobs/{jobName}/download` | 下载导出文件 | `import-export:view` |

### 6.4 控制台请求体

送达确认：

```json
{
  "callSign": "BI1KBU",
  "cardType": "QSO",
  "sceneType": "QSO",
  "receiptRemarks": "签收备注"
}
```

`cardType` 允许：`QSO`、`SWL`、`EYEBALL`。`sceneType` 允许：`QSO`、`SWL`、`ONLINE_EYEBALL`、`EYEBALL`。

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

当前导入导出数据集：`qso-record`、`card-record`、`exchange-request-review`、`address-management`、`bureau-management`、`equipment-catalog`。`all` 仅用于导出聚合。

## 7. 前台公开 API 与页面

基础路径：`/apis/api.qsl-management.halo.run/v1alpha1`

### 7.1 公开接口

| 方法 | 路径 | 说明 | 匿名访问 |
| --- | --- | --- | --- |
| GET | `/qso-public/records` | 按呼号查询公开通联/卡片信息，可带 `sceneType` | 是 |
| GET | `/overview-public/summary` | 公开总览 | 是 |
| GET | `/exchange-online/-/bureaus` | 公开线上换卡卡片局候选，只返回卡片局名称、邮编、地址 | 是 |
| GET | `/exchange-online/-/station-cards` | 公开线上换卡本台卡片版本候选，按配置顺序返回图案、版本号、版本总量、库存余量 | 是 |
| GET | `/exchange-offline/-/activities` | 公开线下活动列表 | 是 |
| POST | `/exchange-online/-/requests` | 匿名提交线上换卡申请 | 是 |
| POST | `/exchange-offline/-/confirm` | 匿名确认线下换卡 | 是 |
| POST | `/receipt-public/-/confirm` | 匿名签收确认 | 是 |

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
