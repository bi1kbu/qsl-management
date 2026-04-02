import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import { IconPlug } from '@halo-dev/components'
import './styles/qsl-theme.css'
import RouteLayoutView from './views/RouteLayoutView.vue'
import DashboardView from './views/DashboardView.vue'
import StationView from './views/StationView.vue'
import BureauView from './views/BureauView.vue'
import DictView from './views/DictView.vue'
import QsoView from './views/QsoView.vue'
import CardView from './views/CardView.vue'
import SendConfirmView from './views/SendConfirmView.vue'
import ReceiveConfirmView from './views/ReceiveConfirmView.vue'
import AuditLogView from './views/AuditLogView.vue'
import ReportView from './views/ReportView.vue'
import ExchangeRequestView from './views/ExchangeRequestView.vue'
import CallsignBindingView from './views/CallsignBindingView.vue'
import ImportExportView from './views/ImportExportView.vue'
import { createQslEditorExtensions } from './extensions/qsl-editor-cards'

const menuMeta = {
  group: 'QSL管理',
  icon: markRaw(IconPlug),
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
                  menu: { ...menuMeta, name: '卡片局配置', priority: 12 },
                },
              },
              {
                path: 'dict',
                name: 'QslDict',
                component: DictView,
                meta: {
                  title: '字典配置',
                  searchable: true,
                  menu: { ...menuMeta, name: '字典配置', priority: 13 },
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
                  menu: { ...menuMeta, name: '卡片记录', priority: 22 },
                },
              },
            ],
          },
          {
            path: 'workflow',
            name: 'QslWorkflow',
            component: RouteLayoutView,
            redirect: '/qsl/workflow/send-confirm',
            meta: {
              title: '流程',
              searchable: false,
              menu: { ...menuMeta, name: '流程', priority: 30 },
            },
            children: [
              {
                path: 'send-confirm',
                name: 'QslSendConfirm',
                component: SendConfirmView,
                meta: {
                  title: '发信确认',
                  searchable: true,
                  menu: { ...menuMeta, name: '发信确认', priority: 31 },
                },
              },
              {
                path: 'receive-confirm',
                name: 'QslReceiveConfirm',
                component: ReceiveConfirmView,
                meta: {
                  title: '收信确认',
                  searchable: true,
                  menu: { ...menuMeta, name: '收信确认', priority: 32 },
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
                  menu: { ...menuMeta, name: '导入导出', priority: 61 },
                },
              },
            ],
          },
        ],
      },
    },
  ],
  extensionPoints: {
    'default:editor:extension:create': () => createQslEditorExtensions(),
  },
})
