import { listExtensions, type QslExtension } from './qsl-extension-api'

export type DatasetValue =
  | 'qso-record'
  | 'card-record'
  | 'exchange-request-review'
  | 'offline-activity'
  | 'address-management'
  | 'bureau-management'
  | 'equipment-catalog'
  | 'system-setting'
  | 'station-profile'
  | 'station-equipment'
  | 'station-card'

interface DatasetConfig {
  value: DatasetValue
  label: string
  plural: string
  kind: string
  idPrefix: string
  headers: string[]
  keywords: string[]
  toRow: (item: QslExtension<Record<string, any>, Record<string, any>>) => Record<string, string>
  fromRow: (row: Record<string, string>) => {
    spec: Record<string, any>
    status?: Record<string, any>
  }
}

const parseBoolean = (value: string): boolean => {
  const normalized = value.trim().toLowerCase()
  return normalized === 'true' || normalized === '1' || normalized === 'yes' || normalized === '是'
}

const parseInteger = (value: string): number => {
  const normalized = value.trim()
  if (!normalized) {
    return 0
  }
  const parsed = Number.parseInt(normalized, 10)
  return Number.isFinite(parsed) ? parsed : 0
}

const parseList = (value: string): string[] => {
  return value
    .replace(/[，、；;]/g, ',')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

const stringifyList = (value: unknown): string => {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean).join('、') : ''
}

