# QSL 管理插件后端 API 合同（一期）

更新时间：2026-04-15  
适用插件：`qsl-management`  
状态：`v1alpha1` 基线合同（用于后续编码与联调）

## 1. 目标与范围

本合同用于明确一期后端接口的统一约束，覆盖：

1. API 分组与版本策略（`group/version`）
2. 认证鉴权与 RBAC 权限映射
3. 数据资源的自动 CRUD 合同
4. 业务动作型 API 合同（控制台）
5. 前台公开 API 合同（匿名访问）

说明：

1. 一期只落地管理员后台能力与前台最小可用公开能力。
2. HAM 用户、操作员角色的细分授权在二期启用（节点已预留）。
3. 不做旧版兼容，按当前合同直接实现。

## 2. 官方依据（核验日期：2026-04-15）

1. 自定义模型与自动 CRUD、CustomEndpoint、Group 规则  
   https://docs.halo.run/developer-guide/plugin/api-reference/server/extension
2. API 权限控制（角色模板、资源型规则、verbs 映射）  
   https://docs.halo.run/developer-guide/plugin/security/role-template
3. RBAC（角色继承、聚合到 anonymous/authenticated）  
   https://docs.halo.run/developer-guide/plugin/security/rbac
4. RESTful API 介绍（Console/UC/Extension/Public 分组、PAT 推荐）  
   https://docs.halo.run/developer-guide/restful-api/introduction
5. 插件 API 变更日志  
   https://docs.halo.run/developer-guide/plugin/api-changelog/

## 3. API 分组与版本约定

## 3.1 分组

1. 扩展资源（自动 CRUD）  
   `qsl-management.halo.run`
2. 控制台自定义 API（仅后台）  
   `console.api.qsl-management.halo.run`
3. 前台公开 API（匿名可访问）  
   `api.qsl-management.halo.run`
4. 个人中心 API（一期预留，不实现）  
   `uc.api.qsl-management.halo.run`

## 3.2 版本

统一使用：`v1alpha1`

## 3.3 基础路径

1. 自动 CRUD：`/apis/qsl-management.halo.run/v1alpha1/...`
2. 控制台业务：`/apis/console.api.qsl-management.halo.run/v1alpha1/...`
3. 前台公开：`/apis/api.qsl-management.halo.run/v1alpha1/...`

## 4. 认证与鉴权约定

## 4.1 认证

1. 控制台 API：必须已登录，支持 `Bearer pat_xxx`（官方推荐）或 Console 会话态。
2. 前台公开 API：允许匿名访问，但强制限流与输入校验。
3. Basic Auth 默认不作为一期方案。

## 4.2 鉴权

1. 服务端必须做权限校验，前端隐藏仅作 UX，不作为安全边界。
2. 读接口要求对应 `:view` 节点；写接口要求对应 `:edit` 节点。
3. 超级管理员（`super-role`）天然可访问；其他用户通过角色模板授权。
4. 前台公开 API 通过聚合角色到 `anonymous` 暴露只读或受控写接口。

## 5. 统一数据资源（自动 CRUD）

说明：以下资源使用 Halo 扩展模型自动生成 CRUD API，用于主数据与核心业务数据持久化。

| 资源标识 | Kind（建议） | Plural（路径） | 主菜单归属 | 读权限 | 写权限 |
| --- | --- | --- | --- | --- | --- |
| system-settings | `SystemSetting` | `system-settings` | 配置/系统参数 | `plugin:qsl-management:system-settings:view` | `plugin:qsl-management:system-settings:edit` |
| station-profile | `StationProfile` | `station-profiles` | 配置/通信地址 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` |
| station-equipment | `StationEquipment` | `station-equipments` | 配置/本台设备 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` |
| station-card | `StationCard` | `station-cards` | 配置/本台卡片 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` |
| qso-record | `QsoRecord` | `qso-records` | 业务/通联记录 | `plugin:qsl-management:qso-record:view` | `plugin:qsl-management:qso-record:edit` |
| card-record | `CardRecord` | `card-records` | 业务/卡片记录 | `plugin:qsl-management:card-record:view` | `plugin:qsl-management:card-record:edit` |
| exchange-request | `ExchangeRequest` | `exchange-requests` | 审核/换卡申请 | `plugin:qsl-management:exchange-request-review:view` | `plugin:qsl-management:exchange-request-review:edit` |
| address-book | `AddressBookEntry` | `address-book-entries` | 数据/地址管理 | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` |
| bureau | `BureauEntry` | `bureau-entries` | 数据/卡片局管理 | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` |
| equipment-catalog | `EquipmentCatalogEntry` | `equipment-catalog-entries` | 数据/设备库维护 | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:equipment-catalog:edit` |
| audit-log | `QslAuditLog` | `qsl-audit-logs` | 审计/审计日志 | `plugin:qsl-management:report-auditlog:view` | `plugin:qsl-management:report-auditlog:edit` |
| import-export-job | `ImportExportJob` | `import-export-jobs` | 数据/导入导出 | `plugin:qsl-management:import-export:view` | `plugin:qsl-management:import-export:edit` |

