# QSL Management Plugin

基于 Halo 2 的 QSL 卡片管理插件，覆盖通联记录、卡片记录、发收确认、前台查询、换卡申请、呼号绑定、审计日志和统计报表。

## 本地开发环境

1. JDK 21
2. Node 20
3. pnpm 9
4. Docker Desktop（推荐）

## 常用命令

```bash
# Windows
./gradlew.bat test
./gradlew.bat build
./gradlew.bat haloServer
./gradlew.bat reloadPlugin
```

```bash
# macOS / Linux
./gradlew test
./gradlew build
./gradlew haloServer
./gradlew reloadPlugin
```

## 开发模式说明

`haloServer` 任务会启动本地 Halo 开发容器并加载当前插件工程，适合快速联调。

如果你使用源码方式运行 Halo，请在 Halo 配置中启用开发模式并指定插件目录：

```yaml
halo:
  plugin:
    runtime-mode: development
    fixedPluginPath:
      - "/path/to/qsl-plugin"
```

## 前台卡片路由

1. 换卡申请页面：`/plugins/qsl-management/widgets/exchange`
2. 收信确认页面：`/plugins/qsl-management/widgets/receive-confirm`
3. 查询：`/plugins/qsl-management/widgets/query`
4. 补卡：`/plugins/qsl-management/widgets/reissue`
5. 统计：`/plugins/qsl-management/widgets/stats`

## 接口前缀

1. 后台管理接口：`/apis/qsl.admin/v1`
2. 前台公开接口：`/apis/qsl.public/v1`
3. 小组件兼容接口：`/plugins/qsl-management/widgets/public-api`

说明：当前前台换卡/收信卡片默认走兼容接口（GET），对应：

1. `/plugins/qsl-management/widgets/public-api/actions/exchange-request`
2. `/plugins/qsl-management/widgets/public-api/actions/receive-confirm`

## 当前实现状态

1. 已实现控制台菜单与 UC 菜单、权限节点、HAM 审核赋权。
2. 已实现通联记录、卡片记录、发信确认、收信确认、换卡审核、呼号绑定审核。
3. 已实现前台卡片（查询/换卡/补卡/收信确认/统计）与编辑器插入扩展。
4. 已实现导入导出、备份导出、审计日志筛选、统计报表、邮件通知。
5. 数据层已接入 Halo Extension 自定义模型持久化（`QslStateStore`），业务数据会在 Halo 数据库中长期保存。
