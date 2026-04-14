<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive } from 'vue'

interface SendConfirmItem {
  id: string
  callSign: string
  cardType: string
  cardDate: string
  cardPrintAt: string
  envelopePrintAt: string
  cardRemarks: string
  sent: boolean
  sentAt: string
}

const rows = reactive<SendConfirmItem[]>([
  {
    id: 'CARD-1001',
    callSign: 'JA1ABC',
    cardType: 'QSO',
    cardDate: '2026-04-10',
    cardPrintAt: '2026-04-11 09:30',
    envelopePrintAt: '2026-04-11 10:20',
    cardRemarks: '优先寄送',
    sent: false,
    sentAt: '',
  },
  {
    id: 'CARD-1002',
    callSign: 'VK3XYZ',
    cardType: 'SWL',
    cardDate: '2026-04-09',
    cardPrintAt: '未制卡',
    envelopePrintAt: '未打印',
    cardRemarks: '',
    sent: false,
    sentAt: '',
  },
])

const markAsSent = (row: SendConfirmItem) => {
  row.sent = true
  row.sentAt = new Date().toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="发信确认清单">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>卡片ID</th>
              <th>对方呼号</th>
              <th>卡片类型</th>
              <th>卡片创建日期</th>
              <th>卡片打印日期</th>
              <th>信封打印日期</th>
              <th>卡片备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ row.callSign }}</td>
              <td>{{ row.cardType }}</td>
              <td>{{ row.cardDate }}</td>
              <td>{{ row.cardPrintAt }}</td>
              <td>{{ row.envelopePrintAt }}</td>
              <td>{{ row.cardRemarks || '无' }}</td>
              <td>
                <VButton v-if="!row.sent" size="xs" type="secondary" @click="markAsSent(row)">确认发信</VButton>
                <VTag v-else theme="secondary">已发卡（{{ row.sentAt }}）</VTag>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>
