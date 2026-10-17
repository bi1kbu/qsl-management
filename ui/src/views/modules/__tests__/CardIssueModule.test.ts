import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type PropType } from 'vue'
import CardIssueModule from '../CardIssueModule.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  update: vi.fn(),
  audit: vi.fn(),
  policy: vi.fn(),
  send: vi.fn(),
}))
vi.mock('../../../api/qsl-extension-api', () => ({
  qslApiVersion: 'qsl-management.bi1kbu.com/v1alpha1',
  listExtensions: mocks.list,
  getExtensionOrNull: mocks.get,
  updateExtension: mocks.update,
}))
vi.mock('../../../api/qsl-audit-log-api', () => ({ appendQslAuditLog: mocks.audit }))
vi.mock('../../../api/qsl-console-api', () => ({
  applyNotificationMailPolicy: mocks.policy,
  sendNotificationMail: mocks.send,
}))
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    VButton: defineComponent({
      name: 'VButton',
      props: { disabled: Boolean },
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h(
            'button',
            { disabled: props.disabled, onClick: () => emit('click') },
            slots.default?.(),
          ),
    }),
    VCard: defineComponent({
      props: { title: String },
      setup:
        (props, { slots }) =>
        () =>
          h('section', { 'data-title': props.title }, slots.default?.()),
    }),
  }
})

