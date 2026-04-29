import { axiosInstance } from '@halo-dev/api-client'
import { isAxiosError } from 'axios'

const QSL_GROUP_VERSION = 'qsl-management.halo.run/v1alpha1'
const QSL_EXTENSION_BASE_PATH = `/apis/${QSL_GROUP_VERSION}`

export interface QslMetadata {
  name: string
  version?: number | null
  labels?: Record<string, string>
  annotations?: Record<string, string>
  creationTimestamp?: string
  deletionTimestamp?: string
}

export interface QslExtension<TSpec, TStatus = Record<string, unknown>> {
  apiVersion: string
  kind: string
  metadata: QslMetadata
  spec?: TSpec
  status?: TStatus
}

interface QslExtensionListResponse<TSpec, TStatus = Record<string, unknown>> {
  items: QslExtension<TSpec, TStatus>[]
}

export const qslApiVersion = QSL_GROUP_VERSION

const buildUrl = (plural: string, name?: string): string => {
  if (!name) {
    return `${QSL_EXTENSION_BASE_PATH}/${plural}`
  }
  return `${QSL_EXTENSION_BASE_PATH}/${plural}/${encodeURIComponent(name)}`
}

export const isNotFoundError = (error: unknown): boolean => {
  return isAxiosError(error) && error.response?.status === 404
}

const containsNotFoundMessage = (error: unknown): boolean => {
  const candidates: string[] = []

  if (error instanceof Error && error.message) {
    candidates.push(error.message)
  }

  if (typeof error === 'string') {
    candidates.push(error)
  }

  if (isAxiosError(error)) {
    const data = error.response?.data as any
    if (typeof data === 'string') {
      candidates.push(data)
    } else if (data && typeof data === 'object') {
      if (typeof data.message === 'string') {
        candidates.push(data.message)
      }
      if (typeof data.detail === 'string') {
        candidates.push(data.detail)
      }
      if (typeof data.title === 'string') {
        candidates.push(data.title)
      }
      if (typeof data.error === 'string') {
        candidates.push(data.error)
      }
    }
  }

  const mergedText = candidates.join(' ').toLowerCase()
  return mergedText.includes('was not found') || mergedText.includes('资源不存在') || mergedText.includes('404')
}

export const createResourceName = (prefix: string): string => {
  const randomPart = Math.random().toString(36).slice(2, 8)
  return `${prefix}-${Date.now()}-${randomPart}`
}

export async function listExtensions<TSpec, TStatus = Record<string, unknown>>(
  plural: string,
  options?: {
    sort?: string[]
  },
): Promise<QslExtension<TSpec, TStatus>[]> {
  const sort = options?.sort?.length ? options.sort : ['metadata.creationTimestamp,desc']
  const response = await axiosInstance.get<QslExtensionListResponse<TSpec, TStatus>>(buildUrl(plural), {
    params: {
      page: 1,
      size: 1000,
      sort,
    },
  })
  const items = Array.isArray(response.data?.items) ? response.data.items : []
  return items.filter((item) => !item.metadata?.deletionTimestamp)
}

export async function getExtensionOrNull<TSpec, TStatus = Record<string, unknown>>(
  plural: string,
  name: string,
): Promise<QslExtension<TSpec, TStatus> | null> {
  try {
    const response = await axiosInstance.get<QslExtension<TSpec, TStatus>>(buildUrl(plural, name))
    return response.data
  } catch (error) {
    if (isNotFoundError(error) || containsNotFoundMessage(error)) {
      return null
    }
    throw error
  }
}

export async function createExtension<TSpec, TStatus = Record<string, unknown>>(
  plural: string,
  extension: QslExtension<TSpec, TStatus>,
): Promise<QslExtension<TSpec, TStatus>> {
  const response = await axiosInstance.post<QslExtension<TSpec, TStatus>>(buildUrl(plural), extension)
  return response.data
}

export async function updateExtension<TSpec, TStatus = Record<string, unknown>>(
  plural: string,
  name: string,
  extension: QslExtension<TSpec, TStatus>,
): Promise<QslExtension<TSpec, TStatus>> {
  const response = await axiosInstance.put<QslExtension<TSpec, TStatus>>(buildUrl(plural, name), extension)
  return response.data
}

export async function deleteExtension(plural: string, name: string): Promise<void> {
  await axiosInstance.delete(buildUrl(plural, name))
}

export async function upsertSingleton<TSpec, TStatus = Record<string, unknown>>(params: {
  plural: string
  kind: string
  name: string
  spec: TSpec
  status?: TStatus
}): Promise<QslExtension<TSpec, TStatus>> {
  const current = await getExtensionOrNull<TSpec, TStatus>(params.plural, params.name)
  const payload: QslExtension<TSpec, TStatus> = {
    apiVersion: qslApiVersion,
    kind: params.kind,
    metadata: {
      name: params.name,
      version: current?.metadata.version,
    },
    spec: params.spec,
    status: params.status,
  }

  if (current) {
    return updateExtension(params.plural, params.name, payload)
  }

  return createExtension(params.plural, payload)
}
