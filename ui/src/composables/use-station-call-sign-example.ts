import { axiosInstance } from '@halo-dev/api-client'
import { onBeforeUnmount, onMounted, readonly, ref } from 'vue'

const defaultCallSign = 'BI1KBU'
const sharedCallSign = ref(defaultCallSign)
let stateVersion = 0
let activeRequest: Promise<void> | undefined

interface StationCallSignResponse {
  data?: { callSign?: string }
}

const normalizeCallSign = (callSign: string | undefined): string =>
  callSign?.trim().toUpperCase() || defaultCallSign

export const stationCallSignExample = readonly(sharedCallSign)

// 仅在持久化成功后调用，同时使保存前发出的读取请求失效。
export const updateStationCallSignExample = (callSign: string | undefined): void => {
  stateVersion += 1
  sharedCallSign.value = normalizeCallSign(callSign)
  activeRequest = undefined
}

export const refreshStationCallSignExample = (): Promise<void> => {
  if (activeRequest) return activeRequest
  const requestVersion = ++stateVersion
  const request = axiosInstance
    .get<StationCallSignResponse>('/apis/api.qsl-management.bi1kbu.com/v1alpha1/station-call-sign')
    .then((response) => {
      if (requestVersion === stateVersion) {
        sharedCallSign.value = normalizeCallSign(response.data.data?.callSign)
      }
    })
    .catch(() => {
      // 示例读取失败时保留已知呼号；首次读取失败则继续使用默认示例。
    })
    .finally(() => {
      // 保存后可能已有新请求，旧请求不得释放新请求的共享引用。
      if (activeRequest === request) activeRequest = undefined
    })
  activeRequest = request
  return request
}

export const useStationCallSignExample = () => {
  const handleFocus = () => {
    void refreshStationCallSignExample()
  }
  onMounted(() => {
    window.addEventListener('focus', handleFocus)
    void refreshStationCallSignExample()
  })
  onBeforeUnmount(() => {
    window.removeEventListener('focus', handleFocus)
  })
  return stationCallSignExample
}
