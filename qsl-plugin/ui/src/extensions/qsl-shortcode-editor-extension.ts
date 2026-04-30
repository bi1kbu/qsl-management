import {
  Decoration,
  DecorationSet,
  Extension,
  Plugin,
  PluginKey,
  ToolboxItem,
  type AnyExtension,
  type Editor,
  type PMNode,
} from '@halo-dev/richtext-editor'
import { markRaw } from 'vue'
import RiArticleLine from '~icons/ri/article-line'
import RiMailSendLine from '~icons/ri/mail-send-line'
import RiExchangeLine from '~icons/ri/exchange-line'

const SHORTCODE_PATTERN = /^\s*\[(qsl-card|qsl-receipt-card|qsl-exchange-card)([^\]]*)\]\s*$/i
const CALLSIGN_ATTR_PATTERN = /callSign\s*=\s*("([^"]*)"|'([^']*)'|([^\s\]]+))/i
const CARD_ID_ATTR_PATTERN = /cardId\s*=\s*("([^"]*)"|'([^']*)'|([^\s\]]+))/i
const SCENE_TYPE_ATTR_PATTERN = /sceneType\s*=\s*("([^"]*)"|'([^']*)'|([^\s\]]+))/i
const shortcodePreviewPluginKey = new PluginKey('qsl-shortcode-preview-plugin')

interface ParsedShortcode {
  type: 'qsl-card' | 'qsl-receipt-card' | 'qsl-exchange-card'
  title: string
  subtitle: string
}

function insertShortcode(editor: Editor, shortcode: string) {
  editor
    .chain()
    .focus()
    .insertContent({
      type: 'paragraph',
      content: [{ type: 'text', text: shortcode }],
    })
    .run()
}

function extractAttrValue(source: string, pattern: RegExp): string {
  const match = source.match(pattern)
  if (!match) {
    return ''
  }
  return (match[2] ?? match[3] ?? match[4] ?? '').trim().toUpperCase()
}

function parseShortcode(text: string): ParsedShortcode | null {
  const match = text.match(SHORTCODE_PATTERN)
  if (!match) {
    return null
  }
  const type = match[1].toLowerCase() as ParsedShortcode['type']
  const attrs = match[2] ?? ''
  const callSign = extractAttrValue(attrs, CALLSIGN_ATTR_PATTERN)
  const sceneType = extractAttrValue(attrs, SCENE_TYPE_ATTR_PATTERN).toUpperCase()
  if (type === 'qsl-card') {
    return {
      type,
      title: 'QSL 查询卡片',
      subtitle: callSign ? `呼号：${callSign}` : '',
    }
  }
  if (type === 'qsl-exchange-card') {
    const sceneText = sceneType === 'EYEBALL' ? '线下换卡' : '线上换卡'
    return {
      type,
      title: 'QSL 换卡申请卡片',
      subtitle: `${sceneText}${callSign ? ` · 呼号：${callSign}` : ''}`,
    }
  }
  const cardId = extractAttrValue(attrs, CARD_ID_ATTR_PATTERN)
  const parts: string[] = []
  if (callSign) {
    parts.push(`呼号：${callSign}`)
  }
  if (cardId) {
    parts.push(`卡片ID：${cardId}`)
  }
  return {
    type,
    title: 'QSL 签收卡片',
    subtitle: parts.join('  '),
  }
}

function createPreviewDecorations(doc: PMNode): DecorationSet {
  const decorations: Decoration[] = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock || !node.textContent) {
      return
    }
    const parsed = parseShortcode(node.textContent)
    if (!parsed) {
      return
    }
    const contentFrom = pos + 1
    const contentTo = pos + node.nodeSize - 1
    if (contentTo <= contentFrom) {
      return
    }

    decorations.push(
      Decoration.inline(contentFrom, contentTo, {
        class: 'qsl-shortcode-preview-hidden',
      }),
    )
    decorations.push(
      Decoration.widget(
        contentFrom,
        () => {
          const card = document.createElement('div')
          card.className = `qsl-shortcode-preview-card qsl-shortcode-preview-card--${parsed.type}`

          const title = document.createElement('div')
          title.className = 'qsl-shortcode-preview-card__title'
          title.textContent = parsed.title
          card.appendChild(title)

          if (parsed.subtitle) {
            const subtitle = document.createElement('div')
            subtitle.className = 'qsl-shortcode-preview-card__subtitle'
            subtitle.textContent = parsed.subtitle
            card.appendChild(subtitle)
          }

          return card
        },
        {
          side: -1,
          ignoreSelection: true,
        },
      ),
    )
  })
  return DecorationSet.create(doc, decorations)
}

