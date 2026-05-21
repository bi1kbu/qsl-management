<script setup lang="ts">
import { VButton, VCard } from '@halo-dev/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { appendQslAuditLog } from '../../api/qsl-audit-log-api'
import {
  createExtension,
  deleteExtension,
  listExtensions,
  qslApiVersion,
  updateExtension,
  type QslExtension,
} from '../../api/qsl-extension-api'
import QslSortableHeader from '../../components/QslSortableHeader.vue'
import { applySortDirection, compareText, type QslSortDirection } from '../../utils/qsl-table-sort'

type ActivitySortKey =
  | 'resourceName'
  | 'activityName'
  | 'activityLocation'
  | 'activityDate'
  | 'activityTime'
  | 'cardRemarks'

interface OfflineActivitySpec {
  activityName: string
  activityLocation: string
  activityDate: string
  activityTime: string
  cardRemarks: string
}

interface OfflineActivityStatus {
  workflowStatus: string
}

interface OfflineActivityItem {
  resourceName: string
  metadataVersion?: number | null
  spec: OfflineActivitySpec
  createdAt: string
}

const resourcePlural = 'offline-activities'
const resourceKind = 'OfflineActivity'

const loading = ref(false)
const saving = ref(false)
const deletingName = ref('')
const feedback = ref('')
const keyword = ref('')
const editingName = ref('')
const sortKey = ref<ActivitySortKey>('resourceName')
const sortDirection = ref<QslSortDirection>('asc')

const form = reactive<OfflineActivitySpec>({
  activityName: '',
  activityLocation: '',
  activityDate: '',
  activityTime: '',
  cardRemarks: '',
})

const rows = ref<OfflineActivityItem[]>([])

const isEditing = computed(() => Boolean(editingName.value))

const filteredRows = computed(() => {
  const normalized = keyword.value.trim().toUpperCase()
  if (!normalized) {
    return rows.value
  }
  return rows.value.filter((row) => {
    const searchText = [
      row.resourceName,
      row.spec.activityName,
      row.spec.activityLocation,
      row.spec.activityDate,
      row.spec.activityTime,
    ]
      .join(' ')
      .toUpperCase()
    return searchText.includes(normalized)
  })
})

const compareActivityRows = (
  left: OfflineActivityItem,
  right: OfflineActivityItem,
  key: ActivitySortKey,
): number => {
  if (key === 'resourceName') {
    return compareText(left.resourceName, right.resourceName)
  }
  return compareText(left.spec[key], right.spec[key])
}

const sortedRows = computed(() => {
  return [...filteredRows.value].sort((left, right) => {
    return applySortDirection(compareActivityRows(left, right, sortKey.value), sortDirection.value)
  })
})

const toggleSort = (key: string) => {
  const nextKey = key as ActivitySortKey
  if (sortKey.value === nextKey) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = nextKey
    sortDirection.value = 'asc'
  }
}

const normalizeSpec = (spec?: Partial<OfflineActivitySpec>): OfflineActivitySpec => ({
  activityName: spec?.activityName ?? '',
  activityLocation: spec?.activityLocation ?? '',
  activityDate: spec?.activityDate ?? '',
  activityTime: spec?.activityTime ?? '',
  cardRemarks: spec?.cardRemarks ?? '',
})

const toItem = (
  extension: QslExtension<OfflineActivitySpec, OfflineActivityStatus>,
): OfflineActivityItem => ({
  resourceName: extension.metadata.name,
  metadataVersion: extension.metadata.version,
  spec: normalizeSpec(extension.spec),
  createdAt: extension.metadata.creationTimestamp ?? '',
})

const buildActivityIdPrefix = (activityDate: string): string => {
  const normalized = activityDate.trim().replace(/-/g, '')
  if (normalized.length >= 6) {
    return normalized.slice(0, 6)
  }
  const now = new Date()
  const year = String(now.getFullYear())
  const month = String(now.getMonth() + 1).padStart(2, '0')
  return `${year}${month}`
}

