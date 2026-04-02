import { h, markRaw } from 'vue'
import { defineComponent } from 'vue'
import { IconDashboard, IconExchange, IconSearch, IconCheckboxCircle } from '@halo-dev/components'
import type { AnyExtension } from '@halo-dev/richtext-editor'
import { Extension, ToolboxItem } from '@halo-dev/richtext-editor'

type CardDef = {
  name: string
  title: string
  description: string
  icon: unknown
  url: string
  keywords: string[]
}

const cardDefs: CardDef[] = [
  {
    name: 'qslQueryCard',
    title: 'QSL 查询卡片',
    description: '插入前台 QSL 查询组件',
    icon: markRaw(IconSearch),
    url: '/plugins/qsl-management/widgets/query',
    keywords: ['qsl', 'query', 'callsign', '查询'],
  },
  {
    name: 'qslReissueCard',
    title: 'QSL 补卡申请卡片',
    description: '插入前台补卡申请组件',
    icon: markRaw(IconExchange),
    url: '/plugins/qsl-management/widgets/reissue',
    keywords: ['qsl', 'reissue', '补卡', '申请'],
  },
  {
    name: 'qslReceiveConfirmCard',
    title: 'QSL 收信确认卡片',
    description: '插入前台收信确认组件',
    icon: markRaw(IconCheckboxCircle),
    url: '/plugins/qsl-management/widgets/receive-confirm',
    keywords: ['qsl', 'receive', 'confirm', '收信'],
  },
  {
    name: 'qslStatsCard',
    title: 'QSL 统计卡片',
    description: '插入前台统计数据组件',
    icon: markRaw(IconDashboard),
    url: '/plugins/qsl-management/widgets/stats',
    keywords: ['qsl', 'stats', 'report', '统计'],
  },
]

function insertWidget(editor: any, url: string, title: string) {
  const iframeCommand = editor?.chain?.().focus?.().setIframe
  if (typeof iframeCommand === 'function') {
    editor.chain().focus().setIframe({ src: url }).run()
    return
  }
  editor
    .chain()
    .focus()
    .insertContent(`<p><a href="${url}" target="_blank" rel="noopener noreferrer">${title}</a></p>`)
    .run()
}

function createEditorCardExtension(def: CardDef): AnyExtension {
  return Extension.create({
    name: def.name,
    addOptions() {
      return {
        getToolboxItems: ({ editor }: { editor: any }) => ({
          priority: 500,
          component: defineComponent({
            name: `${def.name}ToolboxItem`,
            setup() {
              return () =>
                h(ToolboxItem, {
                  editor,
                  icon: def.icon as any,
                  title: def.title,
                  description: def.description,
                  action: () => insertWidget(editor, def.url, def.title),
                })
            },
          }),
          props: { editor },
        }),
        getCommandMenuItems: () => ({
          priority: 500,
          icon: def.icon as any,
          title: def.title,
          keywords: def.keywords,
          command: ({ editor }: { editor: any }) => insertWidget(editor, def.url, def.title),
        }),
      }
    },
  })
}

export function createQslEditorExtensions(): AnyExtension[] {
  return cardDefs.map((def) => createEditorCardExtension(def))
}