const qslShortcodeEditorExtension = Extension.create({
  name: 'qsl-shortcode-editor-extension',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: shortcodePreviewPluginKey,
        state: {
          init(_, state) {
            return createPreviewDecorations(state.doc)
          },
          apply(transaction, oldDecorationSet, _, newState) {
            if (!transaction.docChanged) {
              return oldDecorationSet
            }
            return createPreviewDecorations(newState.doc)
          },
        },
        props: {
          decorations(state) {
            return this.getState(state)
          },
        },
      }),
    ]
  },
  addOptions() {
    return {
      ...this.parent?.(),
      getToolboxItems({ editor }: { editor: Editor }) {
        return [
          {
            priority: 95,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiArticleLine),
              title: '插入 QSL 查询卡片',
              description: '插入前台查询卡片短码',
              action: () => insertShortcode(editor, '[qsl-card]'),
            },
          },
          {
            priority: 96,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiMailSendLine),
              title: '插入 QSL 签收卡片',
              description: '插入前台签收卡片短码',
              action: () => insertShortcode(editor, '[qsl-receipt-card]'),
            },
          },
          {
            priority: 97,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiExchangeLine),
              title: '插入线上换卡申请卡片',
              description: '插入线上换卡申请短码',
              action: () => insertShortcode(editor, '[qsl-exchange-card sceneType="ONLINE_EYEBALL"]'),
            },
          },
          {
            priority: 98,
            component: markRaw(ToolboxItem),
            props: {
              editor,
              icon: markRaw(RiExchangeLine),
              title: '插入线下换卡申请卡片',
              description: '插入线下换卡申请短码',
              action: () => insertShortcode(editor, '[qsl-exchange-card sceneType="EYEBALL"]'),
            },
          },
        ]
      },
      getCommandMenuItems() {
        return [
          {
            priority: 150,
            icon: markRaw(RiArticleLine),
            title: '插入 QSL 查询卡片',
            keywords: ['qsl', '查询', '卡片', 'card'],
            command: ({ editor, range }: { editor: Editor; range: { from: number; to: number } }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .insertContent('[qsl-card]')
                .run()
            },
          },
          {
            priority: 151,
            icon: markRaw(RiMailSendLine),
            title: '插入 QSL 签收卡片',
            keywords: ['qsl', '签收', '回执', 'receipt'],
            command: ({ editor, range }: { editor: Editor; range: { from: number; to: number } }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .insertContent('[qsl-receipt-card]')
                .run()
              },
          },
          {
            priority: 152,
            icon: markRaw(RiExchangeLine),
            title: '插入线上换卡申请卡片',
            keywords: ['qsl', '线上换卡', '申请', 'exchange'],
            command: ({ editor, range }: { editor: Editor; range: { from: number; to: number } }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .insertContent('[qsl-exchange-card sceneType="ONLINE_EYEBALL"]')
                .run()
            },
          },
          {
            priority: 153,
            icon: markRaw(RiExchangeLine),
            title: '插入线下换卡申请卡片',
            keywords: ['qsl', '线下换卡', '申请', 'exchange'],
            command: ({ editor, range }: { editor: Editor; range: { from: number; to: number } }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .insertContent('[qsl-exchange-card sceneType="EYEBALL"]')
                .run()
            },
          },
        ]
      },
    }
  },
})

export function createQslEditorExtensions(): AnyExtension[] {
  return [qslShortcodeEditorExtension]
}