interface TestExtension {
  apiVersion: string
  kind: string
  metadata: { name: string; version: number }
  spec: Record<string, unknown>
  status?: { flowStatus: string }
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const extension = (kind: string, name: string, spec: Record<string, unknown>): TestExtension => ({
  apiVersion: 'qsl-management.bi1kbu.com/v1alpha1',
  kind,
  metadata: { name, version: 3 },
  spec,
})
const card = (
  name: string,
  callSign: string,
  patch: Record<string, unknown> = {},
): TestExtension => ({
  ...extension('CardRecord', name, {
    callSign,
    cardType: 'EYEBALL',
    sceneType: 'ONLINE_EYEBALL',
    cardVersion: '本地回归测试卡片',
    addressEntryName: `${callSign.trim().toUpperCase()}-1`,
    mailTargetEmail: `${callSign.trim().toLowerCase()}@example.test`,
    cardIssued: true,
    envelopePrinted: false,
    cardSent: false,
    receiptConfirmed: false,
    createdMailStatus: '',
    ...patch,
  }),
  status: { flowStatus: '已制卡' },
})
const address = (callSign: string) =>
  extension('AddressBook', `${callSign}-1`, {
    callSign,
    email: `${callSign.toLowerCase()}@example.test`,
    name: '本地测试收件人',
    address: '本地测试地址',
  })

// 保留组件真实查询、按钮与插槽事件，只将无关的表格布局替换为可定位行。
const TableStub = defineComponent({
  name: 'QslDataTable',
  props: { rows: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
  setup:
    (props, { slots }) =>
    () =>
      h(
        'div',
        props.rows.map((row) =>
          h('div', { 'data-row': row.id }, [
            h('span', String(row.id ?? '')),
            slots['cell-callSign']?.({ row }),
            slots['row-actions']?.({ row }),
          ]),
        ),
      ),
})

let data: Record<string, TestExtension[]>
let wrapper: VueWrapper
const persist = (plural: string, name: string, body: TestExtension) => {
  const saved = clone(body)
  saved.metadata.version += 1
  data[plural] = data[plural]!.map((item) => (item.metadata.name === name ? saved : item))
  return clone(saved)
}
const deferred = () => {
  let resolve!: () => void
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
const mountPage = async (cards = [card('C9000', 'ZZ0AAA'), card('C9023', 'ZZ0BBB')]) => {
  data['card-records'] = cards
  wrapper = mount(CardIssueModule, {
    props: { sceneTypes: ['ONLINE_EYEBALL'], defaultSceneType: 'ONLINE_EYEBALL' },
    global: { stubs: { QslDataTable: TableStub } },
  })
  await flushPromises()
}
const section = (title: string) => wrapper.get(`[data-title="${title}"]`)
const button = (label: string, title = '制卡签发', row?: string) => {
  const parent = row ? section(title).get(`[data-row="${row}"]`) : section(title)
  const found = parent.findAll('button').find((item) => item.text() === label)
  if (!found) throw new Error(`未找到按钮：${title}/${row ?? ''}/${label}`)
  return found
}
const search = async (callSign: string, addressKeyword = 'ZZ0AAA') => {
  await wrapper.get('input[placeholder="输入呼号后查询卡片信息"]').setValue(callSign)
  await wrapper.get('input[placeholder="输入呼号或卡片局检索地址"]').setValue(addressKeyword)
  await button('查询').trigger('click')
  await flushPromises()
}
const selectAddress = async (id = 'ZZ0AAA-1') => {
  await button('选定地址', '收件地址', id).trigger('click')
  await flushPromises()
}
const pack = async (name: string) => {
  await button('确认打包', '待制卡', name).trigger('click')
  await flushPromises()
}
const savedCard = (name: string) =>
  data['card-records']!.find((item) => item.metadata.name === name)!
const bindingPreview = () => wrapper.get('.qsl-address-binding-targets').text()

beforeEach(() => {
  vi.resetAllMocks()
  data = {
    'card-records': [],
    'address-book-entries': [address('ZZ0AAA'), address('ZZ0BBB')],
    'bureau-entries': [
      extension('Bureau', 'BURO-1', { bureauName: '本地测试卡片局', address: '卡片局地址' }),
    ],
  }
  mocks.list.mockImplementation(async (plural: string) => clone(data[plural] ?? []))
  mocks.get.mockResolvedValue(null)
  mocks.update.mockImplementation(async (plural: string, name: string, body: TestExtension) =>
    persist(plural, name, body),
  )
  mocks.audit.mockResolvedValue(undefined)
  mocks.policy.mockResolvedValue({ status: 'PENDING', message: '等待手动发送' })
  mocks.send.mockRejectedValue(new Error('本地回归测试不允许发信'))
})
afterEach(() => {
  wrapper?.unmount()
  expect(mocks.send).not.toHaveBeenCalled()
})

describe('制卡签发地址绑定与打包隔离', () => {
  it('选中 ZZ0AAA 地址后直接打包 ZZ0BBB，保留后者的地址和邮箱', async () => {
    await mountPage()
    await search('ZZ0AAA')
    await selectAddress()
    expect(mocks.update).not.toHaveBeenCalled()
    await pack('C9023')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.update).toHaveBeenCalledWith(
      'card-records',
      'C9023',
      expect.objectContaining({
        metadata: { name: 'C9023', version: 3 },
        spec: expect.objectContaining({
          addressEntryName: 'ZZ0BBB-1',
          mailTargetEmail: 'zz0bbb@example.test',
          envelopePrinted: true,
        }),
        status: { flowStatus: '已打包' },
      }),
    )
    expect(savedCard('C9000').spec.envelopePrinted).toBe(false)
    expect(mocks.policy).toHaveBeenCalledWith(expect.objectContaining({ cardRecordName: 'C9023' }))
  })

  it.each([
    { addressEntryName: '', mailTargetEmail: '' },
    { addressEntryName: 'BURO-1', mailTargetEmail: '' },
    { addressEntryName: 'ZZ0BBB-1', mailTargetEmail: 'original@example.test' },
  ])('打包严格保留原地址和邮箱：%j', async (binding) => {
    await mountPage([card('C9023', 'ZZ0BBB', binding)])
    await search('ZZ0BBB')
    await selectAddress()
    await pack('C9023')
    expect(savedCard('C9023').spec).toMatchObject({ ...binding, envelopePrinted: true })
  })

  it('选定地址仅为候选，明确确认后才绑定规范化呼号完全一致且未完成寄送的卡片', async () => {
    await mountPage([
      card('C9023', 'ZZ0BBB'),
      card('C9024', '  zz0bbb  '),
      card('C9025', 'ZZ0BBB/P'),
      card('C9026', 'ZZ0BBB1'),
      card('C9027', 'ZZ0BBB', { envelopePrinted: true }),
      card('C9028', 'ZZ0BBB', { cardSent: true }),
      card('C9029', 'ZZ0BBB', { receiptConfirmed: true }),
    ])
    await search('  zz0bbb  ')
    await selectAddress()
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.audit).not.toHaveBeenCalled()
    expect(bindingPreview()).toContain('C9023')
    expect(bindingPreview()).toContain('C9024')
    for (const excluded of ['C9025', 'C9026', 'C9027', 'C9028', 'C9029'])
      expect(bindingPreview()).not.toContain(excluded)
    await button('确认绑定地址').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls.map((args) => args[1])).toEqual(['C9023', 'C9024'])
    for (const name of ['C9023', 'C9024'])
      expect(savedCard(name).spec).toMatchObject({
        addressEntryName: 'ZZ0AAA-1',
        mailTargetEmail: 'zz0aaa@example.test',
        envelopePrinted: false,
      })
    expect(mocks.audit).toHaveBeenCalledTimes(2)
    expect(mocks.policy).not.toHaveBeenCalled()
  })

  it('部分呼号查询不能把地址批量绑定到相似呼号', async () => {
    await mountPage()
    await search('ZZ0B')
    await selectAddress()
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('明确绑定卡片局时使用局地址并清空个人目标邮箱', async () => {
    await mountPage([card('C9023', 'ZZ0BBB')])
    await search('ZZ0BBB', 'BURO')
    await selectAddress('BURO-1')
    expect(mocks.update).not.toHaveBeenCalled()
    await button('确认绑定地址').trigger('click')
    await flushPromises()
    expect(savedCard('C9023').spec).toMatchObject({
      addressEntryName: 'BURO-1',
      mailTargetEmail: '',
    })
    expect(mocks.policy).not.toHaveBeenCalled()
  })

  it('查询另一个呼号后清空候选地址', async () => {
    await mountPage()
    await search('ZZ0AAA')
    await selectAddress()
    await search('ZZ0BBB')
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(button('选定地址', '收件地址', 'ZZ0AAA-1').exists()).toBe(true)
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('修改呼号但尚未查询时不能沿用旧呼号的绑定上下文', async () => {
    await mountPage()
    await search('ZZ0AAA')
    await selectAddress()
    await wrapper.get('input[placeholder="输入呼号后查询卡片信息"]').setValue('ZZ0BBB')
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(button('选定地址', '收件地址', 'ZZ0AAA-1').exists()).toBe(true)
    await selectAddress()
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('点击待制卡呼号后清空候选，不把该卡原地址隐式当成下一次绑定候选', async () => {
    await mountPage()
    await search('ZZ0AAA')
    await selectAddress()
    await section('待制卡').get('[data-row="C9023"] .qsl-row-clickable').trigger('click')
    await flushPromises()
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(mocks.update).not.toHaveBeenCalled()
    await pack('C9000')
    expect(savedCard('C9000').spec.addressEntryName).toBe('ZZ0AAA-1')
  })

  it('打包更新冲突时不记成功审计、不运行邮件策略', async () => {
    await mountPage()
    mocks.update.mockRejectedValueOnce(
      Object.assign(new Error('409：记录版本冲突'), { response: { status: 409 } }),
    )
    await pack('C9023')
    expect(savedCard('C9023').spec.envelopePrinted).toBe(false)
    expect(mocks.audit).not.toHaveBeenCalled()
    expect(mocks.policy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确认打包失败')
    expect(wrapper.text()).toContain('409')
  })

  it('打包请求未结束时阻止重复打包、其他行写入和切换地址', async () => {
    await mountPage()
    await search('ZZ0AAA')
    await selectAddress()
    const pending = deferred()
    mocks.update.mockImplementationOnce(
      async (plural: string, name: string, body: TestExtension) => {
        await pending.promise
        return persist(plural, name, body)
      },
    )
    await button('确认打包', '待制卡', 'C9023').trigger('click')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    for (const id of ['C9000', 'C9023']) {
      expect(button('确认打包', '待制卡', id).attributes('disabled')).toBeDefined()
      await button('确认打包', '待制卡', id).trigger('click')
    }
    expect(button('查询').attributes('disabled')).toBeDefined()
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.policy).not.toHaveBeenCalled()
    pending.resolve()
    await flushPromises()
    expect(mocks.policy).toHaveBeenCalledTimes(1)
    expect(savedCard('C9023').spec.addressEntryName).toBe('ZZ0BBB-1')
  })

  it('绑定过程中锁定目标并阻止重复确认和页面切换', async () => {
    await mountPage([card('C9023', 'ZZ0BBB'), card('C9024', 'ZZ0BBB'), card('C9000', 'ZZ0AAA')])
    await search('ZZ0BBB')
    await selectAddress()
    const pending = deferred()
    mocks.update.mockImplementationOnce(
      async (plural: string, name: string, body: TestExtension) => {
        await pending.promise
        return persist(plural, name, body)
      },
    )
    await button('确认绑定地址').trigger('click')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(button('确认绑定地址').attributes('disabled')).toBeDefined()
    expect(button('查询').attributes('disabled')).toBeDefined()
    await button('确认绑定地址').trigger('click')
    await section('待制卡').get('[data-row="C9000"] .qsl-row-clickable').trigger('click')
    expect(
      (wrapper.get('input[placeholder="输入呼号后查询卡片信息"]').element as HTMLInputElement)
        .value,
    ).toBe('ZZ0BBB')
    pending.resolve()
    await flushPromises()
    expect(mocks.update.mock.calls.map((args) => args[1])).toEqual(['C9023', 'C9024'])
    expect(savedCard('C9000').metadata.version).toBe(3)
    expect(mocks.policy).not.toHaveBeenCalled()
  })

  it('批量绑定中途冲突后停止，展示实际成功数量并保留未处理记录', async () => {
    await mountPage([card('C9023', 'ZZ0BBB'), card('C9024', 'ZZ0BBB'), card('C9025', 'ZZ0BBB')])
    await search('ZZ0BBB')
    await selectAddress()
    mocks.update
      .mockImplementationOnce(async (plural: string, name: string, body: TestExtension) =>
        persist(plural, name, body),
      )
      .mockRejectedValueOnce(new Error('409：记录版本冲突'))
    await button('确认绑定地址').trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls.map((args) => args[1])).toEqual(['C9023', 'C9024'])
    expect(savedCard('C9023').spec.addressEntryName).toBe('ZZ0AAA-1')
    expect(savedCard('C9024').spec.addressEntryName).toBe('ZZ0BBB-1')
    expect(savedCard('C9025').spec.addressEntryName).toBe('ZZ0BBB-1')
    expect(mocks.audit).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toMatch(/(?:已保存|已完成|已成功|已绑定)\s*1\s*(?:\/\s*3|条)/)
    expect(wrapper.text()).toContain('409')
    expect(mocks.policy).not.toHaveBeenCalled()
  })

  it('绑定已保存但审计失败时仍统计已保存记录并停止后续绑定', async () => {
    await mountPage([card('C9023', 'ZZ0BBB'), card('C9024', 'ZZ0BBB')])
    await search('ZZ0BBB')
    await selectAddress()
    mocks.audit.mockRejectedValueOnce(new Error('审计服务暂时不可用'))
    await button('确认绑定地址').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(savedCard('C9023').spec.addressEntryName).toBe('ZZ0AAA-1')
    expect(savedCard('C9024').spec.addressEntryName).toBe('ZZ0BBB-1')
    expect(wrapper.text()).toContain('已保存 1/2 条')
    expect(wrapper.text()).toContain('审计服务暂时不可用')
    expect(mocks.policy).not.toHaveBeenCalled()
  })
})
