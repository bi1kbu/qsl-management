import { listExtensions, type QslExtension } from './qsl-extension-api'

export type DatasetValue =
  | 'qso-record'
  | 'card-record'
  | 'receive-record'
  | 'exchange-request-review'
  | 'offline-activity'
  | 'offline-exchange-card'
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

const parseNumber = (value: string): number => {
  const normalized = value.trim()
  if (!normalized) {
    return 0
  }
  const parsed = Number.parseFloat(normalized)
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
  return Array.isArray(value)
    ? value
        .map((item) => String(item))
        .filter(Boolean)
        .join('、')
    : ''
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
      'autoCreated',
      'source',
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
      autoCreated: String(Boolean(item.status?.autoCreated)),
      source: String(item.status?.source ?? ''),
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
      status: {
        autoCreated: parseBoolean(row.autoCreated ?? ''),
        source: row.source ?? '',
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
      'sceneType',
      'cardVersion',
      'qsoRecordName',
      'offlineActivityName',
      'addressEntryName',
      'cardDate',
      'cardTime',
      'businessRemarks',
      'createdRemarks',
      'sentRemarks',
      'receivedRemarks',
      'publicReceiptRemarks',
      'cardRemarks',
      'cardSent',
      'cardIssued',
      'envelopePrinted',
      'cardReceived',
      'receiptConfirmed',
      'cardIssuedAt',
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
      'flowStatus',
    ],
    keywords: ['card-record', 'card', '卡片记录'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      cardType: String(item.spec?.cardType ?? ''),
      sceneType: String(item.spec?.sceneType ?? ''),
      cardVersion: String(item.spec?.cardVersion ?? ''),
      qsoRecordName: String(item.spec?.qsoRecordName ?? ''),
      offlineActivityName: String(item.spec?.offlineActivityName ?? ''),
      addressEntryName: String(item.spec?.addressEntryName ?? ''),
      cardDate: String(item.spec?.cardDate ?? ''),
      cardTime: String(item.spec?.cardTime ?? ''),
      businessRemarks: String(item.spec?.businessRemarks ?? ''),
      createdRemarks: String(item.spec?.createdRemarks ?? ''),
      sentRemarks: String(item.spec?.sentRemarks ?? ''),
      receivedRemarks: String(item.spec?.receivedRemarks ?? ''),
      publicReceiptRemarks: String(item.spec?.publicReceiptRemarks ?? ''),
      cardRemarks: String(item.spec?.cardRemarks ?? ''),
      cardSent: String(Boolean(item.spec?.cardSent)),
      cardIssued: String(Boolean(item.spec?.cardIssued)),
      envelopePrinted: String(Boolean(item.spec?.envelopePrinted)),
      cardReceived: String(Boolean(item.spec?.cardReceived)),
      receiptConfirmed: String(Boolean(item.spec?.receiptConfirmed)),
      cardIssuedAt: String(item.spec?.cardIssuedAt ?? ''),
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
      flowStatus: String(item.status?.flowStatus ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        cardType: row.cardType ?? 'QSO',
        sceneType: row.sceneType ?? '',
        cardVersion: row.cardVersion ?? '',
        qsoRecordName: row.qsoRecordName ?? '',
        offlineActivityName: row.offlineActivityName ?? '',
        addressEntryName: row.addressEntryName ?? '',
        cardDate: row.cardDate ?? '',
        cardTime: row.cardTime ?? '',
        businessRemarks: row.businessRemarks ?? '',
        createdRemarks: row.createdRemarks ?? '',
        sentRemarks: row.sentRemarks ?? '',
        receivedRemarks: row.receivedRemarks ?? '',
        publicReceiptRemarks: row.publicReceiptRemarks ?? '',
        cardRemarks: row.cardRemarks ?? '',
        cardSent: parseBoolean(row.cardSent ?? ''),
        cardIssued: parseBoolean(row.cardIssued ?? ''),
        envelopePrinted: parseBoolean(row.envelopePrinted ?? ''),
        cardReceived: parseBoolean(row.cardReceived ?? ''),
        receiptConfirmed: parseBoolean(row.receiptConfirmed ?? ''),
        cardIssuedAt: row.cardIssuedAt ?? '',
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
      },
      status: {
        flowStatus: row.flowStatus ?? '',
      },
    }),
  },
  {
    value: 'receive-record',
    label: '收卡记录',
    plural: 'receive-records',
    kind: 'ReceiveRecord',
    idPrefix: 'receive-record',
    headers: [
      'id',
      'callSign',
      'cardType',
      'businessType',
      'offlineActivityName',
      'receivedDate',
      'receivedAt',
      'outboundCardNames',
      'matchStatus',
      'matchReason',
      'remarks',
      'syncStatus',
    ],
    keywords: ['receive-record', 'receive-records', '收卡记录'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
      cardType: String(item.spec?.cardType ?? ''),
      businessType: String(item.spec?.businessType ?? ''),
      offlineActivityName: String(item.spec?.offlineActivityName ?? ''),
      receivedDate: String(item.spec?.receivedDate ?? ''),
      receivedAt: String(item.spec?.receivedAt ?? ''),
      outboundCardNames: String(item.spec?.outboundCardNames ?? ''),
      matchStatus: String(item.spec?.matchStatus ?? ''),
      matchReason: String(item.spec?.matchReason ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
      syncStatus: String(item.status?.syncStatus ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        cardType: row.cardType ?? 'QSO',
        businessType: row.businessType ?? '',
        offlineActivityName: row.offlineActivityName ?? '',
        receivedDate: row.receivedDate ?? '',
        receivedAt: row.receivedAt ?? '',
        outboundCardNames: row.outboundCardNames ?? '',
        matchStatus: row.matchStatus ?? '',
        matchReason: row.matchReason ?? '',
        remarks: row.remarks ?? '',
      },
      status: {
        syncStatus: row.syncStatus ?? '',
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
      'sceneType',
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
      sceneType: String(item.spec?.sceneType ?? ''),
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
        sceneType: row.sceneType ?? 'QSO',
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
    value: 'offline-exchange-card',
    label: '线下换卡卡片',
    plural: 'offline-exchange-cards',
    kind: 'OfflineExchangeCard',
    idPrefix: 'offline-exchange-card',
    headers: [
      'id',
      'cardRecordName',
      'offlineActivityName',
      'callSign',
      'cardType',
      'cardVersion',
      'claimStatus',
      'sentStatus',
      'sentAt',
      'remarks',
      'flowStatus',
    ],
    keywords: ['offline-exchange-card', 'offline-exchange-cards', '线下换卡卡片'],
    toRow: (item) => ({
      id: item.metadata.name,
      cardRecordName: String(item.spec?.cardRecordName ?? ''),
      offlineActivityName: String(item.spec?.offlineActivityName ?? ''),
      callSign: String(item.spec?.callSign ?? ''),
      cardType: String(item.spec?.cardType ?? ''),
      cardVersion: String(item.spec?.cardVersion ?? ''),
      claimStatus: String(item.spec?.claimStatus ?? ''),
      sentStatus: String(item.spec?.sentStatus ?? ''),
      sentAt: String(item.spec?.sentAt ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
      flowStatus: String(item.status?.flowStatus ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        cardRecordName: row.cardRecordName ?? '',
        offlineActivityName: row.offlineActivityName ?? '',
        callSign: row.callSign ?? '',
        cardType: row.cardType ?? 'EYEBALL',
        cardVersion: row.cardVersion ?? '',
        claimStatus: row.claimStatus ?? '',
        sentStatus: row.sentStatus ?? '',
        sentAt: row.sentAt ?? '',
        remarks: row.remarks ?? '',
      },
      status: {
        flowStatus: row.flowStatus ?? '',
      },
    }),
  },
  {
    value: 'address-management',
    label: '地址管理',
    plural: 'address-book-entries',
    kind: 'AddressBookEntry',
    idPrefix: 'address',
    headers: [
      'id',
      'callSign',
      'name',
      'telephone',
      'postalCode',
      'address',
      'email',
      'addressRemarks',
      'syncStatus',
    ],
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
      syncStatus: String(item.status?.syncStatus ?? ''),
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
      status: {
        syncStatus: row.syncStatus ?? '',
      },
    }),
  },
  {
    value: 'bureau-management',
    label: '卡片局管理',
    plural: 'bureau-entries',
    kind: 'BureauEntry',
    idPrefix: 'buro',
    headers: [
      'id',
      'bureauName',
      'telephone',
      'postalCode',
      'address',
      'addressRemarks',
      'syncStatus',
    ],
    keywords: ['bureau-management', 'bureau', '卡片局管理'],
    toRow: (item) => ({
      id: item.metadata.name,
      bureauName: String(item.spec?.bureauName ?? ''),
      telephone: String(item.spec?.telephone ?? ''),
      postalCode: String(item.spec?.postalCode ?? ''),
      address: String(item.spec?.address ?? ''),
      addressRemarks: String(item.spec?.addressRemarks ?? ''),
      syncStatus: String(item.status?.syncStatus ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        bureauName: row.bureauName ?? '',
        telephone: row.telephone ?? '',
        postalCode: row.postalCode ?? '',
        address: row.address ?? '',
        addressRemarks: row.addressRemarks ?? '',
      },
      status: {
        syncStatus: row.syncStatus ?? '',
      },
    }),
  },
  {
    value: 'equipment-catalog',
    label: '设备库维护',
    plural: 'equipment-catalog-entries',
    kind: 'EquipmentCatalogEntry',
    idPrefix: 'equipment-catalog',
    headers: ['id', 'type', 'value', 'remarks', 'enabled'],
    keywords: ['equipment-catalog', 'equipment', '设备库维护'],
    toRow: (item) => ({
      id: item.metadata.name,
      type: String(item.spec?.type ?? ''),
      value: String(item.spec?.value ?? ''),
      remarks: String(item.spec?.remarks ?? ''),
      enabled: String(Boolean(item.status?.enabled)),
    }),
    fromRow: (row) => ({
      spec: {
        type: row.type ?? '',
        value: row.value ?? '',
        remarks: row.remarks ?? '',
      },
      status: {
        enabled: parseBoolean(row.enabled ?? ''),
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
      'aiEnabled',
      'aiProvider',
      'aiBaseUrl',
      'aiModel',
      'aiSecretName',
      'aiTemperature',
      'aiTimeoutSeconds',
      'aiMaxInputCharacters',
      'aiOnlineImportParseEnabled',
      'aiAddressCleanupEnabled',
      'aiSystemPrompt',
      'aiOnlineImportPrompt',
      'aiAddressCleanupPrompt',
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
      onlineAutoNotifyOnExchangeReviewed: String(
        Boolean(item.spec?.onlineAutoNotifyOnExchangeReviewed),
      ),
      offlineAutoNotifyOnCardReceived: String(Boolean(item.spec?.offlineAutoNotifyOnCardReceived)),
      cardRecordSequence: String(item.spec?.cardRecordSequence ?? ''),
      receiveRecordSequence: String(item.spec?.receiveRecordSequence ?? ''),
      aiEnabled: String(Boolean(item.spec?.aiEnabled)),
      aiProvider: String(item.spec?.aiProvider ?? ''),
      aiBaseUrl: String(item.spec?.aiBaseUrl ?? ''),
      aiModel: String(item.spec?.aiModel ?? ''),
      aiSecretName: String(item.spec?.aiSecretName ?? ''),
      aiTemperature: String(item.spec?.aiTemperature ?? ''),
      aiTimeoutSeconds: String(item.spec?.aiTimeoutSeconds ?? ''),
      aiMaxInputCharacters: String(item.spec?.aiMaxInputCharacters ?? ''),
      aiOnlineImportParseEnabled: String(Boolean(item.spec?.aiOnlineImportParseEnabled)),
      aiAddressCleanupEnabled: String(Boolean(item.spec?.aiAddressCleanupEnabled)),
      aiSystemPrompt: String(item.spec?.aiSystemPrompt ?? ''),
      aiOnlineImportPrompt: String(item.spec?.aiOnlineImportPrompt ?? ''),
      aiAddressCleanupPrompt: String(item.spec?.aiAddressCleanupPrompt ?? ''),
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
        onlineAutoNotifyOnExchangeReviewed: parseBoolean(
          row.onlineAutoNotifyOnExchangeReviewed ?? '',
        ),
        offlineAutoNotifyOnCardReceived: parseBoolean(row.offlineAutoNotifyOnCardReceived ?? ''),
        cardRecordSequence: parseInteger(row.cardRecordSequence ?? ''),
        receiveRecordSequence: parseInteger(row.receiveRecordSequence ?? ''),
        aiEnabled: parseBoolean(row.aiEnabled ?? ''),
        aiProvider: row.aiProvider ?? '',
        aiBaseUrl: row.aiBaseUrl ?? '',
        aiModel: row.aiModel ?? '',
        aiSecretName: row.aiSecretName ?? '',
        aiTemperature: parseNumber(row.aiTemperature ?? ''),
        aiTimeoutSeconds: parseInteger(row.aiTimeoutSeconds ?? ''),
        aiMaxInputCharacters: parseInteger(row.aiMaxInputCharacters ?? ''),
        aiOnlineImportParseEnabled: parseBoolean(row.aiOnlineImportParseEnabled ?? ''),
        aiAddressCleanupEnabled: parseBoolean(row.aiAddressCleanupEnabled ?? ''),
        aiSystemPrompt: row.aiSystemPrompt ?? '',
        aiOnlineImportPrompt: row.aiOnlineImportPrompt ?? '',
        aiAddressCleanupPrompt: row.aiAddressCleanupPrompt ?? '',
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

const datasetConfigMap = Object.fromEntries(
  datasetConfigs.map((config) => [config.value, config]),
) as Record<DatasetValue, DatasetConfig>

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
  return value
    .toLowerCase()
    .replace(/[\uFEFF"']/g, '')
    .replace(/\s+/g, '')
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

export const parseCsvToRowObjects = (
  content: string,
): { rowObjects: Record<string, string>[]; dataRows: number } => {
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
  const headers = rawHeaders.map(
    (header, index) => header || (index === 0 ? 'id' : `field${index}`),
  )
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