## 6. 自动 CRUD 合同（统一模板）

对上表每个 Plural 资源，采用以下基础接口：

| 方法 | 路径模板 | 说明 | 认证 | 权限 |
| --- | --- | --- | --- | --- |
| GET | `/apis/qsl-management.halo.run/v1alpha1/{plural}` | 列表查询（分页/排序/筛选） | 必须登录 | 对应 `:view` |
| GET | `/apis/qsl-management.halo.run/v1alpha1/{plural}/{name}` | 单条详情 | 必须登录 | 对应 `:view` |
| POST | `/apis/qsl-management.halo.run/v1alpha1/{plural}` | 创建 | 必须登录 | 对应 `:edit` |
| PUT | `/apis/qsl-management.halo.run/v1alpha1/{plural}/{name}` | 全量更新 | 必须登录 | 对应 `:edit` |
| DELETE | `/apis/qsl-management.halo.run/v1alpha1/{plural}/{name}` | 删除 | 必须登录 | 对应 `:edit` |

列表查询参数遵循 Halo 扩展模型通用参数：

1. `page`（从 1 开始）
2. `size`
3. `sort`（如 `metadata.creationTimestamp,desc`）
4. `labelSelector`
5. `fieldSelector`（仅可筛选已建索引字段）

## 7. 控制台业务 API 合同（自定义）

## 7.1 总览与统计

| 方法 | 路径 | 说明 | 认证 | 权限 |
| --- | --- | --- | --- | --- |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/overview/summary` | 总览看板聚合统计（QSO总数、卡片总数等） | 必须登录 | `plugin:qsl-management:overview-dashboard:view` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/reports/summary` | 审计统计报表聚合 | 必须登录 | `plugin:qsl-management:report-auditlog:view` |

## 7.2 业务动作

| 方法 | 路径 | 说明 | 认证 | 权限 |
| --- | --- | --- | --- | --- |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/mail-send-confirms/{cardRecordName}/confirm` | 确认发信，写入发信时间与状态 | 必须登录 | `plugin:qsl-management:mail-send-confirm:edit` |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/mail-receive-confirms/confirm` | 收信确认；按规则补建记录并回写状态 | 必须登录 | `plugin:qsl-management:mail-receive-confirm:edit` |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/exchange-requests/{name}/approve` | 换卡申请通过并触发关联卡片创建 | 必须登录 | `plugin:qsl-management:exchange-request-review:edit` |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/exchange-requests/{name}/reject` | 换卡申请拒绝并记录原因 | 必须登录 | `plugin:qsl-management:exchange-request-review:edit` |

## 7.3 导入导出

| 方法 | 路径 | 说明 | 认证 | 权限 |
| --- | --- | --- | --- | --- |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/imports/precheck` | 导入预检（CSV/ZIP） | 必须登录 | `plugin:qsl-management:import-export:edit` |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs` | 创建导入任务 | 必须登录 | `plugin:qsl-management:import-export:edit` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/{jobName}` | 查询导入任务状态 | 必须登录 | `plugin:qsl-management:import-export:view` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/{jobName}/errors` | 查询导入错误明细 | 必须登录 | `plugin:qsl-management:import-export:view` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/imports/jobs/{jobName}/errors/download` | 下载导入错误回执 CSV | 必须登录 | `plugin:qsl-management:import-export:view` |
| POST | `/apis/console.api.qsl-management.halo.run/v1alpha1/exports/jobs` | 创建导出任务（单项CSV/全量ZIP） | 必须登录 | `plugin:qsl-management:import-export:edit` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/exports/jobs/{jobName}` | 查询导出任务状态 | 必须登录 | `plugin:qsl-management:import-export:view` |
| GET | `/apis/console.api.qsl-management.halo.run/v1alpha1/exports/jobs/{jobName}/download` | 下载导出文件 | 必须登录 | `plugin:qsl-management:import-export:view` |

