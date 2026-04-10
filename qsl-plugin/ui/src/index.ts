import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import { IconPlug } from '@halo-dev/components'
import './styles/qsl-theme.css'
import RouteLayoutView from './views/RouteLayoutView.vue'
import DashboardView from './views/DashboardView.vue'
import StationView from './views/StationView.vue'
import BureauView from './views/BureauView.vue'
import DeviceLibraryView from './views/DeviceLibraryView.vue'
import QsoView from './views/QsoView.vue'
import CardView from './views/CardView.vue'
import SendConfirmView from './views/SendConfirmView.vue'
import ReceiveConfirmView from './views/ReceiveConfirmView.vue'
import AuditLogView from './views/AuditLogView.vue'
import ReportView from './views/ReportView.vue'
import ExchangeRequestView from './views/ExchangeRequestView.vue'
import CallsignBindingView from './views/CallsignBindingView.vue'
import ImportExportView from './views/ImportExportView.vue'
import AddressManageView from './views/AddressManageView.vue'
import CallsignManageBindView from './views/CallsignManageBindView.vue'
import CallsignManageAddressView from './views/CallsignManageAddressView.vue'
import CallsignManageQsoView from './views/CallsignManageQsoView.vue'
import CallsignManageCardView from './views/CallsignManageCardView.vue'
import { createQslEditorExtensions } from './extensions/qsl-editor-cards'

const menuMeta = {
  group: 'QSL管理',
  icon: markRaw(IconPlug),
}

