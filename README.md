# QSL Management Plugin

基于 Halo 2 的 QSL 卡片管理插件，覆盖通联记录、卡片记录、发收确认、前台查询、换卡申请、审计日志和统计报表。

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
```

```bash
# macOS / Linux
./gradlew test
./gradlew build
./gradlew haloServer
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

## 接口前缀

1. 后台接口：`/apis/qsl.admin/v1`
2. 前台公开接口：`/apis/qsl.public/v1`
3. HAM 用户接口：`/apis/qsl.user/v1`

## 当前实现状态

1. 已完成插件骨架、核心路由、接口骨架、审计日志筛选接口和报表接口。
2. 已完成前端控制台子菜单占位页面。
3. 已完成本地构建与单元测试链路。
4. 数据持久化当前为内存服务实现，后续可切换为正式存储层。