const allocateActivityResourceName = (activityDate: string): string => {
  const prefix = `${buildActivityIdPrefix(activityDate)}ACT`
  const used = new Set(rows.value.map((item) => item.resourceName.trim().toUpperCase()))
  let maxSequence = 0

  for (const row of rows.value) {
    const name = row.resourceName.trim().toUpperCase()
    const matched = name.match(/^\d{6}ACT(\d+)$/)
    if (!matched) {
      continue
    }
    const sequence = Number.parseInt(matched[1] ?? '0', 10)
    if (Number.isFinite(sequence)) {
      maxSequence = Math.max(maxSequence, sequence)
    }
  }

  let nextSequence = maxSequence + 1
  while (true) {
    const candidate = `${prefix}${String(nextSequence).padStart(2, '0')}`
    if (!used.has(candidate)) {
      return candidate
    }
    nextSequence += 1
  }
}

const resetForm = () => {
  form.activityName = ''
  form.activityLocation = ''
  form.activityDate = ''
  form.activityTime = ''
  form.cardRemarks = ''
  editingName.value = ''
}

const loadRows = async () => {
  loading.value = true
  try {
    const extensions = await listExtensions<OfflineActivitySpec, OfflineActivityStatus>(
      resourcePlural,
    )
    rows.value = extensions.map((item) => toItem(item))
    if (!extensions.length) {
      feedback.value = '暂无线下活动记录。'
    } else if (!isEditing.value) {
      feedback.value = ''
    }
  } catch (error) {
    feedback.value = `加载线下活动失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    loading.value = false
  }
}

const startEdit = (row: OfflineActivityItem) => {
  editingName.value = row.resourceName
  form.activityName = row.spec.activityName
  form.activityLocation = row.spec.activityLocation
  form.activityDate = row.spec.activityDate
  form.activityTime = row.spec.activityTime
  form.cardRemarks = row.spec.cardRemarks
  feedback.value = `正在编辑活动：${row.resourceName}`
}

const saveActivity = async () => {
  if (!form.activityName.trim()) {
    feedback.value = '活动名称不能为空。'
    return
  }
  if (!form.activityDate) {
    feedback.value = '活动日期不能为空。'
    return
  }

  saving.value = true
  try {
    const nextSpec: OfflineActivitySpec = {
      activityName: form.activityName.trim(),
      activityLocation: form.activityLocation.trim(),
      activityDate: form.activityDate,
      activityTime: form.activityTime.trim(),
      cardRemarks: form.cardRemarks.trim(),
    }

    if (isEditing.value) {
      const target = rows.value.find((item) => item.resourceName === editingName.value)
      if (!target) {
        feedback.value = '未找到要编辑的活动，请刷新后重试。'
        return
      }
      await updateExtension<OfflineActivitySpec, OfflineActivityStatus>(
        resourcePlural,
        target.resourceName,
        {
          apiVersion: qslApiVersion,
          kind: resourceKind,
          metadata: {
            name: target.resourceName,
            version: target.metadataVersion,
          },
          spec: nextSpec,
          status: {
            workflowStatus: '活动已更新',
          },
        },
      )
      await appendQslAuditLog({
        action: '编辑线下活动',
        resourceType: 'offline-activity',
        resourceName: target.resourceName,
        detail:
          `${nextSpec.activityName} ${nextSpec.activityDate} ${nextSpec.activityTime || ''}`.trim(),
      })
      await loadRows()
      resetForm()
      feedback.value = '活动已更新。'
      return
    }

    const resourceName = allocateActivityResourceName(nextSpec.activityDate)
    await createExtension<OfflineActivitySpec, OfflineActivityStatus>(resourcePlural, {
      apiVersion: qslApiVersion,
      kind: resourceKind,
      metadata: {
        name: resourceName,
      },
      spec: nextSpec,
      status: {
        workflowStatus: '活动已创建',
      },
    })
    await appendQslAuditLog({
      action: '新增线下活动',
      resourceType: 'offline-activity',
      resourceName,
      detail:
        `${nextSpec.activityName} ${nextSpec.activityDate} ${nextSpec.activityTime || ''}`.trim(),
    })
    await loadRows()
    resetForm()
    feedback.value = '活动已创建。'
  } catch (error) {
    feedback.value = `保存活动失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    saving.value = false
  }
}

