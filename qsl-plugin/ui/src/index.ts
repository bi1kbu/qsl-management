import { definePlugin } from '@halo-dev/ui-shared'
import { qslMenuModules } from './constants/menu-modules'
import './styles/qsl-module-framework.scss'

const qslTopLevelGroups = [
  { key: 'overview', title: '总览', priority: 10 },
  { key: 'settings', title: '配置', priority: 20 },
  { key: 'business', title: '业务', priority: 30 },
  { key: 'review', title: '审核', priority: 40 },
  { key: 'audit', title: '审计', priority: 50 },
  { key: 'data', title: '数据', priority: 60 },
] as const

const groupedModules = qslTopLevelGroups
  .map((group) => ({
    ...group,
    modules: qslMenuModules
      .filter((module) => module.group === group.title)
      .sort((a, b) => a.priority - b.priority),
  }))
  .filter((group) => group.modules.length > 0)

const firstAvailableModulePath =
  groupedModules.length > 0 && groupedModules[0].modules.length > 0
    ? `/qsl/${groupedModules[0].key}/${groupedModules[0].modules[0].key}`
    : '/qsl'

const qslMenuGroupName = 'QSL管理'

const routes = [
  {
    parentName: 'Root',
    route: {
      path: '/qsl',
      name: 'QslRootRedirect',
      redirect: firstAvailableModulePath,
      meta: {
        title: 'QSL管理',
        searchable: false,
      },
    },
  },
  ...groupedModules.map((group) => ({
    parentName: 'Root',
    route: {
      path: `/qsl/${group.key}`,
      name: `QslGroup${group.key}`,
      component: () => import(/* webpackChunkName: "MenuLayoutView" */ './views/MenuLayoutView.vue'),
      redirect: `/qsl/${group.key}/${group.modules[0].key}`,
      meta: {
        title: group.title,
        searchable: false,
        menu: {
          name: group.title,
          group: qslMenuGroupName,
          priority: group.priority,
        },
      },
      children: group.modules.map((module) => ({
        path: module.key,
        name: `QslMenu${module.key}`,
        component: () => import(/* webpackChunkName: "MenuModuleView" */ './views/MenuModuleView.vue'),
        props: {
          qslModule: module,
        },
        meta: {
          title: module.title,
          searchable: true,
          permissions: [module.viewPermission],
          qslModule: module,
          menu: {
            name: module.title,
            priority: module.priority,
          },
        },
      })),
    },
  })),
]

export default definePlugin({
  components: {},
  routes,
  extensionPoints: {},
})
