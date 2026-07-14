# 业余无线电 QSL 卡片管理系统

业余无线电 QSL 卡片管理系统是一个面向 Halo 2 的 QSL 业务插件，用于把通联记录、卡片创建、制卡签发、发信确认、送达确认、收卡编号、公开查询、线上换卡、线下换卡、邮件通知、数据备份和本地打印桥接整合到同一套可持久化流程中。

它适合需要长期维护 QSO/SWL 记录、管理多版本 QSL 卡片、处理线上与线下换卡、追踪寄送与签收状态的个人电台、俱乐部或活动组织者。插件运行在 Halo 后台，公开页面可嵌入站点内容页，也可作为独立链接分发给对方台站使用。

当前插件标识为 `qsl-management`，插件显示名称为“业余无线电 QSL卡片管理系统”。

## 产品定位

传统 QSL 管理往往散落在表格、邮件、聊天记录和打印工具中。这个插件的目标是把“记录一次通联”到“完成收发卡闭环”的过程串起来，让每张卡片都有明确来源、状态、编号、备注、审计记录和后续动作。

系统围绕四类核心业务展开：

- 通联业务：从 QSO/SWL 记录出发，完成创建卡片、制卡、发信和送达确认。
- 线上换卡业务：让对方台站通过公开页面提交换卡申请，后台审核后自动生成卡片并进入后续流程。
- 线下换卡业务：面向 EYEBALL 活动创建批量卡片，支持活动现场确认与后续送达管理。
- 收卡业务：统一管理通联、线上换卡、线下换卡的收卡编号、收卡日期和已收卡清单。

## 核心能力

### 业务闭环

- 支持 QSO、SWL、ONLINE_EYEBALL、EYEBALL 多业务场景。
- 支持从通联日志或换卡申请创建卡片，并在同一条业务线上追踪制卡、打包、发信、签收和收卡。
- 支持正式卡片编号与收卡编号，便于纸质卡片归档、查询和后续追溯。
- 支持批量编辑、状态联动、备注记录和关键动作审计。

### 线上换卡

- 提供公开换卡申请页面，对方台站可选择本台卡片版本并填写个人地址或卡片局地址。
- 后台可审核通过或拒绝申请，审核通过后自动创建线上换卡卡片。
- 支持同一呼号待审核申请限制，避免重复提交。
- 可按业务策略发送审核结果、制卡、发卡和收卡邮件通知。

### 线下换卡

- 支持创建线下活动，并为活动批量创建 EYEBALL 卡片。
- 支持公开线下换卡确认页面，通过卡片编号或二维码完成回填与确认。
- 线下换卡公开页面不会直接暴露本站通信地址，提交成功后才返回必要寄送信息。
- 线下收卡可按活动归属进行匹配，适合活动现场和活动后整理。

### 公开页面与短码

插件提供可匿名访问的公开查询、换卡申请、线下确认和签收页面，并支持通过 Halo 内容短码嵌入文章或页面。

| 能力 | 默认路径 | 短码 |
| --- | --- | --- |
| 公开查询 | `/apis/api.qsl-management.bi1kbu.com/v1alpha1/cards/page` | `[qsl-card]` |
| 线上换卡申请 | `/online_eyeball` | `[qsl-online-exchange-card]` |
| 线下换卡确认 | `/eyeball` | `[qsl-offline-exchange-card]` |
| 公开签收 | `/receipt_public` | `[qsl-receipt-card]` |

公开接口允许匿名访问，但服务端仍会执行输入校验、限流和业务规则校验。

长度敏感场景还可以使用极简别名：线下换卡 `/eb`、线上换卡 `/oe`、公开签收 `/rp`，并支持对应的 `/{cardId}` 子路径。短码仍生成上表中语义更清晰的推荐路径；CardPrint 的收卡回执二维码默认使用 `/rp`，以缩短内容并生成更小的二维码。

自插件 2.4.0 起，以上根路径作为推荐页面入口，服务器内部复用原有 Halo CustomEndpoint，不产生浏览器重定向。原 `/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL`、`ONLINE_EYEBALL` 和 `receipt-public` 页面路由进入弃用期，计划在插件 3.0.0 移除；移除前必须先将推荐路径和极简路径改为直接调用公共页面处理服务，解除其对原 CustomEndpoint 路由的依赖。公开数据提交 API 不在本次弃用范围内。

### 数据与安全