const datasetConfigs: DatasetConfig[] = [
  {
    value: 'qso-record',
    label: '通联记录',
    plural: 'qso-records',
    kind: 'QsoRecord',
    idPrefix: 'qso',
    headers: [
      'id',
      'callSign',
      'sceneType',
      'date',
      'time',
      'timezone',
      'freq',
      'myRig',
      'myRigMode',
      'myRigAnt',
      'myRigPwr',
      'myQth',
      'operator',
      'rig',
      'ant',
      'pwr',
      'qth',
      'rstSent',
      'rstRcvd',
      'remarks',
    ],
    keywords: ['qso-record', 'qso', '通联记录'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      sceneType: String(item.spec?.sceneType ?? 'QSO'),
      date: String(item.spec?.date ?? ''),
      time: String(item.spec?.time ?? ''),
      timezone: String(item.spec?.timezone ?? ''),
      freq: String(item.spec?.freq ?? ''),
      myRig: String(item.spec?.myRig ?? ''),
      myRigMode: String(item.spec?.myRigMode ?? ''),
      myRigAnt: String(item.spec?.myRigAnt ?? ''),
      myRigPwr: String(item.spec?.myRigPwr ?? ''),
      myQth: String(item.spec?.myQth ?? ''),
      operator: String(item.spec?.operator ?? ''),
      rig: String(item.spec?.rig ?? ''),
      ant: String(item.spec?.ant ?? ''),
      pwr: String(item.spec?.pwr ?? ''),
      qth: String(item.spec?.qth ?? ''),
      rstSent: String(item.spec?.rstSent ?? ''),
      rstRcvd: String(item.spec?.rstRcvd ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        sceneType: row.sceneType ?? 'QSO',
        date: row.date ?? '',
        time: row.time ?? '',
        timezone: row.timezone ?? 'UTC',
        freq: row.freq ?? '',
        myRig: row.myRig ?? '',
        myRigMode: row.myRigMode ?? '',
        myRigAnt: row.myRigAnt ?? '',
        myRigPwr: row.myRigPwr ?? '',
        myQth: row.myQth ?? '',
        operator: row.operator ?? '',
        rig: row.rig ?? '',
        ant: row.ant ?? '',
        pwr: row.pwr ?? '',
        qth: row.qth ?? '',
        rstSent: row.rstSent ?? '',
        rstRcvd: row.rstRcvd ?? '',
        remarks: row.remarks ?? '',
      },
    }),
  },
  {
    value: 'card-record',
    label: '卡片记录',
    plural: 'card-records',
    kind: 'CardRecord',
    idPrefix: 'c',
    headers: [
      'id',
      'callSign',
      'cardType',
      'cardVersion',
      'qsoRecordName',
      'cardDate',
      'cardTime',
      'businessRemarks',
      'receivedRemarks',
      'publicReceiptRemarks',
      'cardRemarks',
      'cardSent',
      'cardReceived',
      'receiptConfirmed',
      'sentAt',
      'receivedAt',
      'createdMailStatus',
      'createdMailSentAt',
      'createdMailLastError',
      'sentMailStatus',
      'sentMailSentAt',
      'sentMailLastError',
      'receivedMailStatus',
      'receivedMailSentAt',
      'receivedMailLastError',
      'mailTargetEmail',
      'receivedRecordCodes',
    ],
    keywords: ['card-record', 'card', '卡片记录'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      cardType: String(item.spec?.cardType ?? ''),
      cardVersion: String(item.spec?.cardVersion ?? ''),
      qsoRecordName: String(item.spec?.qsoRecordName ?? ''),
      cardDate: String(item.spec?.cardDate ?? ''),
      cardTime: String(item.spec?.cardTime ?? ''),
      businessRemarks: String(item.spec?.businessRemarks ?? ''),
      receivedRemarks: String(item.spec?.receivedRemarks ?? ''),
      publicReceiptRemarks: String(item.spec?.publicReceiptRemarks ?? ''),
      cardRemarks: String(item.spec?.cardRemarks ?? ''),
      cardSent: String(Boolean(item.spec?.cardSent)),
      cardReceived: String(Boolean(item.spec?.cardReceived)),
      receiptConfirmed: String(Boolean(item.spec?.receiptConfirmed)),
      sentAt: String(item.spec?.sentAt ?? ''),
      receivedAt: String(item.spec?.receivedAt ?? ''),
      createdMailStatus: String(item.spec?.createdMailStatus ?? ''),
      createdMailSentAt: String(item.spec?.createdMailSentAt ?? ''),
      createdMailLastError: String(item.spec?.createdMailLastError ?? ''),
      sentMailStatus: String(item.spec?.sentMailStatus ?? ''),
      sentMailSentAt: String(item.spec?.sentMailSentAt ?? ''),
      sentMailLastError: String(item.spec?.sentMailLastError ?? ''),
      receivedMailStatus: String(item.spec?.receivedMailStatus ?? ''),
      receivedMailSentAt: String(item.spec?.receivedMailSentAt ?? ''),
      receivedMailLastError: String(item.spec?.receivedMailLastError ?? ''),
      mailTargetEmail: String(item.spec?.mailTargetEmail ?? ''),
      receivedRecordCodes: String(item.spec?.receivedRecordCodes ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        cardType: row.cardType ?? 'QSO',
        cardVersion: row.cardVersion ?? '',
        qsoRecordName: row.qsoRecordName ?? '',
        cardDate: row.cardDate ?? '',
        cardTime: row.cardTime ?? '',
        businessRemarks: row.businessRemarks ?? '',
        receivedRemarks: row.receivedRemarks ?? '',
        publicReceiptRemarks: row.publicReceiptRemarks ?? '',
        cardRemarks: row.cardRemarks ?? '',
        cardSent: parseBoolean(row.cardSent ?? ''),
        cardReceived: parseBoolean(row.cardReceived ?? ''),
        receiptConfirmed: parseBoolean(row.receiptConfirmed ?? ''),
        sentAt: row.sentAt ?? '',
        receivedAt: row.receivedAt ?? '',
        createdMailStatus: row.createdMailStatus ?? '',
        createdMailSentAt: row.createdMailSentAt ?? '',
        createdMailLastError: row.createdMailLastError ?? '',
        sentMailStatus: row.sentMailStatus ?? '',
        sentMailSentAt: row.sentMailSentAt ?? '',
        sentMailLastError: row.sentMailLastError ?? '',
        receivedMailStatus: row.receivedMailStatus ?? '',
        receivedMailSentAt: row.receivedMailSentAt ?? '',
        receivedMailLastError: row.receivedMailLastError ?? '',
        mailTargetEmail: row.mailTargetEmail ?? '',
        receivedRecordCodes: row.receivedRecordCodes ?? '',
      },
    }),
  },
  {
    value: 'exchange-request-review',
    label: '换卡申请',
    plural: 'exchange-requests',
    kind: 'ExchangeRequest',
    idPrefix: 'exchange-request',
    headers: [
      'id',
      'callSign',
      'cardVersion',
      'useBureau',
      'bureauName',
      'email',
      'name',
      'telephone',
      'postalCode',
      'address',
      'remarks',
      'reviewStatus',
      'reviewReason',
      'reviewedBy',
      'reviewedAt',
      'reviewMailStatus',
      'reviewMailSentAt',
      'reviewMailLastError',
      'reviewMailTargetEmail',
    ],
    keywords: ['exchange-request-review', 'exchange-request', '换卡申请'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      cardVersion: String(item.spec?.cardVersion ?? ''),
      useBureau: String(Boolean(item.spec?.useBureau)),
      bureauName: String(item.spec?.bureauName ?? ''),
      email: String(item.spec?.email ?? ''),
      name: String(item.spec?.name ?? ''),
      telephone: String(item.spec?.telephone ?? ''),
      postalCode: String(item.spec?.postalCode ?? ''),
      address: String(item.spec?.address ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
      reviewStatus: String(item.status?.reviewStatus ?? ''),
      reviewReason: String(item.status?.reviewReason ?? ''),
      reviewedBy: String(item.status?.reviewedBy ?? ''),
      reviewedAt: String(item.status?.reviewedAt ?? ''),
      reviewMailStatus: String(item.status?.reviewMailStatus ?? ''),
      reviewMailSentAt: String(item.status?.reviewMailSentAt ?? ''),
      reviewMailLastError: String(item.status?.reviewMailLastError ?? ''),
      reviewMailTargetEmail: String(item.status?.reviewMailTargetEmail ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        cardVersion: row.cardVersion ?? '',
        useBureau: parseBoolean(row.useBureau ?? ''),
        bureauName: row.bureauName ?? '',
        email: row.email ?? '',
        name: row.name ?? '',
        telephone: row.telephone ?? '',
        postalCode: row.postalCode ?? '',
        address: row.address ?? '',
        remarks: row.remarks ?? '',
      },
      status: {
        reviewStatus: row.reviewStatus || '待审核',
        reviewReason: row.reviewReason ?? '',
        reviewedBy: row.reviewedBy ?? '',
        reviewedAt: row.reviewedAt ?? '',
        reviewMailStatus: row.reviewMailStatus ?? '',
        reviewMailSentAt: row.reviewMailSentAt ?? '',
        reviewMailLastError: row.reviewMailLastError ?? '',
        reviewMailTargetEmail: row.reviewMailTargetEmail ?? '',
      },
    }),
  },
  {
    value: 'offline-activity',
    label: '线下换卡活动',
    plural: 'offline-activities',
    kind: 'OfflineActivity',
    idPrefix: 'offline-activity',
    headers: [
      'id',
      'activityName',
      'activityLocation',
      'activityDate',
      'activityTime',
      'cardRemarks',
      'workflowStatus',
    ],
    keywords: ['offline-activity', 'offline-activities', '线下换卡活动', '活动清单'],
    toRow: (item) => ({
      id: item.metadata.name,
      activityName: String(item.spec?.activityName ?? ''),
      activityLocation: String(item.spec?.activityLocation ?? ''),
      activityDate: String(item.spec?.activityDate ?? ''),
      activityTime: String(item.spec?.activityTime ?? ''),
      cardRemarks: String(item.spec?.cardRemarks ?? ''),
      workflowStatus: String(item.status?.workflowStatus ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        activityName: row.activityName ?? '',
        activityLocation: row.activityLocation ?? '',
        activityDate: row.activityDate ?? '',
        activityTime: row.activityTime ?? '',
        cardRemarks: row.cardRemarks ?? '',
      },
      status: {
        workflowStatus: row.workflowStatus ?? '',
      },
    }),
  },
  {
    value: 'address-management',
    label: '地址管理',
    plural: 'address-book-entries',
    kind: 'AddressBookEntry',
    idPrefix: 'address',
    headers: ['id', 'callSign', 'name', 'telephone', 'postalCode', 'address', 'email', 'addressRemarks'],
    keywords: ['address-management', 'address', '地址管理'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      name: String(item.spec?.name ?? ''),
      telephone: String(item.spec?.telephone ?? ''),
      postalCode: String(item.spec?.postalCode ?? ''),
      address: String(item.spec?.address ?? ''),
      email: String(item.spec?.email ?? ''),
      addressRemarks: String(item.spec?.addressRemarks ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        name: row.name ?? '',
        telephone: row.telephone ?? '',
        postalCode: row.postalCode ?? '',
        address: row.address ?? '',
        email: row.email ?? '',
        addressRemarks: row.addressRemarks ?? '',
      },
    }),
  },
  {
    value: 'bureau-management',
    label: '卡片局管理',
    plural: 'bureau-entries',
    kind: 'BureauEntry',
    idPrefix: 'buro',
    headers: ['id', 'bureauName', 'telephone', 'postalCode', 'address', 'addressRemarks'],
    keywords: ['bureau-management', 'bureau', '卡片局管理'],
    toRow: (item) => ({
      id: item.metadata.name,
      bureauName: String(item.spec?.bureauName ?? ''),
      telephone: String(item.spec?.telephone ?? ''),
      postalCode: String(item.spec?.postalCode ?? ''),
      address: String(item.spec?.address ?? ''),
      addressRemarks: String(item.spec?.addressRemarks ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        bureauName: row.bureauName ?? '',
        telephone: row.telephone ?? '',
        postalCode: row.postalCode ?? '',
        address: row.address ?? '',
        addressRemarks: row.addressRemarks ?? '',
      },
    }),
  },
  {
    value: 'equipment-catalog',
    label: '设备库维护',
    plural: 'equipment-catalog-entries',
    kind: 'EquipmentCatalogEntry',
    idPrefix: 'equipment-catalog',
    headers: ['id', 'type', 'value', 'remarks'],
    keywords: ['equipment-catalog', 'equipment', '设备库维护'],
    toRow: (item) => ({
      id: item.metadata.name,
      type: String(item.spec?.type ?? ''),
      value: String(item.spec?.value ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        type: row.type ?? '',
        value: row.value ?? '',
        remarks: row.remarks ?? '',
      },
    }),
  },
  {
    value: 'system-setting',
    label: '系统参数与通知策略',
    plural: 'system-settings',
    kind: 'SystemSetting',
    idPrefix: 'system-setting',
    headers: [
      'id',
      'guestQueryPerMinute',
      'requiresExchangeReview',
      'autoNotifyOnCardCreated',
      'autoNotifyOnCardSent',
      'autoNotifyOnCardReceived',
      'autoNotifyOnExchangeReviewed',
      'qsoAutoNotifyOnCardCreated',
      'qsoAutoNotifyOnCardSent',
      'qsoAutoNotifyOnCardReceived',
      'onlineAutoNotifyOnCardCreated',
      'onlineAutoNotifyOnCardSent',
      'onlineAutoNotifyOnCardReceived',
      'onlineAutoNotifyOnExchangeReviewed',
      'offlineAutoNotifyOnCardReceived',
      'cardRecordSequence',
      'receiveRecordSequence',
      'lastModifiedBy',
      'lastModifiedAt',
    ],
    keywords: ['system-setting', 'system-settings', '系统参数', '通知策略'],
    toRow: (item) => ({
      id: item.metadata.name,
      guestQueryPerMinute: String(item.spec?.guestQueryPerMinute ?? ''),
      requiresExchangeReview: String(Boolean(item.spec?.requiresExchangeReview)),
      autoNotifyOnCardCreated: String(Boolean(item.spec?.autoNotifyOnCardCreated)),
      autoNotifyOnCardSent: String(Boolean(item.spec?.autoNotifyOnCardSent)),
      autoNotifyOnCardReceived: String(Boolean(item.spec?.autoNotifyOnCardReceived)),
      autoNotifyOnExchangeReviewed: String(Boolean(item.spec?.autoNotifyOnExchangeReviewed)),
      qsoAutoNotifyOnCardCreated: String(Boolean(item.spec?.qsoAutoNotifyOnCardCreated)),
      qsoAutoNotifyOnCardSent: String(Boolean(item.spec?.qsoAutoNotifyOnCardSent)),
      qsoAutoNotifyOnCardReceived: String(Boolean(item.spec?.qsoAutoNotifyOnCardReceived)),
      onlineAutoNotifyOnCardCreated: String(Boolean(item.spec?.onlineAutoNotifyOnCardCreated)),
      onlineAutoNotifyOnCardSent: String(Boolean(item.spec?.onlineAutoNotifyOnCardSent)),
      onlineAutoNotifyOnCardReceived: String(Boolean(item.spec?.onlineAutoNotifyOnCardReceived)),
      onlineAutoNotifyOnExchangeReviewed: String(Boolean(item.spec?.onlineAutoNotifyOnExchangeReviewed)),
      offlineAutoNotifyOnCardReceived: String(Boolean(item.spec?.offlineAutoNotifyOnCardReceived)),
      cardRecordSequence: String(item.spec?.cardRecordSequence ?? ''),
      receiveRecordSequence: String(item.spec?.receiveRecordSequence ?? ''),
      lastModifiedBy: String(item.status?.lastModifiedBy ?? ''),
      lastModifiedAt: String(item.status?.lastModifiedAt ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        guestQueryPerMinute: parseInteger(row.guestQueryPerMinute ?? ''),
        requiresExchangeReview: parseBoolean(row.requiresExchangeReview ?? ''),
        autoNotifyOnCardCreated: parseBoolean(row.autoNotifyOnCardCreated ?? ''),
        autoNotifyOnCardSent: parseBoolean(row.autoNotifyOnCardSent ?? ''),
        autoNotifyOnCardReceived: parseBoolean(row.autoNotifyOnCardReceived ?? ''),
        autoNotifyOnExchangeReviewed: parseBoolean(row.autoNotifyOnExchangeReviewed ?? ''),
        qsoAutoNotifyOnCardCreated: parseBoolean(row.qsoAutoNotifyOnCardCreated ?? ''),
        qsoAutoNotifyOnCardSent: parseBoolean(row.qsoAutoNotifyOnCardSent ?? ''),
        qsoAutoNotifyOnCardReceived: parseBoolean(row.qsoAutoNotifyOnCardReceived ?? ''),
        onlineAutoNotifyOnCardCreated: parseBoolean(row.onlineAutoNotifyOnCardCreated ?? ''),
        onlineAutoNotifyOnCardSent: parseBoolean(row.onlineAutoNotifyOnCardSent ?? ''),
        onlineAutoNotifyOnCardReceived: parseBoolean(row.onlineAutoNotifyOnCardReceived ?? ''),
        onlineAutoNotifyOnExchangeReviewed: parseBoolean(row.onlineAutoNotifyOnExchangeReviewed ?? ''),
        offlineAutoNotifyOnCardReceived: parseBoolean(row.offlineAutoNotifyOnCardReceived ?? ''),
        cardRecordSequence: parseInteger(row.cardRecordSequence ?? ''),
        receiveRecordSequence: parseInteger(row.receiveRecordSequence ?? ''),
      },
      status: {
        lastModifiedBy: row.lastModifiedBy ?? '',
        lastModifiedAt: row.lastModifiedAt ?? '',
      },
    }),
  },
  {
    value: 'station-profile',
    label: '通信地址',
    plural: 'station-profiles',
    kind: 'StationProfile',
    idPrefix: 'station-profile',
    headers: [
      'id',
      'myCallSign',
      'myName',
      'myTelephone',
      'myPostalCode',
      'myAddress',
      'myEmail',
      'stationRemarks',
      'lastModifiedBy',
      'lastModifiedAt',
    ],
    keywords: ['station-profile', 'station-profiles', '通信地址'],
    toRow: (item) => ({
      id: item.metadata.name,
      myCallSign: String(item.spec?.myCallSign ?? ''),
      myName: String(item.spec?.myName ?? ''),
      myTelephone: String(item.spec?.myTelephone ?? ''),
      myPostalCode: String(item.spec?.myPostalCode ?? ''),
      myAddress: String(item.spec?.myAddress ?? ''),
      myEmail: String(item.spec?.myEmail ?? ''),
      stationRemarks: String(item.spec?.stationRemarks ?? ''),
      lastModifiedBy: String(item.status?.lastModifiedBy ?? ''),
      lastModifiedAt: String(item.status?.lastModifiedAt ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        myCallSign: row.myCallSign ?? '',
        myName: row.myName ?? '',
        myTelephone: row.myTelephone ?? '',
        myPostalCode: row.myPostalCode ?? '',
        myAddress: row.myAddress ?? '',
        myEmail: row.myEmail ?? '',
        stationRemarks: row.stationRemarks ?? '',
      },
      status: {
        lastModifiedBy: row.lastModifiedBy ?? '',
        lastModifiedAt: row.lastModifiedAt ?? '',
      },
    }),
  },
  {
    value: 'station-equipment',
    label: '本台设备',
    plural: 'station-equipments',
    kind: 'StationEquipment',
    idPrefix: 'station-equipment',
    headers: ['id', 'rigName', 'antennas', 'powers', 'modes', 'remarks', 'enabled'],
    keywords: ['station-equipment', 'station-equipments', '本台设备'],
    toRow: (item) => ({
      id: item.metadata.name,
      rigName: String(item.spec?.rigName ?? ''),
      antennas: stringifyList(item.spec?.antennas),
      powers: stringifyList(item.spec?.powers),
      modes: stringifyList(item.spec?.modes),
      remarks: String(item.spec?.remarks ?? ''),
      enabled: String(Boolean(item.status?.enabled)),
    }),
    fromRow: (row) => ({
      spec: {
        rigName: row.rigName ?? '',
        antennas: parseList(row.antennas ?? ''),
        powers: parseList(row.powers ?? ''),
        modes: parseList(row.modes ?? ''),
        remarks: row.remarks ?? '',
      },
      status: {
        enabled: parseBoolean(row.enabled ?? ''),
      },
    }),
  },
  {
    value: 'station-card',
    label: '本台卡片',
    plural: 'station-cards',
    kind: 'StationCard',
    idPrefix: 'station-card',
    headers: [
      'id',
      'cardVersion',
      'imageAttachmentName',
      'imageAttachmentDisplayName',
      'imagePermalink',
      'imageThumbnailUrl',
      'imageMediaType',
      'imageSize',
      'availableInventory',
      'versionTotal',
      'sortOrder',
      'remarks',
      'active',
    ],
    keywords: ['station-card', 'station-cards', '本台卡片'],
    toRow: (item) => ({
      id: item.metadata.name,
      cardVersion: String(item.spec?.cardVersion ?? ''),
      imageAttachmentName: String(item.spec?.imageAttachmentName ?? ''),
      imageAttachmentDisplayName: String(item.spec?.imageAttachmentDisplayName ?? ''),
      imagePermalink: String(item.spec?.imagePermalink ?? ''),
      imageThumbnailUrl: String(item.spec?.imageThumbnailUrl ?? ''),
      imageMediaType: String(item.spec?.imageMediaType ?? ''),
      imageSize: String(item.spec?.imageSize ?? ''),
      availableInventory: String(item.spec?.availableInventory ?? ''),
      versionTotal: String(item.spec?.versionTotal ?? ''),
      sortOrder: String(item.spec?.sortOrder ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
      active: String(Boolean(item.status?.active)),
    }),
    fromRow: (row) => ({
      spec: {
        cardVersion: row.cardVersion ?? '',
        imageAttachmentName: row.imageAttachmentName ?? '',
        imageAttachmentDisplayName: row.imageAttachmentDisplayName ?? '',
        imagePermalink: row.imagePermalink ?? '',
        imageThumbnailUrl: row.imageThumbnailUrl ?? '',
        imageMediaType: row.imageMediaType ?? '',
        imageSize: parseInteger(row.imageSize ?? ''),
        availableInventory: parseInteger(row.availableInventory ?? ''),
        versionTotal: parseInteger(row.versionTotal ?? ''),
        sortOrder: parseInteger(row.sortOrder ?? ''),
        remarks: row.remarks ?? '',
      },
      status: {
        active: parseBoolean(row.active ?? ''),
      },
    }),
  },
]

