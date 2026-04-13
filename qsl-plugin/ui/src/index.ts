import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import { qslMenuModules } from './constants/menu-modules'

const routes = qslMenuModules.map((module) => ({
  parentName: 'Root',
  route: {
    path: `/qsl/${module.key}`,
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
        group: module.group,
        icon: markRaw(IconPlug),
        priority: module.priority,
      },
    },
  },
}))

export default definePlugin({
  components: {},
  routes,
  extensionPoints: {},
})