- 数据基于 Halo Extension 持久化，覆盖通联记录、卡片记录、换卡申请、线下活动、地址簿、卡片局、设备库、系统参数、审计日志和导入导出任务。
- 后台功能通过 Halo RBAC 授权，权限模型至少区分 `view` 和 `edit`。
- 服务端鉴权是安全边界，前端权限隐藏仅用于导航和交互展示。
- 支持导入导出、统计报表、审计日志和业务数据备份。

### 本地打印桥接

仓库内置 `tools/CardPrint` 本地打印工具，可对接插件接口完成卡片、信封、二维码路径映射、打包确认和状态回写。打印工具适合在本地电脑连接打印机使用，同时把关键打印状态同步回 Halo 插件。

若使用 Halo 个人访问令牌，建议为令牌勾选插件提供的“卡片打印工具”权限模板。

## 界面预览

### 通联业务

<img width="3429" height="1665" alt="通联业务界面预览" src="https://github.com/user-attachments/assets/77d2854b-9ff4-49e7-a930-55f034a96613" />

### 线上换卡

<img width="1116" height="1350" alt="线上换卡公开页面预览" src="https://github.com/user-attachments/assets/b24a1d1e-c687-479d-8067-4e0928c7b2fd" />

### 线下换卡

<img width="3426" height="966" alt="线下换卡业务界面预览" src="https://github.com/user-attachments/assets/400245aa-ff99-4c24-aa5a-a3da2178457d" />

### 配置管理

<img width="3431" height="1839" alt="配置管理界面预览" src="https://github.com/user-attachments/assets/e9a49343-e0ed-4c8f-8ef3-b3ac0073142b" />

### 公开查询

<img width="1128" height="1116" alt="公开查询页面预览" src="https://github.com/user-attachments/assets/b3311a10-37d8-44de-93ef-39a1c1fb7baa" />

### 邮件通知

<img width="1938" height="990" alt="邮件通知界面预览" src="https://github.com/user-attachments/assets/735c13d0-212d-4052-ac9a-f7470f9bb3ab" />

### 数据统计

<img width="1389" height="891" alt="数据统计界面预览" src="https://github.com/user-attachments/assets/d2550617-f7a9-4173-a725-9b9b5c353a9d" />

## 安装使用

从 GitHub Release 下载最新插件 JAR：

```text
plugin-qsl-management-*.jar
```

Release 地址：

```text
https://github.com/bi1kbu/qsl-management/releases
```

在 Halo 后台进入插件管理，上传并启用该 JAR。插件启用后，后台入口为：

```text
/qsl
```

当前插件声明需要 Halo `>=2.23.0`。

## 后台功能地图

插件后台当前包含以下菜单：

- 总览：查看业务概况、待处理数量和关键统计。
- 配置：维护系统参数、通信地址、本台设备和本台卡片版本。
- 通联业务：维护通联日志、创建卡片、制卡签发、发信确认和送达确认。
- 线上换卡业务：审核换卡申请，通过单条、批量或 BH6SYX 卡片广场导入数据，并完成线上换卡后续流程。
- 线下换卡业务：创建活动、创建卡片、制卡签发和送达确认。
- 收卡业务：处理通联收卡、线上换卡收卡和线下换卡收卡。
- 审计：查询通联记录、卡片记录、收卡记录、统计报表和审计日志。
- 数据：处理卡片异动、地址管理、卡片局管理、设备库维护和导入导出。

## 本地打印工具

本地打印工具位于：

```text
tools/CardPrint
```

常用启动命令：

```powershell
cd tools/CardPrint
python -m cardprint.cli ui online
```

打印工具需要配置站点地址、认证方式和打印预设。它会通过插件接口读取卡片、地址、本台资料和卡片版本，并在人工确认后回写制卡、打包等状态。

## 开发者信息

项目主要结构：

```text
src/             Halo 插件后端、扩展模型、资源清单与权限模板
ui/              Halo 控制台前端
tools/CardPrint  本地卡片打印工具
docs/spec        项目信息结构化文档
```

开发环境：

- Java 21+
- Node.js 18+
- pnpm
- Halo 2.23+

常用命令：

```powershell
.\gradlew.bat build
.\gradlew.bat haloServer
```

前端开发：

```powershell
cd ui
pnpm install
pnpm dev
```

关键文档：

- `docs/spec/ProductDefinition.md`：产品定义与业务流程。
- `docs/spec/BackendApiContract.md`：后台与公开 API 合同。
- `docs/spec/项目信息结构化清单.md`：结构化项目索引。

插件版本以 `src/main/resources/plugin.yaml` 与 `gradle.properties` 为准，正式打包前需同步递增两处版本号。

## 许可证

本项目使用 GPL-3.0 许可证。