const datasetConfigMap = Object.fromEntries(datasetConfigs.map((config) => [config.value, config])) as Record<
  DatasetValue,
  DatasetConfig
>

export const datasetOptions = datasetConfigs.map((config) => ({
  value: config.value,
  label: config.label,
}))

export const getDatasetLabel = (dataset: DatasetValue | ''): string => {
  if (!dataset) {
    return '未识别类型'
  }
  return datasetConfigMap[dataset].label
}

const normalizeMarker = (value: string): string => {
  return value.toLowerCase().replace(/[\uFEFF"']/g, '').replace(/\s+/g, '')
}

export const detectDatasetByMarker = (marker: string): DatasetValue | '' => {
  const normalized = normalizeMarker(marker)
  if (!normalized) {
    return ''
  }

  for (const item of datasetConfigs) {
    if (item.keywords.some((keyword) => normalized.includes(normalizeMarker(keyword)))) {
      return item.value
    }
  }

  return ''
}

const escapeCsvCell = (value: string): string => {
  if (!/[",\n\r]/.test(value)) {
    return value
  }
  return `"${value.split('"').join('""')}"`
}

export const parseCsvRows = (content: string): string[][] => {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let inQuotes = false

  for (let index = 0; index < content.length; index += 1) {
    const char = content[index]
    const next = content[index + 1]

    if (inQuotes) {
      if (char === '"' && next === '"') {
        cell += '"'
        index += 1
        continue
      }
      if (char === '"') {
        inQuotes = false
        continue
      }
      cell += char
      continue
    }

    if (char === '"') {
      inQuotes = true
      continue
    }
    if (char === ',') {
      row.push(cell)
      cell = ''
      continue
    }
    if (char === '\n') {
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
      continue
    }
    if (char !== '\r') {
      cell += char
    }
  }

  if (cell.length > 0 || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }

  return rows.filter((line) => line.some((cellValue) => cellValue.trim().length > 0))
}

export const parseCsvToRowObjects = (content: string): { rowObjects: Record<string, string>[]; dataRows: number } => {
  const rows = parseCsvRows(content)
  if (rows.length <= 1) {
    return { rowObjects: [], dataRows: 0 }
  }

  const rawHeaders = rows[0].map((header, index) => {
    if (index === 0 && header.includes('#')) {
      return header.split('#')[0].trim()
    }
    return header.trim()
  })
  const headers = rawHeaders.map((header, index) => (header || (index === 0 ? 'id' : `field${index}`)))
  const rowObjects: Record<string, string>[] = []

  for (let index = 1; index < rows.length; index += 1) {
    const line = rows[index]
    const rowObject: Record<string, string> = {}
    headers.forEach((header, headerIndex) => {
      rowObject[header] = (line[headerIndex] ?? '').trim()
    })

    const hasAnyValue = Object.values(rowObject).some((value) => value.length > 0)
    if (hasAnyValue) {
      rowObjects.push(rowObject)
    }
  }

  return { rowObjects, dataRows: rowObjects.length }
}

export const getFirstCellFromCsv = (content: string): string => {
  const parsed = parseCsvRows(content)
  if (!parsed.length || !parsed[0].length) {
    return ''
  }
  return parsed[0][0].trim()
}

export const listDatasetExtensions = async (
  dataset: DatasetValue,
): Promise<QslExtension<Record<string, any>, Record<string, any>>[]> => {
  const config = datasetConfigMap[dataset]
  return listExtensions<Record<string, any>, Record<string, any>>(config.plural)
}

export const listDatasetCount = async (dataset: DatasetValue): Promise<number> => {
  const items = await listDatasetExtensions(dataset)
  return items.length
}

export const buildDatasetCsv = (
  dataset: DatasetValue,
  items: QslExtension<Record<string, any>, Record<string, any>>[],
): string => {
  const config = datasetConfigMap[dataset]
  const header = [...config.headers]
  header[0] = `id#${dataset}`

  const rows = [header]
  items.forEach((item) => {
    const rowObject = config.toRow(item)
    rows.push(config.headers.map((field) => rowObject[field] ?? ''))
  })

  return rows.map((row) => row.map((cell) => escapeCsvCell(cell)).join(',')).join('\n')
}
