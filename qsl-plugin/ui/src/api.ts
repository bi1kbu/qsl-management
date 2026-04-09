type Query = Record<string, string | number | undefined | null>

function queryString(query?: Query): string {
  if (!query) return ''
  const params = new URLSearchParams()
  Object.entries(query).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    params.set(k, String(v))
  })
  const s = params.toString()
  return s ? `?${s}` : ''
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers || {}),
    },
    ...init,
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || `HTTP ${res.status}`)
  }
  const ct = res.headers.get('content-type') || ''
  if (!ct.includes('application/json')) {
    return (null as T)
  }
  return res.json() as Promise<T>
}

export const adminApi = {
  getStationProfile() {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/station-profile')
  },
  updateStationProfile(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/station-profile', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  getSystemConfig() {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/system-config')
  },
  updateSystemConfig(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/system-config', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  listBureaus() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/bureau-configs')
  },
  createBureau(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/bureau-configs', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listEquipments() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/equipments')
  },
  createEquipment(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/equipments', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listAntennas() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/antennas')
  },
  createAntenna(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/antennas', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listPowers() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/power-presets')
  },
  createPower(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/power-presets', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listModes() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/modes')
  },
  createMode(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/modes', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  getSummary() {
    return request<Record<string, number>>('/apis/qsl.admin/v1/reports/summary')
  },
  getDashboard(filters: Query) {
    return request<Array<Record<string, unknown>>>(
      `/apis/qsl.admin/v1/dashboard/overview${queryString(filters)}`,
    )
  },
  listQso() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/qso-records')
  },
  createQso(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/qso-records', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listCards() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/qsl-card-records')
  },
  createCard(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/qsl-card-records', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  sendConfirm(cardIds: number[], isReissue = false, batchNo?: string) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/qsl-card-records/send-confirm', {
      method: 'POST',
      body: JSON.stringify({ cardIds, isReissue, batchNo }),
    })
  },
  receiveConfirm(cardIds: number[], receiveRemark?: string) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/qsl-card-records/receive-confirm', {
      method: 'POST',
      body: JSON.stringify({ cardIds, receiveRemark }),
    })
  },
  receiveConfirmByCallsign(payload: {
    callsign: string
    name?: string
    address?: string
    postcode?: string
    phone?: string
    email?: string
    receiveRemark?: string
  }) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/qsl-card-records/receive-confirm', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  getAuditLogs(filters: Query) {
    return request<Array<Record<string, unknown>>>(`/apis/qsl.admin/v1/audit-logs${queryString(filters)}`)
  },
  listExchangeRequests() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/exchange-requests')
  },
  approveExchangeRequest(id: number) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/exchange-requests/${id}/approve`, {
      method: 'POST',
    })
  },
  rejectExchangeRequest(id: number, reason: string) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/exchange-requests/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
  },
  listCallsignBindings() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/callsign-bindings')
  },
  approveCallsignBinding(id: number) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/callsign-bindings/${id}/approve`, {
      method: 'POST',
    })
  },
  rejectCallsignBinding(id: number, reason: string) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/callsign-bindings/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
  },
  listImportExportTasks() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/import-export-tasks')
  },
  backupExport() {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/backup/export', {
      method: 'POST',
    })
  },
  backupImport(payload?: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/backup/import', {
      method: 'POST',
      body: JSON.stringify(payload || {}),
    })
  },
  backupImportFile(file: File, dataset: string) {
    const form = new FormData()
    form.append('file', file)
    if (dataset) {
      form.append('dataset', dataset)
    }
    return fetch('/apis/qsl.admin/v1/backup/import-file', {
      method: 'POST',
      body: form,
    }).then(async (res) => {
      if (!res.ok) {
        const text = await res.text()
        throw new Error(text || `HTTP ${res.status}`)
      }
      return res.json()
    })
  },
  getReportMonthly() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/reports/trend/monthly')
  },
  getReportTypeDistribution() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/reports/card-type-distribution')
  },
}
