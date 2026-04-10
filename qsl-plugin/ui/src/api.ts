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
  const mergedHeaders = {
    'Content-Type': 'application/json',
    ...((init?.headers || {}) as Record<string, string>),
  }
  const res = await fetch(path, {
    ...(init || {}),
    headers: mergedHeaders,
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

let currentUserIdCache: string | null = null
let currentUserAccessCache:
  | {
      userName: string
      roleNames: string[]
      uiPermissions: string[]
      isAdmin: boolean
    }
  | null = null

async function getCurrentUserId(): Promise<string> {
  if (currentUserIdCache) return currentUserIdCache
  try {
    const me = await request<Record<string, unknown>>('/apis/api.console.halo.run/v1alpha1/users/-')
    const user = ((me.user || me) as Record<string, unknown>) || {}
    const metadata = (user.metadata || {}) as Record<string, unknown>
    const spec = (user.spec || {}) as Record<string, unknown>
    const name = String(metadata.name || spec.displayName || '').trim()
    currentUserIdCache = name || 'console-user'
  } catch {
    currentUserIdCache = 'console-user'
  }
  return currentUserIdCache
}

function parseUiPermissions(roles: Array<Record<string, unknown>>): string[] {
  const values: string[] = []
  roles.forEach((role) => {
    const metadata = (role.metadata || {}) as Record<string, unknown>
    const annotations = (metadata.annotations || {}) as Record<string, unknown>
    const raw = String(annotations['rbac.authorization.halo.run/ui-permissions'] || '').trim()
    if (!raw) return
    try {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        parsed.forEach((item) => {
          const v = String(item || '').trim()
          if (v) values.push(v)
        })
      }
    } catch {
      // ignore invalid annotation payload
    }
  })
  return Array.from(new Set(values))
}

async function getCurrentUserAccess(): Promise<{
  userName: string
  roleNames: string[]
  uiPermissions: string[]
  isAdmin: boolean
}> {
  if (currentUserAccessCache) return currentUserAccessCache
  try {
    const data = await request<Record<string, unknown>>('/apis/api.console.halo.run/v1alpha1/users/-')
    const user = (data.user || {}) as Record<string, unknown>
    const roles = ((data.roles || []) as Array<Record<string, unknown>>) || []
    const userMeta = (user.metadata || {}) as Record<string, unknown>
    const userName = String(userMeta.name || '').trim() || 'console-user'
    const roleNames = roles
      .map((role) => String(((role.metadata || {}) as Record<string, unknown>).name || '').trim())
      .filter(Boolean)
    const uiPermissions = parseUiPermissions(roles)
    const isAdmin =
      roleNames.includes('super-role') ||
      uiPermissions.includes('*')
    currentUserAccessCache = { userName, roleNames, uiPermissions, isAdmin }
    if (!currentUserIdCache) {
      currentUserIdCache = userName
    }
  } catch {
    currentUserAccessCache = {
      userName: currentUserIdCache || 'console-user',
      roleNames: [],
      uiPermissions: [],
      isAdmin: false,
    }
  }
  return currentUserAccessCache
}

async function myRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const userId = await getCurrentUserId()
  const headers = {
    ...(init?.headers || {}),
    'X-User-Id': userId,
    'X-Operator': userId,
  }
  return request<T>(path, { ...init, headers })
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
  updateQso(id: number, payload: Record<string, unknown>) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/qso-records/${id}`, {
      method: 'PUT',
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
  updateCard(id: number, payload: Record<string, unknown>) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/qsl-card-records/${id}`, {
      method: 'PUT',
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
  unbindCallsignBinding(id: number) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/callsign-bindings/${id}/unbind`, {
      method: 'POST',
    })
  },
  listImportExportTasks() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/import-export-tasks')
  },
  listAddresses() {
    return request<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/address-books')
  },
  createAddress(payload: Record<string, unknown>) {
    return request<Record<string, unknown>>('/apis/qsl.admin/v1/address-books', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  updateAddress(id: number, payload: Record<string, unknown>) {
    return request<Record<string, unknown>>(`/apis/qsl.admin/v1/address-books/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  deleteAddress(id: number) {
    return request<{ deleted: boolean }>(`/apis/qsl.admin/v1/address-books/${id}`, {
      method: 'DELETE',
    })
  },
  backupExport() {
    return fetch('/apis/qsl.admin/v1/backup/export', {
      method: 'POST',
    }).then(async (res) => {
      if (!res.ok) {
        const text = await res.text()
        throw new Error(text || `HTTP ${res.status}`)
      }
      const blob = await res.blob()
      const cd = res.headers.get('content-disposition') || ''
      const matched = cd.match(/filename=\"?([^\";]+)\"?/)
      const fileName = matched?.[1] || 'qsl-full-backup.json'
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      window.URL.revokeObjectURL(url)
      return { downloaded: true, fileName }
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
  getCurrentUserAccess,
}

export const myApi = {
  listBindings() {
    return myRequest<Array<Record<string, unknown>>>('/apis/qsl.admin/v1/my/callsign-bindings')
  },
  createBinding(payload: Record<string, unknown>) {
    return myRequest<Record<string, unknown>>('/apis/qsl.admin/v1/my/callsign-bindings', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  searchCallsignRecords(callsign: string) {
    return myRequest<Record<string, unknown>>(
      `/apis/qsl.admin/v1/my/callsign-records/search${queryString({ callsign })}`,
    )
  },
  listAddresses(callsign?: string) {
    return myRequest<Array<Record<string, unknown>>>(
      `/apis/qsl.admin/v1/my/address-books${queryString({ callsign })}`,
    )
  },
  createAddress(payload: Record<string, unknown>) {
    return myRequest<Record<string, unknown>>('/apis/qsl.admin/v1/my/address-books', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  updateAddress(id: number, payload: Record<string, unknown>) {
    return myRequest<Record<string, unknown>>(`/apis/qsl.admin/v1/my/address-books/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  deleteAddress(id: number) {
    return myRequest<{ deleted: boolean }>(`/apis/qsl.admin/v1/my/address-books/${id}`, {
      method: 'DELETE',
    })
  },
  listQso(callsign?: string) {
    return myRequest<Array<Record<string, unknown>>>(
      `/apis/qsl.admin/v1/my/qso-records${queryString({ callsign })}`,
    )
  },
  listCards(callsign?: string) {
    return myRequest<Array<Record<string, unknown>>>(
      `/apis/qsl.admin/v1/my/qsl-card-records${queryString({ callsign })}`,
    )
  },
}
