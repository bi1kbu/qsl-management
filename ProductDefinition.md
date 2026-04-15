# QSL卡片管理系统产品定义

## 1. 产品一句话定义

面向业余无线电场景的 QSO 记录与 QSL 卡片全流程管理产品，覆盖“记录、制卡、发卡、收卡、查询、申请、审计、统计”的业务闭环，并兼顾公开查询与隐私保护。

---

## 2. 菜单组织

零级菜单分组：QSL管理（左侧菜单分组名，不是可点击功能页面）

入口路由：`/qsl`（仅重定向到默认功能页）

一级菜单：总览、配置、业务、审核、审计、数据

二级菜单：一级菜单下的具体功能页（如总览看板、系统参数、通联记录等）

说明：当前实现中，左侧展示结构为“QSL管理分组 -> 一级菜单 -> 二级菜单”。

### 2.0 菜单与路由映射

| 一级菜单 | 二级菜单 | 控制台路由 | 只读节点 | 编辑节点 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 总览 | 总览看板 | `/qsl/overview/overview-dashboard` | `plugin:qsl-management:overview-dashboard:view` | `plugin:qsl-management:overview-dashboard:edit` | 独立节点 |
| 配置 | 系统参数 | `/qsl/settings/system-settings` | `plugin:qsl-management:system-settings:view` | `plugin:qsl-management:system-settings:edit` | 独立节点 |
| 配置 | 通信地址 | `/qsl/settings/station-profile` | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | 与“本台设备/本台卡片”共用节点 |
| 配置 | 本台设备 | `/qsl/settings/station-equipment` | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | 与“通信地址/本台卡片”共用节点 |
| 配置 | 本台卡片 | `/qsl/settings/station-card` | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | 与“通信地址/本台设备”共用节点 |
| 业务 | 通联记录 | `/qsl/business/qso-record` | `plugin:qsl-management:qso-record:view` | `plugin:qsl-management:qso-record:edit` | 独立节点 |
| 业务 | 卡片记录 | `/qsl/business/card-record` | `plugin:qsl-management:card-record:view` | `plugin:qsl-management:card-record:edit` | 独立节点 |
| 业务 | 发信确认 | `/qsl/business/mail-send-confirm` | `plugin:qsl-management:mail-send-confirm:view` | `plugin:qsl-management:mail-send-confirm:edit` | 独立节点 |
| 业务 | 收信确认 | `/qsl/business/mail-receive-confirm` | `plugin:qsl-management:mail-receive-confirm:view` | `plugin:qsl-management:mail-receive-confirm:edit` | 独立节点 |
| 审核 | 换卡申请 | `/qsl/review/exchange-request-review` | `plugin:qsl-management:exchange-request-review:view` | `plugin:qsl-management:exchange-request-review:edit` | 独立节点 |
| 审计 | 通联记录查询 | `/qsl/audit/qso-query` | `plugin:qsl-management:qso-query:view` | `plugin:qsl-management:qso-query:edit` | 独立节点 |
| 审计 | 卡片记录查询 | `/qsl/audit/card-query` | `plugin:qsl-management:card-query:view` | `plugin:qsl-management:card-query:edit` | 独立节点 |
| 审计 | 统计报表 | `/qsl/audit/report-auditlog` | `plugin:qsl-management:report-auditlog:view` | `plugin:qsl-management:report-auditlog:edit` | 与“审计日志”共用节点 |
| 审计 | 审计日志 | `/qsl/audit/audit-log` | `plugin:qsl-management:report-auditlog:view` | `plugin:qsl-management:report-auditlog:edit` | 与“统计报表”共用节点 |
| 数据 | 地址管理 | `/qsl/data/address-management` | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` | 与“卡片局管理”共用节点 |
| 数据 | 卡片局管理 | `/qsl/data/bureau-management` | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` | 与“地址管理”共用节点 |
| 数据 | 设备库维护 | `/qsl/data/equipment-catalog` | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:equipment-catalog:edit` | 独立节点 |
| 数据 | 导入导出 | `/qsl/data/import-export` | `plugin:qsl-management:import-export:view` | `plugin:qsl-management:import-export:edit` | 页内拆分“导入板块/导出板块” |

### 2.1 总览

总览看板是统计报表的可视化表现形式，包括QSO总数、眼球总数、卡片总数、待发卡片（数）、已发卡片（数）、发卡签收（数）、已收卡片（数）

### 2.2 配置

配置页包括以下内容：系统参数、通信地址、本台设备、本台卡片

#### 系统参数

​	**游客每分钟查询次数**：*整数*，用于限制单IP每分钟通过前台卡片访问后端接口的频率

​	**换卡是否需要审核**：*布尔值*，用于控制游客在前台填写换卡申请后自动通过审核还是需要人工审核

#### 通信地址

​	**本台呼号（My_Call_Sign）**

​	**姓名（My_Name）**

​	**电话（My_Telephone）**

​	**邮编（My_Postal_Code）**

​	**收件地址（My_Address）**

​	**电子邮件（My_E-mail）**

​	**备注（Station_Remarks）**

#### 本台设备（二维字典）

​	**设备（My_RIG）**：通过新增按钮可将设备库中的设备标记为本站已持有，并在通联记录时使用

​		**天线（My_RIG_ANT）**：与设备相关联，通过新增按钮可将设备库中的天线标记为本台设备可用，并在通联记录时使用

​		**功率（My_RIG_PWR）**：与设备相关联，通过新增按钮可将设备库中的功率候选标记为本台设备可用，并在通联记录时使用

​		**模式（My_RIG_MODE）**：与设备相关联，通过新增按钮可将设备库中的模式标记为本台设备可用，并在通联记录时使用

#### 本台卡片

​	上传图片，并给图片添加 卡片版本（Card_Version）名称

### 2.3 业务

#### 通联记录

​	用于记录与其他电台产生的QSO，包括：

​	**基本信息**

​		**日期（DATE）**：Y-M-D 格式

​		**时间（TIME）**：HHmm 格式

​		**时区（TIMEZONE）**：UTC或UTC+8二选一

​		**是否实时**：非字段，复选框，勾选后将日期时间时区的输入框设置为实时的UTC时间，并每分钟跳动

​		**频率（FREQ）**：文本型

​		**设备（My_RIG）**：下拉选项

​		**模式（My_RIG_MODE）**：下拉选项

​		**天线（My_RIG_ANT）**：下拉选项

​		**功率（My_RIG_PWR）**：下拉选项

​	**对方电台信息**

​		**呼号（Call_Sign）**：文本型

​		**设备（RIG）**：输入内容自动联想设备库的内容并选中，如果联想不到则在设备库中新增后再被选中

​		**天线（ANT）**：输入内容自动联想设备库的内容并选中，如果联想不到则在设备库中新增后再被选中

​		**功率（PWR）**：输入内容自动联想设备库的内容并选中，如果联想不到则在设备库中新增后再被选中

​		**位置（QTH）**：文本型

​	**信号报告**

​		**给对方（RST_Sent）**：文本型，在页面层面和模式相关联，如果为CW，默认显示599，否则默认显示59。可修改可留空。

​		**给我方（RST_Rcvd）**：文本型，在页面层面和模式相关联，如果为CW，默认显示599，否则默认显示59。可修改可留空。

​		**备注（Remarks）**：文本型

​	**历史记录**

​		在输入对方呼号后自动显示历史通信记录，显示字段包括对方呼号、日期、频率、模式、设备、天线、功率、位置、备注

#### 卡片记录

​	**对方呼号（Call_Sign）**：文本型

​	**卡片类型（Card_Type）**：下拉选项，包括QSO、SWL、EYEBALL

​	**卡片版本（Card_Version）**：下拉选项，选择上传的卡片图片对应的名称

​	**关联记录QSO_ID**：选填。点击后弹出页内卡片，卡片内可通过呼号、QSO_ID等检索到通联记录，并选中保存对应的QSO_ID

​	**卡片创建日期（Card_DATE）**：Y-M-D 格式，如果关联了QSO_ID，则锁定使用QSO的日期，否则可手动填写

​	**卡片创建时间（Card_TIME）**：HHmm 格式，如果关联了QSO_ID，则锁定使用QSO的时间，否则可手动填写

​	**卡片备注（Card_Remarks）**：文本型，多行文本，支持换行。

#### 发信确认

​	显示一个清单，包括卡片ID（Card_ID）、对方呼号（Call_Sign）、卡片类型（Card_Type）、卡片创建日期（Card_DATE）、卡片打印日期（（Card_Print_DATE）日期+时间或显示“未制卡”）、信封打印日期（（Envelope_Print_DATE）日期+时间或显示“未打印”）、卡片备注（Card_Remarks）、确认发信（按钮，按下后隐藏确认发信按钮并显示提示文本“已发卡”+点下按钮时的日期时间）

#### 收信确认

​	输入对方呼号（Call_Sign），选择卡片类型（Card_Type），并找到对应的QSO记录或卡片记录，如果能找到记录，则将该记录的 已收卡片（Card_Received，布尔值）标记为 True 。

​	如果卡片类型为QSO且查询无记录，则在点击确认收信时自动创建一个通联记录，并关联创建绑定了这个通联记录的卡片记录，并将该记录的 已收卡片（Card_Received，布尔值）标记为 True ，并将卡片备注（Card_Remarks）填写为“异常QSO记录，无法找到原始通信QSO”。

​	如果卡片类型为SWL且查询无记录，则在点击确认收信时自动创建一个通联记录，并关联创建绑定了这个通联记录的卡片记录，并将该记录的 已收卡片（Card_Received，布尔值）标记为 True ，已发卡片（Card_Sent，布尔值）标记为 True ，并将卡片备注（Card_Remarks）填写为“SWL收信，无需发卡”。

​	如果卡片类型为EYEBALL且查询无记录，则在点击确认收信时自动创建一个eyeball类型的卡片

### 2.4 审核

**换卡申请**

​	显示前台提交过来的换卡申请的各项字段，并显示同意和拒绝按钮。点击按钮后按钮隐藏，并显示通过与否的状态文字。如果审批通过，则创建对应的eyeball卡片记录。

### 2.5 审计

**通联记录查询**

​	显示通联记录的对方呼号、频率、日期、时间、时区等，点击条目后会展开条目，显示完整字段，完整字段参照“通联记录”菜单

**卡片记录查询**

​	显示“卡片记录”的各项字段内容

**统计报表**

​	QSO总数（数量）、眼球总数（数量）、卡片总数（数量）、待发卡片（数量）、已发卡片（数量）、发卡签收（数量）、已收卡片（数量）

**审计日志**

​	所有操作全部都需要留存审计日志，且不得删除日志

### 2.6 数据

**地址管理**

​	包含呼号（Call_Sign）、姓名（Name）、电话（Telephone）、邮编（Postal_Code）、收件地址（Address）、电子邮件（E-mail）、地址备注（Address_Remarks）等字段

**卡片局管理**

​	包含卡片局名名称（复用Call_Sign字段）、电话（Telephone，选填）、邮编（Postal_Code）、收件地址（Address）、地址备注（Address_Remarks）等字段

**设备库维护**

​	包括：设备（RIG）、天线（ANT）、功率（PWR）、模式（MODE）等字段，每个字段可存放若干行内容

**导入导出**

​	页面内拆分为两个功能板块：

​	**导入板块**：用于批量写入“通联记录”、“卡片记录”、“换卡申请”、“地址管理”、“卡片局管理”、“设备库”等数据

​	**导出板块**：支持“单独导出”和“全部导出”。单独导出为对应数据集的 CSV 文件；全部导出为包含全部数据集 CSV 文件的 ZIP 压缩包。

------

## 3. 前台卡片

前台卡片均为开放接口，接受匿名用户的访问，受到“游客每分钟查询次数”的频率限制

### 3.1 数据总览

​	包括卡片型的数据总览（同后台“总览”一致）和数据表格，表格包括目标呼号（Call_Sign）、卡片类型（Card_Type）、发卡时间、收卡时间（无记录则显示“未收到回卡”）。支持输入呼号、时间、卡片类型等实现过滤

### 3.2 换卡申请

​	前台输入呼号（Call_Sign）、是否使用卡片局（下拉选项）、电子邮件（E-mail）、换卡备注（Eyeball_Remarks）等字段

​	如果选择不使用卡片局，则需输入 姓名（Name）、电话（Telephone）、邮编（Postal_Code）、收件地址（Address）

​	如果选择使用卡片局，则需在下拉选项当中选择卡片局名称，选择后自动拉取电话（Telephone，选填）、邮编（Postal_Code）、收件地址（Address）等字段

​	提交申请后将发送到后台用于审核

### 3.3 卡片签收

​	前台输入呼号（Call_Sign）、卡片编号（Card_ID）、签收备注（Receipt_Remarks，选填），点击 确认签收 按钮，将数据传递给后台，后台做出判断后如果通过则变更“发卡签收”的值为“True”，并回传签收成功。否则回传签收失败，卡片和呼号不匹配。

## 4. 角色模型与权限节点

### 4.1 角色模型

- 游客：仅可做公开呼号查询与公开报表查看。
- HAM 用户：在通过呼号审核后，可查看本人相关记录并发起换卡/补卡申请。
- 操作员：执行录入、导出、发信确认、收信确认等日常业务。
- 管理员：拥有配置、审核、审计、统计和权限分配治理能力。

> 一期实施范围说明：当前仅落地管理员（含超级管理员）相关后台权限能力；HAM 用户与操作员角色暂不启用，作为二期能力预留。

## 4.2 权限节点

### 4.2.1 设计原则

1. 权限节点按“业务能力”设计：每个能力至少包含只读 `view` 与编辑 `edit` 两个节点。
2. 权限节点命名统一为 `plugin:qsl-management:<slug>:<action>`，其中 `<action>` 仅允许 `view` 或 `edit`。
3. 多个菜单可复用同一组权限节点（仅拆分菜单展示，不强制新增节点）。
4. `edit` 默认依赖同项 `view`，并可附加其他前置依赖。
5. 跨模块依赖优先依赖对方的 `view` 节点；确需跨模块写操作时才依赖对方 `edit` 节点。
6. 本期（一期）仅启用管理员相关授权，HAM 用户、操作员等角色不落地实现。
7. 本节节点在一期先完成预留与定义，二期再按角色模型分配与启用。

### 4.2.2 菜单权限节点（当前实现）

| 菜单项（按你给出的行） | 只读节点 | 编辑节点 | 只读依赖 | 编辑依赖 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 总览看板 | `plugin:qsl-management:overview-dashboard:view` | `plugin:qsl-management:overview-dashboard:edit` | `plugin:qsl-management:qso-record:view`,`plugin:qsl-management:card-record:view`,`plugin:qsl-management:mail-send-confirm:view`,`plugin:qsl-management:mail-receive-confirm:view` | `plugin:qsl-management:overview-dashboard:view` | 总览依赖业务数据读取 |
| 系统参数 | `plugin:qsl-management:system-settings:view` | `plugin:qsl-management:system-settings:edit` | 无 | `plugin:qsl-management:system-settings:view` | 独立配置项 |
| 通信地址 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:station-profile:view`,`plugin:qsl-management:equipment-catalog:view` | 与“本台设备/本台卡片”共用同一权限节点（仅拆分菜单） |
| 本台设备 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:station-profile:view`,`plugin:qsl-management:equipment-catalog:view` | 与“通信地址/本台卡片”共用同一权限节点（仅拆分菜单） |
| 本台卡片 | `plugin:qsl-management:station-profile:view` | `plugin:qsl-management:station-profile:edit` | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:station-profile:view`,`plugin:qsl-management:equipment-catalog:view` | 与“通信地址/本台设备”共用同一权限节点（仅拆分菜单） |
| 通联记录 | `plugin:qsl-management:qso-record:view` | `plugin:qsl-management:qso-record:edit` | `plugin:qsl-management:equipment-catalog:view`,`plugin:qsl-management:station-profile:view` | `plugin:qsl-management:qso-record:view` | 录入时使用本台配置与设备候选 |
| 卡片记录 | `plugin:qsl-management:card-record:view` | `plugin:qsl-management:card-record:edit` | `plugin:qsl-management:qso-record:view`,`plugin:qsl-management:station-profile:view` | `plugin:qsl-management:card-record:view`,`plugin:qsl-management:qso-record:view` | 可关联 QSO，依赖本台卡片版本读取 |
| 发信确认 | `plugin:qsl-management:mail-send-confirm:view` | `plugin:qsl-management:mail-send-confirm:edit` | `plugin:qsl-management:card-record:view` | `plugin:qsl-management:mail-send-confirm:view`,`plugin:qsl-management:card-record:view` | 发信对象来源于卡片记录 |
| 收信确认 | `plugin:qsl-management:mail-receive-confirm:view` | `plugin:qsl-management:mail-receive-confirm:edit` | `plugin:qsl-management:card-record:view`,`plugin:qsl-management:qso-record:view` | `plugin:qsl-management:mail-receive-confirm:view`,`plugin:qsl-management:card-record:view`,`plugin:qsl-management:qso-record:view` | 收信匹配依赖卡片/通联读取 |
| 换卡申请 | `plugin:qsl-management:exchange-request-review:view` | `plugin:qsl-management:exchange-request-review:edit` | `plugin:qsl-management:address-bureau:view`,`plugin:qsl-management:card-record:view` | `plugin:qsl-management:exchange-request-review:view`,`plugin:qsl-management:card-record:edit` | 审批通过涉及创建卡片记录 |
| 通联记录查询 | `plugin:qsl-management:qso-query:view` | `plugin:qsl-management:qso-query:edit` | `plugin:qsl-management:qso-record:view` | `plugin:qsl-management:qso-query:view` | 查询菜单编辑节点一期预留 |
| 卡片记录查询 | `plugin:qsl-management:card-query:view` | `plugin:qsl-management:card-query:edit` | `plugin:qsl-management:card-record:view` | `plugin:qsl-management:card-query:view` | 查询菜单编辑节点一期预留 |
| 统计报表 | `plugin:qsl-management:report-auditlog:view` | `plugin:qsl-management:report-auditlog:edit` | `plugin:qsl-management:qso-query:view`,`plugin:qsl-management:card-query:view`,`plugin:qsl-management:exchange-request-review:view`,`plugin:qsl-management:mail-send-confirm:view`,`plugin:qsl-management:mail-receive-confirm:view` | `plugin:qsl-management:report-auditlog:view` | 与“审计日志”共用同一权限节点（仅拆分菜单） |
| 审计日志 | `plugin:qsl-management:report-auditlog:view` | `plugin:qsl-management:report-auditlog:edit` | `plugin:qsl-management:qso-query:view`,`plugin:qsl-management:card-query:view`,`plugin:qsl-management:exchange-request-review:view`,`plugin:qsl-management:mail-send-confirm:view`,`plugin:qsl-management:mail-receive-confirm:view` | `plugin:qsl-management:report-auditlog:view` | 与“统计报表”共用同一权限节点（仅拆分菜单） |
| 地址管理 | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` | 无 | `plugin:qsl-management:address-bureau:view` | 与“卡片局管理”共用同一权限节点（仅拆分菜单） |
| 卡片局管理 | `plugin:qsl-management:address-bureau:view` | `plugin:qsl-management:address-bureau:edit` | 无 | `plugin:qsl-management:address-bureau:view` | 与“地址管理”共用同一权限节点（仅拆分菜单） |
| 设备库维护 | `plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:equipment-catalog:edit` | 无 | `plugin:qsl-management:equipment-catalog:view` | 基础主数据维护 |
| 导入导出 | `plugin:qsl-management:import-export:view` | `plugin:qsl-management:import-export:edit` | `plugin:qsl-management:qso-query:view`,`plugin:qsl-management:card-query:view`,`plugin:qsl-management:exchange-request-review:view`,`plugin:qsl-management:address-bureau:view`,`plugin:qsl-management:equipment-catalog:view` | `plugin:qsl-management:import-export:view`,`plugin:qsl-management:qso-record:edit`,`plugin:qsl-management:card-record:edit`,`plugin:qsl-management:exchange-request-review:edit`,`plugin:qsl-management:address-bureau:edit`,`plugin:qsl-management:equipment-catalog:edit` | 单一菜单，页内拆分导入/导出两个板块 |

### 4.2.3 权限节点复用关系（避免歧义）

| 权限节点（view/edit） | 被哪些菜单复用 |
| --- | --- |
| `station-profile` | 通信地址、本台设备、本台卡片 |
| `report-auditlog` | 统计报表、审计日志 |
| `address-bureau` | 地址管理、卡片局管理 |
| `import-export` | 导入导出（页内导入板块、导出板块） |

### 4.2.4 一期与二期启用策略

1. 一期启用：后台仅管理员/超级管理员使用上述节点；普通游客前台访问走匿名接口与限流策略，不走后台 RBAC 分配。
2. 一期不启用：HAM 用户、操作员角色及其差异化授权暂不实现，仅保留角色定义与权限预留。
3. 二期计划：基于本节节点给 HAM 用户、操作员做正式授权编排；如需更细粒度，在对应节点下再拆分子权限。


---




