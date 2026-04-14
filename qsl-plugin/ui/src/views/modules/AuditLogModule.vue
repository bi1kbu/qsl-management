<script setup lang="ts">
import { VCard, VTag } from '@halo-dev/components'
import { computed, reactive, ref } from 'vue'

interface AuditLogItem {
  id: string
  time: string
  operator: string
  action: string
  resource: string
  ip: string
}

const logs = ref<AuditLogItem[]>([
  {
    id: 'LOG-1001',
    time: '2026-04-14 09:12:11',
    operator: 'Administrator',
    action: '新增通联记录',
    resource: 'QSO-1001',
    ip: '127.0.0.1',
  },
  {
    id: 'LOG-1002',
    time: '2026-04-14 10:25:08',
    operator: 'Administrator',
    action: '审核换卡申请通过',
    resource: 'REQ-1001',
    ip: '127.0.0.1',
  },
  {
    id: 'LOG-1003',
    time: '2026-04-14 11:06:43',
    operator: 'Administrator',
    action: '确认发信',
    resource: 'CARD-1001',
    ip: '127.0.0.1',
  },
])

const filters = reactive({
  keyword: '',
})

const filteredLogs = computed(() => {
  const keyword = filters.keyword.trim()
  if (!keyword) {
    return logs.value
  }

  return logs.value.filter((item) => {
    return (
      item.id.includes(keyword) ||
      item.operator.includes(keyword) ||
      item.action.includes(keyword) ||
      item.resource.includes(keyword)
    )
  })
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="审计日志">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="filters.keyword" type="text" placeholder="按日志ID/操作人/动作/资源筛选" />
        </div>
        <VTag theme="danger" rounded>日志不可删除</VTag>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>日志ID</th>
              <th>时间</th>
              <th>操作人</th>
              <th>动作</th>
              <th>资源</th>
              <th>IP</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredLogs" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.time }}</td>
              <td>{{ item.operator }}</td>
              <td>{{ item.action }}</td>
              <td>{{ item.resource }}</td>
              <td>{{ item.ip }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>
