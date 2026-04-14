<script setup lang="ts">
import { VButton, VCard, VTag } from '@halo-dev/components'
import { reactive } from 'vue'

interface ExchangeRequestItem {
  id: string
  callSign: string
  useBureau: boolean
  email: string
  remarks: string
  status: '待审核' | '已通过' | '已拒绝'
  decisionAt: string
}

const rows = reactive<ExchangeRequestItem[]>([
  {
    id: 'REQ-1001',
    callSign: 'JA1ABC',
    useBureau: false,
    email: 'ja1abc@example.com',
    remarks: '希望尽快换卡',
    status: '待审核',
    decisionAt: '',
  },
  {
    id: 'REQ-1002',
    callSign: 'VK3XYZ',
    useBureau: true,
    email: 'vk3xyz@example.com',
    remarks: '使用卡片局投递',
    status: '待审核',
    decisionAt: '',
  },
])

const approve = (row: ExchangeRequestItem) => {
  row.status = '已通过'
  row.decisionAt = new Date().toLocaleString('zh-CN', { hour12: false })
}

const reject = (row: ExchangeRequestItem) => {
  row.status = '已拒绝'
  row.decisionAt = new Date().toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="qsl-block">
    <VCard title="换卡申请审核">
      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>申请ID</th>
              <th>呼号</th>
              <th>是否卡片局</th>
              <th>电子邮件</th>
              <th>备注</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.id }}</td>
              <td>{{ row.callSign }}</td>
              <td>{{ row.useBureau ? '是' : '否' }}</td>
              <td>{{ row.email }}</td>
              <td>{{ row.remarks || '无' }}</td>
              <td>
                <VTag :theme="row.status === '待审核' ? 'default' : row.status === '已通过' ? 'secondary' : 'danger'">
                  {{ row.status }}
                </VTag>
                <span v-if="row.decisionAt" class="qsl-table-note">{{ row.decisionAt }}</span>
              </td>
              <td>
                <div v-if="row.status === '待审核'" class="qsl-actions qsl-actions--tight">
                  <VButton size="xs" type="secondary" @click="approve(row)">同意</VButton>
                  <VButton size="xs" type="danger" @click="reject(row)">拒绝</VButton>
                </div>
                <span v-else class="qsl-muted">已处理</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>
