<script setup lang="ts">
import { computed, type Component } from 'vue'
import type { QslMenuModule } from '../constants/menu-modules'
import QslModuleFrame from '../components/qsl/QslModuleFrame.vue'
import AddressManagementModule from './modules/AddressManagementModule.vue'
import AuditLogModule from './modules/AuditLogModule.vue'
import BureauManagementModule from './modules/BureauManagementModule.vue'
import CardQueryModule from './modules/CardQueryModule.vue'
import CardRecordModule from './modules/CardRecordModule.vue'
import DefaultModulePlaceholder from './modules/DefaultModulePlaceholder.vue'
import EquipmentCatalogModule from './modules/EquipmentCatalogModule.vue'
import ExchangeRequestReviewModule from './modules/ExchangeRequestReviewModule.vue'
import ImportExportModule from './modules/ImportExportModule.vue'
import MailReceiveConfirmModule from './modules/MailReceiveConfirmModule.vue'
import MailSendConfirmModule from './modules/MailSendConfirmModule.vue'
import OverviewDashboardModule from './modules/OverviewDashboardModule.vue'
import QsoQueryModule from './modules/QsoQueryModule.vue'
import QsoRecordModule from './modules/QsoRecordModule.vue'
import ReportAuditlogModule from './modules/ReportAuditlogModule.vue'
import StationCardModule from './modules/StationCardModule.vue'
import StationEquipmentModule from './modules/StationEquipmentModule.vue'
import StationProfileModule from './modules/StationProfileModule.vue'
import SystemSettingsModule from './modules/SystemSettingsModule.vue'

interface ModuleRenderer {
  component: Component
  props?: Record<string, unknown>
}

const props = defineProps<{
  qslModule: QslMenuModule
}>()

const currentModule = computed(() => props.qslModule)

const renderer = computed<ModuleRenderer>(() => {
  switch (currentModule.value.key) {
    case 'overview-dashboard':
      return { component: OverviewDashboardModule }
    case 'system-settings':
      return { component: SystemSettingsModule }
    case 'station-profile':
      return { component: StationProfileModule }
    case 'station-equipment':
      return { component: StationEquipmentModule }
    case 'station-card':
      return { component: StationCardModule }
    case 'qso-record':
      return { component: QsoRecordModule }
    case 'card-record':
      return { component: CardRecordModule }
    case 'mail-send-confirm':
      return { component: MailSendConfirmModule }
    case 'mail-receive-confirm':
      return { component: MailReceiveConfirmModule }
    case 'exchange-request-review':
      return { component: ExchangeRequestReviewModule }
    case 'qso-query':
      return { component: QsoQueryModule }
    case 'card-query':
      return { component: CardQueryModule }
    case 'report-auditlog':
      return { component: ReportAuditlogModule }
    case 'audit-log':
      return { component: AuditLogModule }
    case 'address-management':
      return { component: AddressManagementModule }
    case 'bureau-management':
      return { component: BureauManagementModule }
    case 'equipment-catalog':
      return { component: EquipmentCatalogModule }
    case 'import-export':
      return { component: ImportExportModule }
    default:
      return {
        component: DefaultModulePlaceholder,
        props: {
          module: currentModule.value,
        },
      }
  }
})

</script>

<template>
  <QslModuleFrame :module="currentModule">
    <component :is="renderer.component" v-bind="renderer.props" />
  </QslModuleFrame>
</template>
