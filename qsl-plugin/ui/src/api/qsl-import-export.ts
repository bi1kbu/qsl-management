import {
  createExtension,
  createResourceName,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from './qsl-extension-api'

export type DatasetValue =
  | 'qso-record'
  | 'card-record'
  | 'exchange-request-review'
  | 'address-management'
  | 'bureau-management'
  | 'equipment-catalog'

export type ImportStrategy = 'skip' | 'overwrite'

export interface ImportResult {
  total: number
  success: number
  skipped: number
  failed: number
}

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

const datasetConfigs: DatasetConfig[] = [
  {
    value: 'qso-record',
    label: '通联记录',
    plural: 'qso-records',
    kind: 'QsoRecord',
    idPrefix: 'qso-record',
    headers: [
      'id',
      'callSign',
      'date',
      'time',
      'timezone',
      'freq',
      'myRig',
      'myRigMode',
      'myRigAnt',
      'myRigPwr',
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
      date: String(item.spec?.date ?? ''),
      time: String(item.spec?.time ?? ''),
      timezone: String(item.spec?.timezone ?? ''),
      freq: String(item.spec?.freq ?? ''),
      myRig: String(item.spec?.myRig ?? ''),
      myRigMode: String(item.spec?.myRigMode ?? ''),
      myRigAnt: String(item.spec?.myRigAnt ?? ''),
      myRigPwr: String(item.spec?.myRigPwr ?? ''),
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
        date: row.date ?? '',
        time: row.time ?? '',
        timezone: row.timezone ?? 'UTC',
        freq: row.freq ?? '',
        myRig: row.myRig ?? '',
        myRigMode: row.myRigMode ?? '',
        myRigAnt: row.myRigAnt ?? '',
        myRigPwr: row.myRigPwr ?? '',
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
    idPrefix: 'card-record',
    headers: [
      'id',
      'callSign',
      'cardType',
      'cardVersion',
      'qsoRecordName',
      'cardDate',
      'cardTime',
      'cardRemarks',
      'cardSent',
      'cardReceived',
      'receiptConfirmed',
      'sentAt',
      'receivedAt',
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
      cardRemarks: String(item.spec?.cardRemarks ?? ''),
      cardSent: String(Boolean(item.spec?.cardSent)),
      cardReceived: String(Boolean(item.spec?.cardReceived)),
      receiptConfirmed: String(Boolean(item.spec?.receiptConfirmed)),
      sentAt: String(item.spec?.sentAt ?? ''),
      receivedAt: String(item.spec?.receivedAt ?? ''),
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
        cardType: row.cardType ?? 'QSO',
        cardVersion: row.cardVersion ?? '',
        qsoRecordName: row.qsoRecordName ?? '',
        cardDate: row.cardDate ?? '',
        cardTime: row.cardTime ?? '',
        cardRemarks: row.cardRemarks ?? '',
        cardSent: parseBoolean(row.cardSent ?? ''),
        cardReceived: parseBoolean(row.cardReceived ?? ''),
        receiptConfirmed: parseBoolean(row.receiptConfirmed ?? ''),
        sentAt: row.sentAt ?? '',
        receivedAt: row.receivedAt ?? '',
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
    ],
    keywords: ['exchange-request-review', 'exchange-request', '换卡申请'],
    toRow: (item) => ({
      id: item.metadata.name,
      callSign: String(item.spec?.callSign ?? ''),
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
    }),
    fromRow: (row) => ({
      spec: {
        callSign: row.callSign ?? '',
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
      },
    }),
  },
  {
    value: 'address-management',
    label: '地址管理',
    plural: 'address-book-entries',
    kind: 'AddressBookEntry',
    idPrefix: 'address-entry',
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
    idPrefix: 'bureau-entry',
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

export const importDatasetCsv = async (
  dataset: DatasetValue,
  csvContent: string,
  strategy: ImportStrategy,
): Promise<ImportResult> => {
  const config = datasetConfigMap[dataset]
  const { rowObjects } = parseCsvToRowObjects(csvContent)
  const existing = await listExtensions<Record<string, any>, Record<string, any>>(config.plural)
  const existingMap = new Map(existing.map((item) => [item.metadata.name, item]))

  const result: ImportResult = {
    total: rowObjects.length,
    success: 0,
    skipped: 0,
    failed: 0,
  }

  for (const rowObject of rowObjects) {
    const resourceName = rowObject.id || createResourceName(config.idPrefix)
    const converted = config.fromRow(rowObject)
    const existingItem = existingMap.get(resourceName)

    try {
      if (existingItem) {
        if (strategy === 'skip') {
          result.skipped += 1
          continue
        }
        await updateExtension<Record<string, any>, Record<string, any>>(config.plural, resourceName, {
          apiVersion: qslApiVersion,
          kind: config.kind,
          metadata: {
            name: resourceName,
            version: existingItem.metadata.version,
          },
          spec: converted.spec,
          status: converted.status,
        })
        result.success += 1
        continue
      }

      await createExtension<Record<string, any>, Record<string, any>>(config.plural, {
        apiVersion: qslApiVersion,
        kind: config.kind,
        metadata: {
          name: resourceName,
        },
        spec: converted.spec,
        status: converted.status,
      })
      result.success += 1
    } catch {
      result.failed += 1
    }
  }

  return result
}
