export interface QslMenuModule {
  key: string
  title: string
  group: string
  priority: number
  viewPermission: string
  editPermission: string
  viewDependencies: string[]
  editDependencies: string[]
}

const pluginPermissionPrefix = 'plugin:qsl-management'

const toViewPermission = (key: string): string => `${pluginPermissionPrefix}:${key}:view`
const toEditPermission = (key: string): string => `${pluginPermissionPrefix}:${key}:edit`

export const qslMenuModules: QslMenuModule[] = [
  {
    key: 'overview-dashboard',
    title: '总览看板',
    group: '总览',
    priority: 100,
    viewPermission: toViewPermission('overview-dashboard'),
    editPermission: toEditPermission('overview-dashboard'),
    viewDependencies: [
      toViewPermission('qso-record'),
      toViewPermission('card-record'),
      toViewPermission('mail-send-confirm'),
      toViewPermission('mail-receive-confirm'),
    ],
    editDependencies: [toViewPermission('overview-dashboard')],
  },
  {
    key: 'system-settings',
    title: '系统参数',
    group: '配置',
    priority: 90,
    viewPermission: toViewPermission('system-settings'),
    editPermission: toEditPermission('system-settings'),
    viewDependencies: [],
    editDependencies: [toViewPermission('system-settings')],
  },
  {
    key: 'station-profile',
    title: '通信地址、本台设备、本台卡片',
    group: '配置',
    priority: 80,
    viewPermission: toViewPermission('station-profile'),
    editPermission: toEditPermission('station-profile'),
    viewDependencies: [toViewPermission('equipment-catalog')],
    editDependencies: [toViewPermission('station-profile'), toViewPermission('equipment-catalog')],
  },
  {
    key: 'qso-record',
    title: '通联记录',
    group: '业务',
    priority: 70,
    viewPermission: toViewPermission('qso-record'),
    editPermission: toEditPermission('qso-record'),
    viewDependencies: [toViewPermission('equipment-catalog'), toViewPermission('station-profile')],
    editDependencies: [toViewPermission('qso-record')],
  },
  {
    key: 'card-record',
    title: '卡片记录',
    group: '业务',
    priority: 60,
    viewPermission: toViewPermission('card-record'),
    editPermission: toEditPermission('card-record'),
    viewDependencies: [toViewPermission('qso-record'), toViewPermission('station-profile')],
    editDependencies: [toViewPermission('card-record'), toViewPermission('qso-record')],
  },
  {
    key: 'mail-send-confirm',
    title: '发信确认',
    group: '业务',
    priority: 50,
    viewPermission: toViewPermission('mail-send-confirm'),
    editPermission: toEditPermission('mail-send-confirm'),
    viewDependencies: [toViewPermission('card-record')],
    editDependencies: [toViewPermission('mail-send-confirm'), toViewPermission('card-record')],
  },
  {
    key: 'mail-receive-confirm',
    title: '收信确认',
    group: '业务',
    priority: 40,
    viewPermission: toViewPermission('mail-receive-confirm'),
    editPermission: toEditPermission('mail-receive-confirm'),
    viewDependencies: [toViewPermission('card-record'), toViewPermission('qso-record')],
    editDependencies: [
      toViewPermission('mail-receive-confirm'),
      toViewPermission('card-record'),
      toViewPermission('qso-record'),
    ],
  },
  {
    key: 'exchange-request-review',
    title: '换卡申请',
    group: '审核',
    priority: 30,
    viewPermission: toViewPermission('exchange-request-review'),
    editPermission: toEditPermission('exchange-request-review'),
    viewDependencies: [toViewPermission('address-bureau'), toViewPermission('card-record')],
    editDependencies: [toViewPermission('exchange-request-review'), toEditPermission('card-record')],
  },
  {
    key: 'qso-query',
    title: '通联记录查询',
    group: '审计',
    priority: 20,
    viewPermission: toViewPermission('qso-query'),
    editPermission: toEditPermission('qso-query'),
    viewDependencies: [toViewPermission('qso-record')],
    editDependencies: [toViewPermission('qso-query')],
  },
  {
    key: 'card-query',
    title: '卡片记录查询',
    group: '审计',
    priority: 19,
    viewPermission: toViewPermission('card-query'),
    editPermission: toEditPermission('card-query'),
    viewDependencies: [toViewPermission('card-record')],
    editDependencies: [toViewPermission('card-query')],
  },
  {
    key: 'report-auditlog',
    title: '统计报表、审计日志',
    group: '审计',
    priority: 18,
    viewPermission: toViewPermission('report-auditlog'),
    editPermission: toEditPermission('report-auditlog'),
    viewDependencies: [
      toViewPermission('qso-query'),
      toViewPermission('card-query'),
      toViewPermission('exchange-request-review'),
      toViewPermission('mail-send-confirm'),
      toViewPermission('mail-receive-confirm'),
    ],
    editDependencies: [toViewPermission('report-auditlog')],
  },
  {
    key: 'address-bureau',
    title: '地址管理、卡片局管理',
    group: '数据',
    priority: 10,
    viewPermission: toViewPermission('address-bureau'),
    editPermission: toEditPermission('address-bureau'),
    viewDependencies: [],
    editDependencies: [toViewPermission('address-bureau')],
  },
  {
    key: 'equipment-catalog',
    title: '设备库维护',
    group: '数据',
    priority: 9,
    viewPermission: toViewPermission('equipment-catalog'),
    editPermission: toEditPermission('equipment-catalog'),
    viewDependencies: [],
    editDependencies: [toViewPermission('equipment-catalog')],
  },
  {
    key: 'import-export',
    title: '导入导出',
    group: '数据',
    priority: 8,
    viewPermission: toViewPermission('import-export'),
    editPermission: toEditPermission('import-export'),
    viewDependencies: [
      toViewPermission('qso-query'),
      toViewPermission('card-query'),
      toViewPermission('exchange-request-review'),
      toViewPermission('address-bureau'),
      toViewPermission('equipment-catalog'),
    ],
    editDependencies: [
      toViewPermission('import-export'),
      toEditPermission('qso-record'),
      toEditPermission('card-record'),
      toEditPermission('exchange-request-review'),
      toEditPermission('address-bureau'),
      toEditPermission('equipment-catalog'),
    ],
  },
]