const removeActivity = async (row: OfflineActivityItem) => {
  const confirmed = window.confirm(`确认删除活动“${row.spec.activityName || row.resourceName}”吗？`)
  if (!confirmed) {
    return
  }

  deletingName.value = row.resourceName
  try {
    await deleteExtension(resourcePlural, row.resourceName)
    await appendQslAuditLog({
      action: '删除线下活动',
      resourceType: 'offline-activity',
      resourceName: row.resourceName,
      detail:
        `${row.spec.activityName} ${row.spec.activityDate} ${row.spec.activityTime || ''}`.trim(),
    })
    await loadRows()
    if (editingName.value === row.resourceName) {
      resetForm()
    }
    feedback.value = '活动已删除。'
  } catch (error) {
    feedback.value = `删除活动失败：${error instanceof Error ? error.message : '未知错误'}`
  } finally {
    deletingName.value = ''
  }
}

onMounted(() => {
  loadRows()
})
</script>

<template>
  <div class="qsl-block">
    <VCard title="创建活动">
      <div class="qsl-form-grid">
        <label class="qsl-field">
          <span class="qsl-field__label">活动名称</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="form.activityName"
              type="text"
              placeholder="例如：2026 春季线下换卡活动"
            />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">活动地点</span>
          <div class="qsl-input-shell">
            <input
              v-model.trim="form.activityLocation"
              type="text"
              placeholder="例如：北京信息科技大学沙河校区"
            />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">活动日期</span>
          <div class="qsl-input-shell">
            <input v-model="form.activityDate" type="date" />
          </div>
        </label>

        <label class="qsl-field">
          <span class="qsl-field__label">活动时间</span>
          <div class="qsl-input-shell">
            <input v-model.trim="form.activityTime" type="text" placeholder="例如：1400-1700" />
          </div>
        </label>

        <label class="qsl-field qsl-field--full">
          <span class="qsl-field__label">卡片备注</span>
          <div class="qsl-input-shell qsl-input-shell--textarea">
            <textarea v-model.trim="form.cardRemarks" rows="3" placeholder="输入卡片备注" />
          </div>
        </label>
      </div>

      <div class="qsl-actions">
        <VButton type="secondary" :disabled="saving || loading" @click="saveActivity">
          {{ isEditing ? '保存编辑' : '创建活动' }}
        </VButton>
        <VButton v-if="isEditing" :disabled="saving || loading" @click="resetForm"
          >取消编辑</VButton
        >
        <span v-if="feedback" class="qsl-feedback">{{ feedback }}</span>
      </div>
    </VCard>

    <VCard title="活动清单">
      <div class="qsl-form-inline">
        <div class="qsl-input-shell">
          <input v-model.trim="keyword" type="text" placeholder="按活动名称/地点/日期筛选" />
        </div>
        <VButton :disabled="loading" @click="loadRows">刷新</VButton>
      </div>

      <div class="qsl-table-wrap">
        <table class="qsl-table">
          <thead>
            <tr>
              <th>
                <QslSortableHeader
                  column-key="resourceName"
                  label="活动ID"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>
                <QslSortableHeader
                  column-key="activityName"
                  label="活动名称"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>
                <QslSortableHeader
                  column-key="activityLocation"
                  label="活动地点"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>
                <QslSortableHeader
                  column-key="activityDate"
                  label="活动日期"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>
                <QslSortableHeader
                  column-key="activityTime"
                  label="活动时间"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>
                <QslSortableHeader
                  column-key="cardRemarks"
                  label="卡片备注"
                  :sort-key="sortKey"
                  :sort-direction="sortDirection"
                  @sort="toggleSort"
                />
              </th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in sortedRows" :key="row.resourceName">
              <td>{{ row.resourceName }}</td>
              <td>{{ row.spec.activityName || '-' }}</td>
              <td>{{ row.spec.activityLocation || '-' }}</td>
              <td>{{ row.spec.activityDate || '-' }}</td>
              <td>{{ row.spec.activityTime || '-' }}</td>
              <td>{{ row.spec.cardRemarks || '-' }}</td>
              <td>
                <div class="qsl-actions qsl-actions--tight">
                  <VButton
                    size="xs"
                    type="secondary"
                    :disabled="saving || loading"
                    @click="startEdit(row)"
                    >编辑</VButton
                  >
                  <VButton
                    size="xs"
                    :disabled="deletingName === row.resourceName || saving || loading"
                    @click="removeActivity(row)"
                  >
                    删除
                  </VButton>
                </div>
              </td>
            </tr>
            <tr v-if="!sortedRows.length">
              <td colspan="7" class="qsl-table-empty">暂无活动记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </VCard>
  </div>
</template>

<style scoped lang="scss">
.qsl-table-empty {
  text-align: center;
  color: #6b7280;
}
</style>
