# Halo 非资源型 API 待核查问题

状态：待处理（仅记录，尚未完成运行验证与整改）

记录日期：2026-07-13

适用目标版本：项目当前 Halo `2.23`；同时参考 Halo `2.25` 最新官方文档核对规则变化。

## 1. 问题背景

Halo 的 RBAC 规则将 API 分为“资源型”和“非资源型”两类。插件自定义 API 不会天然归入非资源型，最终分类取决于完整请求路径是否符合 Halo 资源型 API 的路径结构。

官方定义的资源型 API 最多包含以下层级：

```text
/apis/<group>/<version>/<resource>
/apis/<group>/<version>/<resource>/<resourceName>
/apis/<group>/<version>/<resource>/<resourceName>/<subresource>
```

凡是不符合上述结构的 API，官方文档将其归类为非资源型 API。非资源型权限应使用 `nonResourceURLs` 与 `verbs` 配置，而不是只配置 `apiGroups`、`resources` 与 `verbs`。

## 2. 官方来源

核验日期：2026-07-13

1. Halo 2.23 API 权限控制：<https://docs.halo.run/2.23/developer-guide/plugin/security/role-template>
2. Halo 2.25 API 权限控制：<https://docs.halo.run/developer-guide/plugin/security/role-template>
3. Halo RESTful API 说明：<https://docs.halo.run/developer-guide/restful-api/introduction>

官方非资源型权限示例：

```yaml
rules:
  - nonResourceURLs:
      - "/healthz"
      - "/healthz/*"
    verbs:
      - get
      - create
```

## 3. 当前项目代码事实

当前项目通过 `CustomEndpoint` 注册控制台和公开自定义 API：

1. 控制台 API group：`console.api.qsl-management.bi1kbu.com/v1alpha1`
2. 公开 API group：`api.qsl-management.bi1kbu.com/v1alpha1`
3. Halo 自动为自定义 Endpoint 组合 `/apis/<group>/<version>` 前缀。
4. 当前角色模板主要通过 `apiGroups`、`resources` 和 `verbs` 为这些自定义 API 授权。

主要代码位置：

1. `src/main/java/com/bi1kbu/qslmanagement/api/console/QslConsoleApiEndpoint.java`
2. `src/main/java/com/bi1kbu/qslmanagement/api/publicapi/`
3. `src/main/resources/extensions/qsl-menu-role-templates.yaml`

## 4. 待核查路由

以下路由在 `group/version` 后超过 `resource/resourceName/subresource` 三层结构，存在被 Halo 判断为非资源型 API 的可能：

| 方法 | 完整路由 | 当前相关资源权限 | 待核查点 |
|---|---|---|---|
| `GET` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/exports/jobs/{jobName}/download` | `resources: ["exports"]` | 四层业务路径是否绕过资源型规则匹配 |
| `GET` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/imports/jobs/{jobName}/errors` | `resources: ["imports"]` | 四层业务路径是否需要 `nonResourceURLs` |
| `GET` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/imports/jobs/{jobName}/errors/download` | `resources: ["imports"]` | 五层业务路径是否需要 `nonResourceURLs` |
| `POST` | `/apis/console.api.qsl-management.bi1kbu.com/v1alpha1/mail-receive-confirms/{cardRecordName}/received-record-code/migrate` | `resources: ["mail-receive-confirms"]` | 四层业务路径及 POST 动词的权限匹配方式 |

本清单仅表示“需要验证”，不表示这些接口已经确认存在权限缺陷。

## 5. 潜在影响

如果上述接口被 Halo 归类为非资源型，而角色模板仍只提供资源型规则，可能出现：

1. 超级管理员访问正常，普通已授权角色返回 `403 Forbidden`。
2. 导入错误详情或错误报告下载不可用。
3. 导出文件下载不可用。
4. 收卡编号迁移动作对非超级管理员不可用。
5. 前端 UI 权限显示正常，但服务端实际请求被拒绝。

## 6. 后续验证要求

处理该问题前应先完成以下验证，不直接修改权限：

1. 使用 Halo `2.23` 目标运行环境启动插件。
2. 分别使用超级管理员、仅查看角色、编辑角色和无权限用户访问候选路由。
3. 记录每条请求的 HTTP 状态码和 Halo 鉴权日志。
4. 确认 Halo 对深层 `CustomEndpoint` 路由的资源解析结果。
5. 对照 `qsl-menu-role-templates.yaml` 确认实际命中的 Role rule。

## 7. 可能的处理方向

完成验证后，从以下方案中选择，不在验证前预设结论：

1. 优先调整为符合 Halo 资源型结构的路由，并同步前端、文档和测试。
2. 保留现有路由，为确认属于非资源型的接口补充精确 `nonResourceURLs`。
3. 如需路径通配符，应限制在最小业务前缀，不使用覆盖整个插件 API 的宽泛规则。
4. 继续保持 `view/edit` 最小权限拆分，写操作权限依赖对应查看权限。
5. 服务端鉴权仍作为最终边界，不能只依赖 UI 权限隐藏。

## 8. 完成条件

满足以下条件后方可关闭该问题：

1. 候选路由的 Halo RBAC 分类已有运行证据。
2. 普通角色、匿名用户和超级管理员的预期访问矩阵已明确。
3. 如需整改，代码、角色模板、API 合同与结构化文档已同步。
4. 相关构建和权限场景测试通过。
5. 再次核对目标 Halo 版本官方文档与 API 变更日志。
