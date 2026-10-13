import { beforeEach, describe, expect, it, vi } from 'vitest'
const mockGet = vi.hoisted(() => vi.fn())
vi.mock('@halo-dev/api-client', () => ({ axiosInstance: { get: mockGet } }))
const response = (callSign?: string) => ({
  data: { code: 'QSL-0000', message: '成功', data: { callSign } },
})
const deferred = () => {
  let resolve!: (value: ReturnType<typeof response>) => void
  const promise = new Promise<ReturnType<typeof response>>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetModules()
  mockGet.mockReset()
})
describe('本台呼号示例', () => {
  it.each([undefined, '', '   '])('未配置或空白时保留默认示例：%s', async (callSign) => {
    mockGet.mockResolvedValueOnce(response(callSign))
    const state = await import('../use-station-call-sign-example')
    expect(state.stationCallSignExample.value).toBe('BI1KBU')
    await state.refreshStationCallSignExample()
    expect(state.stationCallSignExample.value).toBe('BI1KBU')
  })
  it('多个页面共享请求并使用持久化呼号', async () => {
    const pending = deferred()
    mockGet.mockReturnValueOnce(pending.promise)
    const state = await import('../use-station-call-sign-example')
    const first = state.refreshStationCallSignExample()
    expect(state.refreshStationCallSignExample()).toBe(first)
    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith(
      '/apis/api.qsl-management.bi1kbu.com/v1alpha1/station-call-sign',
    )
    pending.resolve(response(' bh1abc '))
    await first
    expect(state.stationCallSignExample.value).toBe('BH1ABC')
  })
  it('读取失败保留已知值且允许重试', async () => {
    mockGet.mockRejectedValueOnce(new Error('网络暂时不可用'))
    mockGet.mockResolvedValueOnce(response('BH2DEF'))
    const state = await import('../use-station-call-sign-example')
    state.updateStationCallSignExample('BH1ABC')
    await state.refreshStationCallSignExample()
    expect(state.stationCallSignExample.value).toBe('BH1ABC')
    await state.refreshStationCallSignExample()
    expect(state.stationCallSignExample.value).toBe('BH2DEF')
  })
  it('保存前的慢请求不会覆盖已保存呼号', async () => {
    const pending = deferred()
    mockGet.mockReturnValueOnce(pending.promise)
    const state = await import('../use-station-call-sign-example')
    const old = state.refreshStationCallSignExample()
    state.updateStationCallSignExample('BH2DEF')
    pending.resolve(response('BH1ABC'))
    await old
    expect(state.stationCallSignExample.value).toBe('BH2DEF')
  })
  it('旧请求结束不会释放保存后新请求的共享引用', async () => {
    const oldPending = deferred()
    const newPending = deferred()
    mockGet.mockReturnValueOnce(oldPending.promise).mockReturnValueOnce(newPending.promise)
    const state = await import('../use-station-call-sign-example')
    const old = state.refreshStationCallSignExample()
    state.updateStationCallSignExample('BH2DEF')
    const current = state.refreshStationCallSignExample()
    oldPending.resolve(response('BH1ABC'))
    await old
    expect(state.stationCallSignExample.value).toBe('BH2DEF')
    expect(state.refreshStationCallSignExample()).toBe(current)
    expect(mockGet).toHaveBeenCalledTimes(2)
    newPending.resolve(response('BH3GHI'))
    await current
    expect(state.stationCallSignExample.value).toBe('BH3GHI')
  })
})
