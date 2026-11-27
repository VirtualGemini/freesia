<template>
  <div class="user-page freesia-full-height">
    <AccountSearch
      v-show="showSearchBar"
      v-model="searchForm"
      @search="handleSearch"
      @reset="resetSearchParams"
    />

    <ElCard class="freesia-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
      <FreesiaTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <ElSpace wrap>
            <ElButton v-if="hasAuth('system:account:create')" @click="showDialog('add')" v-ripple>
              {{ t('pages.system.account.actions.addAccount') }}
            </ElButton>
            <ElButton
              v-if="hasAuth('system:account:disable')"
              class="freesia-batch-disable"
              type="warning"
              plain
              :disabled="selectedRows.length === 0 || preparingLifecycle"
              @click="openLifecycle('disable', selectedRows)"
              v-ripple
            >
              {{ t('pages.system.account.lifecycle.batchDisable') }}
            </ElButton>
            <ElButton
              v-if="hasAuth('system:account:delete')"
              class="freesia-batch-delete"
              type="danger"
              plain
              :disabled="selectedRows.length === 0 || preparingLifecycle"
              @click="openLifecycle('delete', selectedRows)"
              v-ripple
            >
              {{ t('common.batchDelete') }}
            </ElButton>
          </ElSpace>
        </template>
      </FreesiaTableHeader>

      <FreesiaTable
        ref="freesiaTableRef"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @selection-change="handleSelectionChange"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />

      <AccountDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :account-data="currentAccountData"
        :submitting="savingAccount"
        @submit="handleDialogSubmit"
        @closed="currentAccountData = {}"
      />
      <AccountLifecycleDialog
        v-model="lifecycleVisible"
        :action="lifecycleAction"
        :accounts="lifecycleTargets"
        :initial-preview="lifecyclePreview"
        :batch="lifecycleBatch"
        @completed="handleLifecycleCompleted"
      />
      <AccountDetailDrawer v-model:visible="detailVisible" :detail="currentAccountDetail" />
      <AccountEditDrawer
        v-model:visible="editVisible"
        :detail="currentAccountDetail"
        @saved="refreshData"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import FreesiaButtonTable from '@/components/core/forms/freesia-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { useAuth } from '@/hooks/core/useAuth'
  import {
    fetchCreateAccount,
    fetchAccountDeletionPreview,
    fetchAccountStatusPreview,
    fetchGetAccountDetailCard,
    fetchGetAccountList,
    fetchUpdateAccount,
    type AccountDeletionPreview,
    type AccountStatusPreview
  } from '@/api/system-manage'
  import AccountSearch from './modules/user-search.vue'
  import AccountDialog from './modules/user-dialog.vue'
  import AccountDetailDrawer from './modules/account-detail-drawer.vue'
  import AccountEditDrawer from './modules/account-edit-drawer.vue'
  import AccountLifecycleDialog from './modules/account-lifecycle-dialog.vue'
  import { ElTag, ElImage } from 'element-plus'
  import { DialogType } from '@/types'
  import { useI18n } from 'vue-i18n'
  import { HttpError } from '@/utils/http/error'
  import { logger } from '@/utils/sys/logger'

  defineOptions({ name: 'Account' })

  type AccountListItem = Api.SystemManage.AccountListItem

  const { hasAuth } = useAuth()
  const { t } = useI18n()

  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const savingAccount = ref(false)
  const currentAccountData = ref<Partial<AccountListItem>>({})
  const detailVisible = ref(false)
  const editVisible = ref(false)
  const currentAccountDetail = ref<Api.SystemManage.AccountDetailCard>()
  const freesiaTableRef = ref()
  const selectedRows = ref<AccountListItem[]>([])
  const showSearchBar = ref(false)
  const lifecycleVisible = ref(false)
  const lifecycleAction = ref<'disable' | 'delete'>('disable')
  const lifecycleTargets = ref<AccountListItem[]>([])
  const lifecyclePreview = ref<AccountDeletionPreview | AccountStatusPreview>()
  const lifecycleBatch = ref(true)
  const preparingLifecycle = ref(false)

  const searchForm = ref({
    username: undefined,
    email: undefined,
    remark: undefined,
    status: undefined,
    activeStatus: undefined,
    createTimeRange: undefined as [string, string] | undefined,
    updateTimeRange: undefined as [string, string] | undefined
  })

  const ACCOUNT_STATUS_CONFIG = {
    '1': { type: 'success' as const, textKey: 'pages.system.account.status.enabled' },
    '2': { type: 'info' as const, textKey: 'pages.system.account.status.disabled' },
    '3': { type: 'warning' as const, textKey: 'pages.system.account.status.abnormal' }
  } as const

  const getAccountStatusConfig = (status: string) =>
    ACCOUNT_STATUS_CONFIG[status as keyof typeof ACCOUNT_STATUS_CONFIG] || {
      type: 'info' as const,
      textKey: 'common.status.unknown'
    }

  const getActiveStatusConfig = (activeStatus: string | null) =>
    activeStatus == null
      ? { type: 'info' as const, textKey: 'common.status.unknown' }
      : activeStatus === '1'
        ? { type: 'success' as const, textKey: 'pages.system.account.activeStatus.online' }
        : { type: 'info' as const, textKey: 'pages.system.account.activeStatus.offline' }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData,
    refreshRemove
  } = useTable({
    core: {
      apiFn: fetchGetAccountList,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        {
          type: 'selection',
          width: 52,
          selectable: (row: AccountListItem) => !row.protectedAccount
        },
        { type: 'index', minWidth: 96, label: t('table.column.index') },
        {
          prop: 'organizationName',
          label: '组织',
          minWidth: 160,
          formatter: (row: AccountListItem) => row.organizationName || '-'
        },
        {
          prop: 'accountInfo',
          label: 'pages.system.account.columns.accountInfo',
          minWidth: 400,
          formatter: (row: AccountListItem) =>
            h('div', { class: 'user flex-c' }, [
              h(ElImage, {
                class: 'size-9.5 rounded-md',
                src: row.avatar,
                previewSrcList: row.avatar ? [row.avatar] : [],
                previewTeleported: true
              }),
              h('div', { class: 'ml-2' }, [
                h('p', { class: 'user-name' }, row.username),
                h('p', { class: 'email' }, row.email || '-')
              ])
            ])
        },
        {
          prop: 'status',
          label: 'pages.system.account.columns.accountStatus',
          minWidth: 150,
          formatter: (row: AccountListItem) => {
            const statusConfig = getAccountStatusConfig(row.status)
            return h(ElTag, { type: statusConfig.type }, () => t(statusConfig.textKey))
          }
        },
        {
          prop: 'activeStatus',
          label: 'pages.system.account.columns.activeStatus',
          minWidth: 150,
          formatter: (row: AccountListItem) => {
            const activeConfig = getActiveStatusConfig(row.activeStatus)
            return h(ElTag, { type: activeConfig.type, effect: 'plain' }, () =>
              t(activeConfig.textKey)
            )
          }
        },
        {
          prop: 'remark',
          label: 'pages.system.account.columns.remark',
          minWidth: 260,
          showOverflowTooltip: true,
          formatter: (row: AccountListItem) => row.remark || '-'
        },
        {
          prop: 'createTime',
          label: 'pages.system.account.columns.createTime',
          minWidth: 210,
          sortable: true
        },
        {
          prop: 'updateTime',
          label: 'pages.system.account.columns.updateTime',
          minWidth: 210,
          sortable: true
        },
        {
          prop: 'operation',
          label: 'pages.system.account.columns.operation',
          width: 180,
          fixed: 'right',
          formatter: (row: AccountListItem) =>
            h(
              'div',
              [
                hasAuth('system:account:query')
                  ? h(FreesiaButtonTable, {
                      type: 'view',
                      onClick: () => showDetail(row)
                    })
                  : null,
                hasAuth('system:account:update') ||
                hasAuth('system:account:assign-role') ||
                hasAuth('system:account:transfer') ||
                hasAuth('system:account:enable') ||
                hasAuth('system:account:disable') ||
                hasAuth('system:account:reset-password')
                  ? h(FreesiaButtonTable, {
                      type: 'edit',
                      onClick: () => showEdit(row)
                    })
                  : null,
                hasAuth('system:account:delete') && !row.protectedAccount
                  ? h(FreesiaButtonTable, {
                      type: 'delete',
                      onClick: () => deleteAccount(row)
                    })
                  : null
              ].filter(Boolean)
            )
        }
      ]
    },
    performance: { enableCache: false }
  })

  const handleSearch = (params: Api.SystemManage.AccountSearchParams) => {
    replaceSearchParams(params)
    getData(undefined, { operationLogQuery: true })
  }

  const showDialog = (type: DialogType, row?: AccountListItem): void => {
    dialogType.value = type
    currentAccountData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  const showDetail = async (row: AccountListItem) => {
    try {
      currentAccountDetail.value = await fetchGetAccountDetailCard(row.accountId)
      detailVisible.value = true
    } catch (error) {
      if (!(error instanceof HttpError)) {
        logger.error('[Account] 获取账号详情异常:', error)
      }
    }
  }

  const showEdit = async (row: AccountListItem) => {
    try {
      currentAccountDetail.value = await fetchGetAccountDetailCard(row.accountId)
      editVisible.value = true
    } catch (error) {
      if (!(error instanceof HttpError)) {
        logger.error('[Account] 获取账号详情异常:', error)
      }
    }
  }

  const openLifecycle = async (
    action: 'disable' | 'delete',
    accounts: AccountListItem[],
    batch = true
  ) => {
    if (accounts.length === 0 || preparingLifecycle.value || lifecycleVisible.value) return
    const selected = [...accounts]
    preparingLifecycle.value = true
    try {
      const ids = selected.map((account) => account.accountId)
      const preview =
        action === 'delete'
          ? await fetchAccountDeletionPreview(ids)
          : await fetchAccountStatusPreview(ids, action)
      if (!batch && 'blockedAccounts' in preview && preview.blockedAccounts.length) {
        ElMessage.warning(t('pages.system.account.lifecycle.disableBeforeDelete'))
        return
      }
      // Open with a complete authoritative snapshot, never an empty dialog that grows later.
      lifecycleAction.value = action
      lifecycleTargets.value = selected
      lifecyclePreview.value = preview
      lifecycleBatch.value = batch
      lifecycleVisible.value = true
    } catch (failure) {
      ElMessage.error(
        failure instanceof Error ? failure.message : t('pages.system.account.lifecycle.failed')
      )
    } finally {
      preparingLifecycle.value = false
    }
  }

  const deleteAccount = (account: AccountListItem) => {
    if (account.protectedAccount) return
    if (account.status !== '2') {
      ElMessage.warning(t('pages.system.account.lifecycle.disableBeforeDelete'))
      return
    }
    void openLifecycle('delete', [account], false)
  }

  const handleLifecycleCompleted = async () => {
    freesiaTableRef.value?.elTableRef?.clearSelection()
    selectedRows.value = []
    if (lifecycleAction.value === 'delete') await refreshRemove()
    else await refreshData()
  }

  const handleDialogSubmit = async (payload: Api.SystemManage.AccountSaveCommand) => {
    if (savingAccount.value) return
    savingAccount.value = true
    try {
      if (dialogType.value === 'add') {
        await fetchCreateAccount(payload)
        ElMessage.success(t('pages.system.account.messages.createSuccess'))
      } else if (currentAccountData.value.accountId) {
        await fetchUpdateAccount(currentAccountData.value.accountId, payload)
        ElMessage.success(t('pages.system.account.messages.updateSuccess'))
      }
      dialogVisible.value = false
      // currentAccountData 在 @closed 事件中清空，避免关闭动画期间表单闪烁
      refreshData()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        logger.error('[Account] 提交账号信息异常:', error)
      }
    } finally {
      savingAccount.value = false
    }
  }

  const handleSelectionChange = (selection: AccountListItem[]): void => {
    selectedRows.value = selection
  }
</script>

<style scoped>
  .user {
    min-width: 0;
  }

  .user-name,
  .email {
    margin: 0;
    line-height: 1.5;
  }

  .user-name {
    color: var(--el-text-color-primary);
  }

  .email {
    color: var(--el-text-color-secondary);
    word-break: break-all;
  }

  @media (width <= 768px) {
    .user {
      align-items: flex-start;
    }

    .user-name,
    .email {
      white-space: normal;
    }
  }
</style>
