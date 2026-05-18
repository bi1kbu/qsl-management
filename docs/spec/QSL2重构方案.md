# QSL 2.0.0 重构方案

更新时间：2026-05-18

目标版本：2.0.0

Halo 官方资料核验日期：2026-05-18

## 1. 官方依据

1. Halo 插件 Manifest  
   https://docs.halo.run/developer-guide/plugin/basics/manifest
2. Halo Extension、自定义模型与索引  
   https://docs.halo.run/developer-guide/plugin/api-reference/server/extension
3. Halo API 权限控制与角色模板  
   https://docs.halo.run/developer-guide/plugin/security/role-template
4. Halo RBAC 与匿名聚合角色  
   https://docs.halo.run/developer-guide/plugin/security/rbac
5. Halo 插件 API 变更日志  
   https://docs.halo.run/developer-guide/plugin/api-changelog/

当前实现继续使用 Halo Extension、CustomEndpoint、Role 模板与匿名聚合角色；新增模型注册遵循 `IndexSpecs.single` 方式创建 `metadata.name` 索引。

## 2. 问题定义

当前 `CardRecord` 同时承担以下职责：

1. 我方发出的卡片。
2. 我方收到的卡片。
3. 线下换卡空呼号占位卡。
4. 线上换卡卡片。
5. 通联卡片。
6. 收卡编号容器。
7. 邮件通知状态容器。

这导致不同业务场景通过 `sceneType`、空呼号、状态联动和临时卡片互相兼容。线下换卡中，对方不扫码填写呼号时，收卡操作会额外创建新卡片，造成卡片编号被浪费、发卡与收卡统计偏离实际、后续合并成本上升。

## 3. 重构原则

1. 一个模型只承载一种业务生命周期。
2. 发出卡片与收回卡片是两个独立事实。
3. 收卡时只能创建收卡记录，不能为了收卡创建新的发卡编号。
4. 发出卡片与收回卡片通过可选关联连接，不强制一对一。
5. 前端可以复用基础组件，但页面级业务逻辑不再依赖统一 `sceneType` 开关。
6. 统计按业务事实聚合：发卡来自发卡模型，收卡来自收卡模型，闭环来自关联关系。
7. 新增后台能力必须有服务端鉴权，权限至少区分 `view` 与 `edit`。

## 4. 数据边界

### 4.1 发出卡片

发出卡片代表“我方实际准备或已经发出的卡片”。现阶段保留既有 `CardRecord` 承载通联与线上换卡发卡流程，后续再拆分为更细模型。

线上换卡发卡流程中的“送达确认”不再作为收卡事实处理，业务语义调整为“确认签收/签收状态”，即记录对方是否签收我方寄出的卡片；签收事实落点为 `CardRecord.spec.receiptConfirmed`。

2.0.0 第一阶段新增 `OfflineExchangeCard`，专门承载线下换卡活动卡：

| 字段 | 说明 |
| --- | --- |
| `cardRecordName` | 关联的旧卡片编号；过渡期用于保留实体卡片 ID |
| `offlineActivityName` | 线下活动资源名 |
| `callSign` | 对方呼号；未填写代表待认领 |
| `cardType` | 固定以 `EYEBALL` 为主，保留扩展空间 |
| `cardVersion` | 本台卡片版本 |
| `claimStatus` | `待认领`、`已认领`、`人工绑定` |
| `sentStatus` | `待发出`、`已发出` |
| `sentAt` | 发出时间 |
| `remarks` | 线下业务备注 |

线下换卡卡片不绑定通信地址或卡片局地址，不写入 `CardRecord.spec.addressEntryName`；线下活动交付不依赖收件地址展示或信封打印。

### 4.2 收回卡片

新增 `ReceiveRecord`，专门承载“我方收到对方卡片”的事实。

| 字段 | 说明 |
| --- | --- |
| `metadata.name` | 收卡编号，如 `R0001-20260517` |
| `callSign` | 对方呼号 |
| `cardType` | `QSO`、`SWL`、`EYEBALL` |
| `businessType` | `QSO`、`SWL`、`ONLINE_EYEBALL`、`OFFLINE_EYEBALL`、`UNKNOWN` |
| `offlineActivityName` | 线下活动资源名，可为空 |
| `receivedAt` | 收卡时间 |
| `receivedDate` | 收卡日期 |
| `outboundCardNames` | 关联的发出卡片编号列表，可为空 |
| `matchStatus` | `未匹配`、`自动匹配`、`人工匹配`、`已解除` |
| `matchReason` | `卡片ID`、`活动呼号`、`呼号场景`、`人工选择`、`未找到发卡记录` |
| `remarks` | 收卡备注 |

收卡业务的收卡编号来源统一为 `ReceiveRecord.metadata.name`。旧 `CardRecord.spec.receivedRecordCodes` 仅作为迁移期兼容字段，不作为新收卡编号、查询、统计或导入导出的主数据来源。

## 5. 发出与收回的关联关系

发出卡片与收回卡片为可选多对多关系。系统默认交互按“一张收卡记录关联一张发卡记录”处理，异常场景允许人工调整。

推荐关联落点：

```text
ReceiveRecord.spec.outboundCardNames = ["C1001"]
```

原因：

1. 收卡动作是关联发生的时点。
2. 避免在发卡模型和收卡模型之间双写造成不一致。
3. 发卡详情需要展示收卡关系时，由服务端聚合查询。

匹配优先级：