## 7.4 请求体定义（控制台）

### 7.4.1 收信确认

`POST /mail-receive-confirms/confirm`

```json
{
  "callSign": "BG7ABC",
  "cardType": "QSO",
  "receiptRemarks": "签收备注"
}
```

说明：

1. `callSign` 必填，服务端统一转大写。
2. `cardType` 取值：`QSO` / `SWL` / `EYEBALL`。
3. 未匹配到卡片时，按 `cardType` 执行自动补建规则。

### 7.4.2 换卡申请拒绝

`POST /exchange-requests/{name}/reject`

```json
{
  "reason": "审批拒绝"
}
```

### 7.4.3 导入预检

`POST /imports/precheck`

```json
{
  "dataset": "qso-record",
  "format": "csv",
  "sourceFile": "qso-record.csv",
  "rowCount": 120
}
```

### 7.4.4 创建导入任务

`POST /imports/jobs`

```json
{
  "dataset": "qso-record",
  "format": "csv",
  "strategy": "skip",
  "sourceFile": "qso-record.csv",
  "totalCount": 120,
  "successCount": 118,
  "failedCount": 2,
  "status": "部分成功",
  "errors": [
    "第5行（ID=qso-record-xxxx）：字段校验失败"
  ]
}
```

说明：

1. `totalCount/successCount/failedCount/status/errors` 为可选，用于前端本地执行导入后回填最终任务状态与错误明细。
2. `errors` 最多持久化 1000 条非空错误信息，并可通过错误下载接口导出 CSV 回执。
3. 不传上述可选字段时，后端默认创建 `待处理` 状态任务。

### 7.4.5 创建导出任务

`POST /exports/jobs`

```json
{
  "dataset": "all",
  "format": "zip"
}
```

## 8. 前台公开 API 合同（匿名）

说明：以下接口由 `api.qsl-management.halo.run` 暴露；按需聚合角色到 `anonymous`，并叠加限流策略。

| 方法 | 路径 | 说明 | 认证 | 限制 |
| --- | --- | --- | --- | --- |
| GET | `/apis/api.qsl-management.halo.run/v1alpha1/qso-public/records` | 按完整呼号查询公开通联/卡片信息 | 匿名可访问 | 受“游客每分钟查询次数”限制 |
| POST | `/apis/api.qsl-management.halo.run/v1alpha1/exchange-public/requests` | 提交换卡申请 | 匿名可访问 | 请求体字段校验 + 限流 |
| POST | `/apis/api.qsl-management.halo.run/v1alpha1/receipt-public/confirm` | 卡片签收确认 | 匿名可访问 | 呼号+卡片号强校验 + 限流 |
| GET | `/apis/api.qsl-management.halo.run/v1alpha1/overview-public/summary` | 公共数据总览 | 匿名可访问 | 只读查询 + 限流 |

## 8.1 匿名接口限流规则（一期）

1. 限流维度：`endpoint + clientIp`，其中 `clientIp` 解析顺序为 `X-Forwarded-For` 首段、`X-Real-IP`、`remoteAddress`。
2. 限流窗口：固定窗口 1 分钟。
3. 阈值来源：优先读取持久化系统参数 `SystemSetting.spec.guestQueryPerMinute`，未配置时回退默认值 `30`。
4. 触发限流返回：`HTTP 429`，错误码 `QSL-429-0001`。
5. 当前实现为插件进程内计数（单实例一致）；若二期进入多实例部署，需升级为网关层或共享存储限流，避免实例间配额不一致。
6. 临时回退方案：将 `guestQueryPerMinute` 提高到较大值（如 `10000`）可快速放宽限流，再安排升级限流架构。

## 8.2 请求体与查询参数（公开）

### 8.2.1 公开查询

`GET /qso-public/records?callSign=BG7ABC`

说明：`callSign` 为必填查询参数。

### 8.2.2 匿名提交换卡申请

`POST /exchange-public/requests`

```json
{
  "callSign": "BG7ABC",
  "useBureau": true,
  "bureauName": "示例卡片局",
  "email": "demo@example.com",
  "name": "张三",
  "telephone": "13800000000",
  "postalCode": "510000",
  "address": "广东省广州市",
  "remarks": "请走卡片局"
}
```

### 8.2.3 匿名签收确认

