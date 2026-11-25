<!-- 角色管理页面 -->
<template>
  <div class="freesia-full-height">
    <RoleSearch
      v-show="showSearchBar"
      v-model="searchForm"
      @search="handleSearch"
      @reset="resetSearchParams"
    ></RoleSearch>

    <ElCard class="freesia-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
      <FreesiaTableHeader
        v-model:columns="columnChecks"
        v-model:showSearchBar="showSearchBar"
        :loading="loading"
        @refresh="refreshData"
      >
        <template #left>
          <ElSpace wrap>
            <ElButton v-if="hasAuth('system:role:create')" @click="showDialog('add')" v-ripple>
              {{ t('pages.system.role.actions.addRole') }}
            </ElButton>
            <ElButton
              v-if="hasAuth('system:role:delete')"
              class="freesia-batch-delete"
              type="danger"
              plain
              :disabled="selectedRows.length === 0"
              @click="handleBatchDelete"
              v-ripple
            >
              {{ t('common.batchDelete') }}
            </ElButton>
          </ElSpace>
        </template>
      </FreesiaTableHeader>

      <!-- 表格 -->
      <FreesiaTable
        ref="freesiaTableRef"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @selection-change="handleSelectionChange"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </FreesiaTable>
    </ElCard>

    <!-- 角色编辑弹窗 -->
    <RoleEditDialog
      v-model="dialogVisible"
      :dialog-type="dialogType"
      :role-data="currentRoleData"
      @submit="handleRoleSubmit"
    />

    <!-- 菜单权限弹窗 -->
    <RolePermissionDialog
      ref="permissionDialogRef"
      v-model="permissionDialog"
      :role-data="currentRoleData"
      :mode="permissionMode"
      @success="refreshData"
    />
    <RoleDeleteDialog v-model="deleteVisible" :role-ids="deletionIds" @deleted="handleDeleted" />
    <ElDialog
      v-model="membersVisible"
      :title="t('pages.system.role.members.title')"
      width="min(600px, 94vw)"
    >
      <ElAlert
        :title="t('pages.system.role.members.count', { count: visibleMembers.length })"
        type="info"
        :closable="false"
      />
      <ElTable :data="visibleMembers">
        <ElTableColumn prop="username" :label="t('pages.system.role.members.account')" />
      </ElTable>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ButtonMoreItem } from '@/components/core/forms/freesia-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCreateRole, fetchGetRoleList, fetchUpdateRole } from '@/api/system-manage'
  import FreesiaButtonMore from '@/components/core/forms/freesia-button-more/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import RoleSearch from './modules/role-search.vue'
  import RoleEditDialog from './modules/role-edit-dialog.vue'
  import RolePermissionDialog from './modules/role-permission-dialog.vue'
  import RoleDeleteDialog from './modules/role-delete-dialog.vue'
  import { ElTag } from 'element-plus'
  import request from '@/utils/http'
  import { useI18n } from 'vue-i18n'

  defineOptions({ name: 'Role' })

  type RoleListItem = Api.SystemManage.RoleListItem
  type RoleSearchFormParams = Api.SystemManage.RoleSearchParams

  const { hasAuth } = useAuth()
  const { t } = useI18n()

  // 搜索表单
  const searchForm = ref({
    roleName: undefined,
    roleCode: undefined,
    description: undefined,
    type: undefined,
    enabled: undefined,
    createTimeRange: undefined as [string, string] | undefined,
    updateTimeRange: undefined as [string, string] | undefined
  })

  const showSearchBar = ref(false)

  const dialogVisible = ref(false)
  const permissionDialogRef = ref<InstanceType<typeof RolePermissionDialog>>()
  const permissionDialog = ref(false)
  const permissionMode = ref<'permission' | 'grant'>('permission')
  const currentRoleData = ref<RoleListItem | undefined>(undefined)
  const freesiaTableRef = ref()
  const selectedRows = ref<RoleListItem[]>([])
  const membersVisible = ref(false)
  const deleteVisible = ref(false)
  const deletionIds = ref<string[]>([])
  const visibleMembers = ref<Array<{ accountId: string; username: string }>>([])

  const isSystemRole = (row?: RoleListItem) => row?.type === 0
  const isSuperRole = (row?: RoleListItem) => row?.roleCode === 'R_SUPER'
  const getRoleTypeTagType = (type?: number) => (type === 0 ? 'danger' : 'info')

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
    // 核心配置
    core: {
      apiFn: fetchGetRoleList,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        { type: 'selection', width: 52, selectable: (row: RoleListItem) => !isSystemRole(row) },
        { type: 'index', label: t('table.column.index'), minWidth: 96 },
        {
          prop: 'roleName',
          label: 'pages.system.role.columns.roleName',
          minWidth: 200
        },
        {
          prop: 'roleCode',
          label: 'pages.system.role.columns.roleCode',
          minWidth: 200,
          showOverflowTooltip: true
        },
        {
          prop: 'type',
          label: 'pages.system.role.columns.type',
          minWidth: 152,
          formatter: (row) =>
            h(ElTag, { type: getRoleTypeTagType(row.type) }, () =>
              row.type === 0 ? t('common.status.builtin') : t('common.status.custom')
            )
        },
        {
          prop: 'description',
          label: 'pages.system.role.columns.description',
          minWidth: 280,
          showOverflowTooltip: true
        },
        {
          prop: 'enabled',
          label: 'pages.system.role.columns.enabled',
          minWidth: 152,
          formatter: (row: RoleListItem) =>
            h(ElTag, { type: row.enabled ? 'success' : 'warning' }, () =>
              t(`common.status.${row.enabled ? 'enabled' : 'disabled'}`)
            )
        },
        {
          prop: 'createTime',
          label: 'pages.system.role.columns.createTime',
          minWidth: 210,
          sortable: true
        },
        {
          prop: 'updateTime',
          label: 'pages.system.role.columns.updateTime',
          minWidth: 210,
          sortable: true
        },
        {
          prop: 'operation',
          label: 'pages.system.role.columns.operation',
          width: 120,
          fixed: 'right',
          formatter: (row) =>
            h('div', [
              h(FreesiaButtonMore, {
                list: buildActionList(row),
                onClick: (item: ButtonMoreItem) => buttonMoreClick(item, row)
              })
            ])
        }
      ]
    },
    performance: { enableCache: false },
    hooks: {
      onSuccess: () => {
        void permissionDialogRef.value?.prepare(true).catch(() => {
          // The request layer displays the error; opening the dialog can retry.
        })
      }
    }
  })

  const dialogType = ref<'add' | 'edit'>('add')

  const resolveRoleId = (row?: RoleListItem) => {
    const roleId = row?.roleId?.trim()
    if (!roleId) {
      ElMessage.error(t('pages.system.role.messages.missingRoleId'))
      return null
    }
    return roleId
  }

  const showDialog = (type: 'add' | 'edit', row?: RoleListItem) => {
    if (type === 'edit' && !resolveRoleId(row)) {
      return
    }
    if (type === 'edit' && isSuperRole(row)) {
      ElMessage.warning(t('pages.system.role.messages.superRoleCannotEdit'))
      return
    }
    dialogVisible.value = true
    dialogType.value = type
    currentRoleData.value = row
  }

  const handleRoleSubmit = async (payload: Api.SystemManage.RoleSaveCommand) => {
    if (dialogType.value === 'add') {
      await fetchCreateRole(payload)
      ElMessage.success(t('pages.system.role.messages.createSuccess'))
    } else {
      const roleId = resolveRoleId(currentRoleData.value)
      if (!roleId) return
      await fetchUpdateRole(roleId, payload)
      ElMessage.success(t('pages.system.role.messages.updateSuccess'))
    }
    dialogVisible.value = false
    refreshData()
  }

  /**
   * 搜索处理
   * @param params 搜索参数
   */
  const handleSearch = (params: RoleSearchFormParams) => {
    replaceSearchParams(params)
    getData(undefined, { operationLogQuery: true })
  }

  const buttonMoreClick = (item: ButtonMoreItem, row: RoleListItem) => {
    switch (item.key) {
      case 'permission':
        showPermissionDialog(row, 'permission')
        break
      case 'grant':
        showPermissionDialog(row, 'grant')
        break
      case 'edit':
        showDialog('edit', row)
        break
      case 'delete':
        deleteRole(row)
        break
      case 'members':
        void showMembers(row.roleId)
        break
    }
  }

  const showPermissionDialog = async (
    row: RoleListItem | undefined,
    mode: 'permission' | 'grant'
  ) => {
    try {
      if (!(await permissionDialogRef.value?.prepare())) return
      permissionMode.value = mode
      currentRoleData.value = row
      permissionDialog.value = true
    } catch {
      // Do not open an empty editor when its options could not be loaded.
    }
  }

  const buildActionList = (row: RoleListItem): ButtonMoreItem[] => {
    const items: ButtonMoreItem[] = []
    if (!isSuperRole(row)) {
      items.push({
        key: 'permission',
        label: 'pages.system.role.actions.permission',
        icon: 'ri:user-3-line',
        auth: 'system:role:permission'
      })
      items.push({
        key: 'grant',
        label: 'pages.system.role.permissions.grant',
        icon: 'ri:share-forward-line',
        auth: 'system:role:permission'
      })
    }
    items.push({
      key: 'members',
      label: 'pages.system.role.actions.members',
      icon: 'ri:group-line',
      auth: 'system:role:members'
    })

    if (!isSuperRole(row)) {
      items.push({
        key: 'edit',
        label: 'pages.system.role.actions.editRole',
        icon: 'ri:edit-2-line',
        auth: 'system:role:update'
      })
      items.push({
        key: 'delete',
        label: 'pages.system.role.actions.deleteRole',
        icon: 'ri:delete-bin-line',
        auth: 'system:role:delete',
        color: 'var(--el-color-danger)'
      })
    }

    return items
  }
  async function showMembers(id: string) {
    const result = await request.get<{
      records: Array<{ accountId: string; username: string }>
      visibleCount: number
    }>({
      url: `/api/role/${id}/members`
    })
    visibleMembers.value = result.records
    membersVisible.value = true
  }
  const deleteRole = (row: RoleListItem) => {
    if (isSystemRole(row)) {
      ElMessage.warning(t('pages.system.role.messages.systemRoleCannotDelete'))
      return
    }
    const roleId = resolveRoleId(row)
    if (!roleId) return

    deletionIds.value = [roleId]
    deleteVisible.value = true
  }

  const handleSelectionChange = (selection: RoleListItem[]) => {
    selectedRows.value = selection
  }

  const handleBatchDelete = () => {
    if (selectedRows.value.length === 0) {
      ElMessage.warning(t('common.batchDeleteEmpty'))
      return
    }
    deletionIds.value = selectedRows.value.map((row) => row.roleId)
    deleteVisible.value = true
  }

  const handleDeleted = async () => {
    freesiaTableRef.value?.elTableRef?.clearSelection()
    selectedRows.value = []
    await refreshRemove()
  }
</script>