const permissions = {
  dashboardView: 'plugin:qsl-management:dashboard:view',
  configStation: 'plugin:qsl-management:config:station',
  configBureau: 'plugin:qsl-management:config:bureau',
  configDeviceLibrary: 'plugin:qsl-management:config:device-library',
  businessQso: 'plugin:qsl-management:business:qso',
  businessCard: 'plugin:qsl-management:business:card',
  businessSendConfirm: 'plugin:qsl-management:business:send-confirm',
  businessReceiveConfirm: 'plugin:qsl-management:business:receive-confirm',
  callsignManage: 'plugin:qsl-management:callsign:manage',
  auditLog: 'plugin:qsl-management:audit:log',
  reviewExchange: 'plugin:qsl-management:review:exchange',
  reviewBinding: 'plugin:qsl-management:review:binding',
  dataImportExport: 'plugin:qsl-management:data:import-export',
  dataAddress: 'plugin:qsl-management:data:address-manage',
}

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/qsl',
        name: 'QslRoot',
        component: RouteLayoutView,
        redirect: '/qsl/dashboard',
        meta: {
          title: 'QSL 管理',
          searchable: false,
        },
        children: [
          {
            path: 'dashboard',
            name: 'QslDashboard',
            component: DashboardView,
            meta: {
              title: '总览',
              searchable: true,
              permissions: [permissions.dashboardView],
              menu: { ...menuMeta, name: '总览', priority: 1 },
            },
          },
          {
            path: 'config',
            name: 'QslConfig',
            component: RouteLayoutView,
            redirect: '/qsl/config/station',
            meta: {
              title: '配置',
              searchable: false,
              menu: { ...menuMeta, name: '配置', priority: 10 },
            },
            children: [
              {
                path: 'station',
                name: 'QslStation',
                component: StationView,
                meta: {
                  title: '本站配置',
                  searchable: true,
                  permissions: [permissions.configStation],
                  menu: { ...menuMeta, name: '本站配置', priority: 11 },
                },
              },
              {
                path: 'bureau',
                name: 'QslBureau',
                component: BureauView,
                meta: {
                  title: '卡片局配置',
                  searchable: true,
                  permissions: [permissions.configBureau],
                  menu: { ...menuMeta, name: '卡片局配置', priority: 12 },
                },
              },
              {
                path: 'dict',
                redirect: '/qsl/config/station',
                meta: { title: '本台设备(兼容路由)', searchable: false },
              },
              {
                path: 'device-library',
                name: 'QslDeviceLibrary',
                component: DeviceLibraryView,
                meta: {
                  title: '设备库',
                  searchable: true,
                  permissions: [permissions.configDeviceLibrary],
                  menu: { ...menuMeta, name: '设备库', priority: 14 },
                },
              },
            ],
          },
          {
            path: 'business',
            name: 'QslBusiness',
            component: RouteLayoutView,
            redirect: '/qsl/business/qso',
            meta: {
              title: '业务',
              searchable: false,
              menu: { ...menuMeta, name: '业务', priority: 20 },
            },
            children: [
              {
                path: 'qso',
                name: 'QslQso',
                component: QsoView,
                meta: {
                  title: '通联记录',
                  searchable: true,
                  permissions: [permissions.businessQso],
                  menu: { ...menuMeta, name: '通联记录', priority: 21 },
                },
              },
              {
                path: 'card',
                name: 'QslCard',
                component: CardView,
                meta: {
                  title: '卡片记录',
                  searchable: true,
                  permissions: [permissions.businessCard],
                  menu: { ...menuMeta, name: '卡片记录', priority: 22 },
                },
              },
              {
                path: 'send-confirm',
                name: 'QslSendConfirm',
                component: SendConfirmView,
                meta: {
                  title: '发信确认',
                  searchable: true,
                  permissions: [permissions.businessSendConfirm],
                  menu: { ...menuMeta, name: '发信确认', priority: 23 },
                },
              },
              {
                path: 'receive-confirm',
                name: 'QslReceiveConfirm',
                component: ReceiveConfirmView,
                meta: {
                  title: '收信确认',
                  searchable: true,
                  permissions: [permissions.businessReceiveConfirm],
                  menu: { ...menuMeta, name: '收信确认', priority: 24 },
                },
              },
            ],
          },
          {
            path: 'workflow/send-confirm',
            redirect: '/qsl/business/send-confirm',
            meta: { title: '发信确认(兼容路由)', searchable: false },
          },
          {
            path: 'workflow/receive-confirm',
            redirect: '/qsl/business/receive-confirm',
            meta: { title: '收信确认(兼容路由)', searchable: false },
          },
          {
            path: 'callsign',
            name: 'QslCallsignManage',
            component: RouteLayoutView,
            redirect: '/qsl/callsign/bind',
            meta: {
              title: '呼号管理',
              searchable: false,
              menu: { ...menuMeta, name: '呼号管理', priority: 30 },
            },
            children: [
              {
                path: 'bind',
                name: 'QslCallsignBind',
                component: CallsignManageBindView,
                meta: {
                  title: '呼号绑定',
                  searchable: true,
                  menu: { ...menuMeta, name: '呼号绑定', priority: 31 },
                },
              },
              {
                path: 'addresses',
                name: 'QslCallsignAddress',
                component: CallsignManageAddressView,
                meta: {
                  title: '地址维护',
                  searchable: true,
                  permissions: [permissions.callsignManage],
                  menu: { ...menuMeta, name: '地址维护', priority: 32 },
                },
              },
              {
                path: 'qso-query',
                name: 'QslCallsignQsoQuery',
                component: CallsignManageQsoView,
                meta: {
                  title: '通信记录查询',
                  searchable: true,
                  permissions: [permissions.callsignManage],
                  menu: { ...menuMeta, name: '通信记录查询', priority: 33 },
                },
              },
              {
                path: 'card-query',
                name: 'QslCallsignCardQuery',
                component: CallsignManageCardView,
                meta: {
                  title: '卡片记录查询',
                  searchable: true,
                  permissions: [permissions.callsignManage],
                  menu: { ...menuMeta, name: '卡片记录查询', priority: 34 },
                },
              },
            ],
          },
          {
            path: 'audit',
            name: 'QslAuditGroup',
            component: RouteLayoutView,
            redirect: '/qsl/audit/log',
            meta: {
              title: '审计',
              searchable: false,
              menu: { ...menuMeta, name: '审计', priority: 40 },
            },
            children: [
              {
                path: 'log',
                name: 'QslAudit',
                component: AuditLogView,
                meta: {
                  title: '审计日志',
                  searchable: true,
                  permissions: [permissions.auditLog],
                  menu: { ...menuMeta, name: '审计日志', priority: 41 },
                },
              },
              {
                path: 'report',
                name: 'QslReport',
                component: ReportView,
                meta: {
                  title: '统计报表',
                  searchable: true,
                  menu: { ...menuMeta, name: '统计报表', priority: 42 },
                },
              },
            ],
          },
          {
            path: 'review',
            name: 'QslReview',
            component: RouteLayoutView,
            redirect: '/qsl/review/exchange-request',
            meta: {
              title: '审核',
              searchable: false,
              menu: { ...menuMeta, name: '审核', priority: 50 },
            },
            children: [
              {
                path: 'exchange-request',
                name: 'QslExchangeRequest',
                component: ExchangeRequestView,
                meta: {
                  title: '换卡申请',
                  searchable: true,
                  permissions: [permissions.reviewExchange],
                  menu: { ...menuMeta, name: '换卡申请', priority: 51 },
                },
              },
              {
                path: 'callsign-binding',
                name: 'QslBindingReview',
                component: CallsignBindingView,
                meta: {
                  title: '呼号绑定审核',
                  searchable: true,
                  permissions: [permissions.reviewBinding],
                  menu: { ...menuMeta, name: '呼号绑定审核', priority: 52 },
                },
              },
            ],
          },
          {
            path: 'data',
            name: 'QslData',
            component: RouteLayoutView,
            redirect: '/qsl/data/import-export',
            meta: {
              title: '数据',
              searchable: false,
              menu: { ...menuMeta, name: '数据', priority: 60 },
            },
            children: [
              {
                path: 'import-export',
                name: 'QslImportExport',
                component: ImportExportView,
                meta: {
                  title: '导入导出',
                  searchable: true,
                  permissions: [permissions.dataImportExport],
                  menu: { ...menuMeta, name: '导入导出', priority: 61 },
                },
              },
              {
                path: 'addresses',
                name: 'QslAddressManage',
                component: AddressManageView,
                meta: {
                  title: '地址管理',
                  searchable: true,
                  permissions: [permissions.dataAddress],
                  menu: { ...menuMeta, name: '地址管理', priority: 62 },
                },
              },
            ],
          },
        ],
      },
    },
  ],
  ucRoutes: [
    {
      parentName: 'Root',
      route: {
        path: '/qsl-uc',
        name: 'QslUcRoot',
        component: RouteLayoutView,
        redirect: '/qsl-uc/callsign/bind',
        meta: {
          title: 'QSL 管理',
          searchable: false,
          menu: { ...menuMeta, name: 'QSL 管理', group: 'content', priority: 35 },
        },
        children: [
          {
            path: 'callsign/bind',
            name: 'QslUcCallsignBind',
            component: CallsignManageBindView,
            meta: {
              title: '呼号绑定',
              searchable: false,
              menu: { ...menuMeta, name: '呼号绑定', group: 'content', priority: 36 },
            },
          },
          {
            path: 'callsign/addresses',
            name: 'QslUcCallsignAddress',
            component: CallsignManageAddressView,
            meta: {
              title: '地址维护',
              searchable: false,
              permissions: [permissions.callsignManage],
              menu: { ...menuMeta, name: '地址维护', group: 'content', priority: 37 },
            },
          },
          {
            path: 'callsign/qso-query',
            name: 'QslUcCallsignQsoQuery',
            component: CallsignManageQsoView,
            meta: {
              title: '通信记录查询',
              searchable: false,
              permissions: [permissions.callsignManage],
              menu: { ...menuMeta, name: '通信记录查询', group: 'content', priority: 38 },
            },
          },
          {
            path: 'callsign/card-query',
            name: 'QslUcCallsignCardQuery',
            component: CallsignManageCardView,
            meta: {
              title: '卡片记录查询',
              searchable: false,
              permissions: [permissions.callsignManage],
              menu: { ...menuMeta, name: '卡片记录查询', group: 'content', priority: 39 },
            },
          },
          {
            path: 'report',
            name: 'QslUcReport',
            component: ReportView,
            meta: {
              title: '统计报表',
              searchable: false,
              menu: { ...menuMeta, name: '统计报表', group: 'content', priority: 40 },
            },
          },
        ],
      },
    },
  ],
  extensionPoints: {
    'default:editor:extension:create': () => createQslEditorExtensions(),
  },
})
