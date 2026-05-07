# 业余无线电 QSL 卡片管理系统

业余无线电 QSL 卡片管理系统是一个面向 Halo 2 的插件项目，用于管理 QSO 记录、QSL 收发卡、线上 EYEBALL 换卡、线下 EYEBALL 换卡、收卡编号、公开查询、邮件通知、数据备份和本地打印桥接等流程。

当前插件标识名为 `qsl-management`，插件显示名称为“业余无线电 QSL卡片管理系统”。

## 主要能力

- 通联业务：维护 QSO/SWL 记录，创建卡片，制卡签发，发信确认，送达确认。
- <img width="3429" height="1665" alt="image" src="https://github.com/user-attachments/assets/77d2854b-9ff4-49e7-a930-55f034a96613" />
- 线上换卡业务：公开提交换卡申请，后台审核，自动创建卡片，制卡、发卡、送达流转。
- <img width="1116" height="1350" alt="image" src="https://github.com/user-attachments/assets/b24a1d1e-c687-479d-8067-4e0928c7b2fd" />
- 线下换卡业务：创建活动，批量创建活动卡片，线下制卡，公开确认，送达确认。
- <img width="3426" height="966" alt="image" src="https://github.com/user-attachments/assets/400245aa-ff99-4c24-aa5a-a3da2178457d" />
- 收卡业务：通联收卡、线上换卡收卡、线下换卡收卡，支持收卡编号、收卡日期、编号迁移和已收卡片清单。
- 配置管理：系统参数、通知策略、通信地址、本台设备、本台卡片、卡片局、地址和设备库。
- <img width="3431" height="1839" alt="image" src="https://github.com/user-attachments/assets/e9a49343-e0ed-4c8f-8ef3-b3ac0073142b" />
- 公开页面：公开查询、线上换卡申请、线下换卡确认、公开签收。
- <img width="1128" height="1116" alt="image" src="https://github.com/user-attachments/assets/b3311a10-37d8-44de-93ef-39a1c1fb7baa" />
- 邮件通知：按业务场景配置自动或手动通知，支持测试邮件。
- <img width="1938" height="990" alt="image" src="https://github.com/user-attachments/assets/735c13d0-212d-4052-ac9a-f7470f9bb3ab" />
- 数据能力：审计日志、统计报表、备份导入导出。
- <img width="1389" height="891" alt="image" src="https://github.com/user-attachments/assets/d2550617-f7a9-4173-a725-9b9b5c353a9d" />
- 本地打印工具：通过 `tools/CardPrint` 对接插件接口，支持卡片、信封、打包确认和二维码路径映射。

## 插件安装

从 GitHub Release 下载最新的插件 JAR 文件：

```text
plugin-qsl-management-*.jar
```

然后在 Halo 后台进入插件管理，上传并启用该 JAR。

Release 地址：

```text
https://github.com/bi1kbu/qsl-management/releases
```

## 后台入口

插件启用后，后台菜单入口为：

```text
/qsl
```

当前菜单包含：

- 总览
- 配置
- 通联业务
- 线上换卡业务
- 线下换卡业务
- 收卡业务
- 审计
- 数据

## 公开页面

插件提供以下公开页面与接口能力（建议通过带参数重定向或反向代理缩短链接使用）：

| 能力 | 默认路径 |
| --- | --- |
| 公开查询 | `/apis/api.qsl-management.halo.run/v1alpha1/cards/page` |
| 线上换卡申请 | `/apis/api.qsl-management.halo.run/v1alpha1/ONLINE_EYEBALL` |
| 线下换卡确认 | `/apis/api.qsl-management.halo.run/v1alpha1/EYEBALL` |
| 公开签收 | `/apis/api.qsl-management.halo.run/v1alpha1/receipt-public` |

公开页面也支持通过 Halo 内容短码嵌入：

```text
[qsl-card]
[qsl-online-exchange-card]
[qsl-offline-exchange-card]
[qsl-receipt-card]
```

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

打印工具需要配置站点地址、认证方式和打印预设。若使用个人访问令牌，建议在 Halo 中为令牌勾选插件提供的“卡片打印工具”权限模板。

## 开发环境

插件开发环境：

- Java 21+
- Node.js 18+
- pnpm
- Halo 2.23+

构建插件：

```powershell
cd qsl-plugin
.\gradlew.bat build
```

构建完成后，JAR 产物位于：

```text
qsl-plugin/build/libs
```

本地启动 Halo 联调：

```powershell
cd qsl-plugin
.\gradlew.bat haloServer
```

前端开发：

```powershell
cd qsl-plugin/ui
pnpm install
pnpm dev
```

## 项目结构

```text
qsl-plugin/     Halo 插件后端、控制台前端、扩展模型、权限模板
tools/CardPrint 本地卡片打印工具
docs/spec       项目信息结构化文档
```

关键文档：

- `ProductDefinition.md`：产品定义与业务流程
- `BackendApiContract.md`：后台、公开 API 合同
- `docs/spec/项目信息结构化清单.md`：结构化项目索引

## 版本说明

插件版本以以下两个文件为准：

```text
qsl-plugin/src/main/resources/plugin.yaml
qsl-plugin/gradle.properties
```

正式打包前需同步递增两处版本号。

## 许可证

本项目使用 GPL-3.0 许可证。