1. 卡片 ID：二维码、公开确认链接或后台指定卡片编号。
2. 线下活动 + 呼号 + 卡片类型：优先绑定同活动下待认领或未完成闭环的线下卡。
3. 场景 + 呼号 + 卡片类型：匹配通联或线上换卡发卡记录。
4. 无法可靠匹配：只创建 `ReceiveRecord`，状态为 `未匹配`，不创建发卡记录。

## 6. 统计口径

1. 发卡数：统计正式 `C{序号}` 且呼号非空的发卡模型，不从收卡记录反推；待发卡片仅统计仍需发卡处理的非线上换卡积压，并排除已签收、已收卡或已经被 `ReceiveRecord.outboundCardNames` 闭环关联的记录；已发卡片统计明确已发卡、已签收、有发卡时间的记录，线上换卡中已制卡、已打包且制卡通知已发送的历史记录也纳入已发。
2. 收卡数：统计 `ReceiveRecord`，按呼号去重；呼号为空时按关联发卡 ID 去重。
3. 换卡闭环数：统计已关联发卡记录的 `ReceiveRecord`。
4. 未匹配收卡：统计 `ReceiveRecord.matchStatus=未匹配`。
5. 线下待认领：统计 `OfflineExchangeCard.claimStatus=待认领`。
6. 线下占位卡不进入发卡、收卡、眼球总数。
7. 线上换卡签收状态统计以 `CardRecord.spec.receiptConfirmed` 为准，不计入收卡编号统计。

## 7. 2.0.0 第一阶段实施范围

1. 新增 `ReceiveRecord` Extension。
2. 新增 `OfflineExchangeCard` Extension。
3. 线下收卡改为优先创建 `ReceiveRecord`，并尝试绑定已有线下发卡记录；找不到时不再新建 `CardRecord`。
4. 总览的收卡统计改从 `ReceiveRecord` 聚合，并保留旧数据兜底用于迁移期核验。
5. 新增或调整 RBAC 模板，覆盖 `receive-records` 与 `offline-exchange-cards`。
6. 更新 `plugin.yaml` 与 `gradle.properties` 到 `2.0.0`。
7. 同步更新结构化文档与 API 合同。
8. 审计卡片记录查询与收卡记录查询按业务场景 tab 展示，卡片记录查询读取 `CardRecord`，收卡记录查询读取 `ReceiveRecord`。

## 7.1 旧导出包转换

2.0.0 前导出的 CSV ZIP 中，收卡事实和线下换卡活动卡关系分散存放在 `card-record.csv` 内。导入到 2.0.0 前必须先执行 `tools/convert_legacy_export_to_qsl2.py`：

1. 按 `receivedRecordCodes` 聚合旧收卡关系，生成 `receive-record.csv`，并将可识别的发卡卡片编号写入 `outboundCardNames`。
2. 将旧版“自动创建EYEBALL卡片”的收卡占位记录转为未匹配 `ReceiveRecord`，并从 `card-record.csv` 中移除，避免继续占用发卡编号。
3. 按线下 `offlineActivityName` 提取活动卡，生成 `offline-exchange-card.csv`。
4. 清理保留 `card-record.csv` 中已经迁出的收卡字段，收卡统计与闭环关系以 `ReceiveRecord` 为准。
5. 同步修正 `system-setting.csv` 中的卡片编号与收卡编号序列，避免导入后继续从旧序号分配。

## 8. API 合同基线

### 8.1 扩展资源

| 资源 | Kind | Plural | 权限节点 |
| --- | --- | --- | --- |
| 线下换卡卡片 | `OfflineExchangeCard` | `offline-exchange-cards` | `offline-exchange-card` |
| 收卡记录 | `ReceiveRecord` | `receive-records` | `receive-record`、`mail-receive-confirm` |

### 8.2 控制台动作接口

继续保留现有接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/mail-receive-confirms/confirm` | 创建收卡记录并尝试关联发卡记录 |
| `POST` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/receipt-confirms/{cardRecordName}/confirm` | 控制台确认对方签收我方发出的卡片 |

返回体保持兼容字段，但 `cardRecordName` 在 2.0.0 起表示“关联到的发卡记录编号”；无法匹配时为空。`receivedRecordCode` 为新建 `ReceiveRecord.metadata.name`。

线上换卡“签收确认”相关接口或页面只更新 `CardRecord.spec.receiptConfirmed/publicReceiptRemarks`，不创建 `ReceiveRecord`，也不分配 `receivedRecordCode`。

## 9. 安全设计

1. 控制台收卡接口必须登录并具备 `mail-receive-confirm:edit`。
2. `receive-records` 只读归入 `mail-receive-confirm:view`、`card-query:view` 与新增 `receive-record:view`。
3. `receive-records` 创建与更新归入 `mail-receive-confirm:edit` 与新增 `receive-record:edit`。
4. 公开接口不直接写入 `ReceiveRecord`，除非后续明确设计公开签收与收卡事实的关系。
5. 所有关联变更必须追加审计日志。

## 10. 偏离与回退

1. 第一阶段不会立即移除旧 `CardRecord.cardReceived/receivedRecordCodes` 字段，原因是现有前端与打印工具仍读取这些字段。风险是迁移期存在双口径；回退方案是总览继续按旧字段统计。
2. 第一阶段不一次性拆分通联发卡与线上换卡发卡模型，原因是线下换卡与收卡偏差最严重。风险是 `CardRecord` 仍有部分历史耦合；回退方案是保持现有通联与线上换卡流程不变。
3. 第一阶段新增模型后，历史数据迁移需单独执行导入或修复任务，避免自动迁移误合并实体卡片编号。