`POST /receipt-public/confirm`

```json
{
  "callSign": "BG7ABC",
  "cardType": "QSO",
  "remarks": "已签收"
}
```

### 8.2.4 输入校验基线（一期）

1. `callSign`：仅允许大写字母、数字、`/`、`-`，长度 `3-16`。
2. `cardType`：仅允许 `QSO` / `SWL` / `EYEBALL`。
3. `useBureau=true` 时，`bureauName` 必填。
4. 可选字段格式约束：
   - `email`：邮箱格式校验，最大 `120` 字符。
   - `telephone`：仅允许数字、空格、`+`、`-`，最大 `30` 字符。
   - `postalCode`：仅允许字母、数字、`-`，最大 `20` 字符。
5. 长度约束：
   - `bureauName` 最大 `80` 字符。
   - `name` 最大 `60` 字符。
   - `address` 最大 `200` 字符。
   - `remarks` / `receiptRemarks` 最大 `500` 字符。

## 9. 请求与响应约定

## 9.1 自动 CRUD

按 Halo 扩展资源规范返回标准资源对象或列表对象（包含 `apiVersion/kind/metadata/spec/status` 结构）。

## 9.2 自定义 API

统一响应体：

```json
{
  "code": "QSL-0000",
  "message": "成功",
  "data": {}
}
```

失败时：

```json
{
  "code": "QSL-4XX-XXXX",
  "message": "中文可读错误信息",
  "data": null
}
```

## 9.3 错误码基线

| 错误码 | 场景 |
| --- | --- |
| QSL-400-0001 | 参数校验失败 |
| QSL-400-0002 | 文件格式不支持（导入） |
| QSL-400-0003 | CSV 类型识别失败 |
| QSL-401-0001 | 未认证 |
| QSL-403-0001 | 无权限（RBAC 拒绝） |
| QSL-404-0001 | 资源不存在 |
| QSL-409-0001 | 版本冲突（乐观锁） |
| QSL-429-0001 | 请求过于频繁（限流触发） |
| QSL-422-0001 | 业务规则不满足（如签收不匹配） |
| QSL-500-0001 | 服务端内部错误 |

## 10. 安全与审计约束

1. 所有写操作必须记录审计日志，不提供删除审计日志接口。
2. 导入任务必须输出预检结果与错误行明细，避免脏数据静默写入。
3. 前台公开接口必须启用限流、输入白名单校验、敏感字段脱敏返回。
4. 涉及跨模块写入（如换卡审批创建卡片记录）必须在同一事务边界内处理。

## 11. 一期不实现（预留）

1. `uc.api.qsl-management.halo.run/v1alpha1` 下的个人中心接口。
2. HAM 用户、操作员角色的独立授权编排。
3. 更细粒度子权限（在 `view/edit` 下继续细分）。

## 12. 与权限节点的对应关系

本合同与以下已存在节点保持一致，不新增命名体系：

1. `plugin:qsl-management:*:view`
2. `plugin:qsl-management:*:edit`

具体节点与依赖以以下文件为准并保持同步：

1. `ProductDefinition.md` 的“4.2 权限节点”
2. `qsl-plugin/src/main/resources/extensions/qsl-menu-role-templates.yaml`

## 13. 一期当前实现说明（2026-04-15）

1. 控制台业务动作接口已落地持久化写入：发信确认、收信确认、换卡审批。
2. 导入/导出任务接口已落地 `import-export-jobs` 持久化（任务创建、状态查询、错误明细查询）。
3. 导出下载接口已支持真实文件流：单项导出返回对应数据集 CSV，全量导出返回包含全部数据集 CSV 的 ZIP 压缩包。
4. 前台公开接口已落地持久化写入：匿名换卡申请、匿名签收确认。
5. `qsl-menu-role-templates.yaml` 已补齐服务端 `rules`，覆盖扩展资源 CRUD、控制台自定义 API、公开 API 匿名聚合角色。
6. 前台公开接口已接入服务端限流：按 `endpoint + clientIp` 固定窗口 1 分钟计数，阈值读取持久化系统参数 `guestQueryPerMinute`，触发时返回 `HTTP 429 / QSL-429-0001`。
7. 导入任务已支持错误明细持久化：`imports/jobs` 可写入 `errors`，`imports/jobs/{jobName}/errors` 返回明细，`imports/jobs/{jobName}/errors/download` 可下载 CSV 错误回执。
