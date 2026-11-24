<template>
  <ElDialog
    v-model="visible"
    :title="
      t(
        mode === 'permission'
          ? 'pages.system.role.actions.permission'
          : 'pages.system.role.permissions.grant'
      )
    "
    width="520px"
    align-center
    class="el-dialog-border"
    @closed="handleClosed"
  >
    <ElScrollbar max-height="70vh">
      <ElForm label-width="auto">
        <RoleRightsFields
          ref="fieldsRef"
          v-model="rights"
          :mode="mode"
          :permissions="permissions"
          :organizations="organizations"
        />
      </ElForm>
    </ElScrollbar>
    <template #footer>
      <ElButton @click="toggleExpandAll">
        {{ t(`pages.system.role.permissions.${expanded ? 'collapse' : 'expand'}`) }}
      </ElButton>
      <ElButton @click="toggleSelectAll">
        {{ t(`pages.system.role.permissions.${selectedAll ? 'clearAll' : 'selectAll'}`) }}
      </ElButton>
      <ElButton type="primary" :disabled="saving" @click="save">{{
        t('table.form.submit')
      }}</ElButton>
    </template>
  </ElDialog>
</template>
<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import request from '@/utils/http'
  import { getOrganizationOptions, type Organization } from '@/api/organization'
  import { useAccountStore } from '@/store/modules/user'
  import { useAuth } from '@/hooks/core/useAuth'
  import RoleRightsFields, {
    type PermissionNode,
    type RoleRights
  } from '@/components/business/role-rights-fields.vue'
  const props = defineProps<{
    modelValue: boolean
    roleData?: Api.SystemManage.RoleListItem
    mode: 'permission' | 'grant'
  }>()
  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'success'): void
  }>()
  const { t } = useI18n()
  const account = useAccountStore()
  const { hasAuth } = useAuth()
  const fieldsRef = ref<InstanceType<typeof RoleRightsFields>>()
  const permissions = shallowRef<PermissionNode[]>([])
  const organizations = shallowRef<Organization[]>([])
  let optionsReady = false
  let optionsGeneration = 0
  let optionsFlight: Promise<boolean> | undefined
  const expanded = ref(false)
  const selectedAll = ref(false)
  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const saving = ref(false)
  let saved = false
  const rights = ref<RoleRights>({
    dataScope: 'SELF',
    customOrganizationIds: [],
    menuIds: [],
    grantMenuIds: []
  })
  watch(
    () => props.modelValue,
    (open) => {
      if (open && props.roleData)
        rights.value = {
          dataScope: props.roleData.dataScope,
          customOrganizationIds: [...props.roleData.customOrganizationIds],
          menuIds: [...props.roleData.menuIds],
          grantMenuIds: [...props.roleData.grantMenuIds]
        }
    }
  )
  watch(
    () => [account.info.accountId, account.info.buttons, account.language],
    () => {
      optionsGeneration++
      optionsReady = false
      optionsFlight = undefined
      permissions.value = []
      organizations.value = []
    }
  )
  // Page-local display options, not authorization state. Writes still recheck D/M/G on the server.
  async function prepare(force = false) {
    if (!hasAuth('system:role:permission')) return false
    if (force) optionsReady = false
    if (optionsFlight) return optionsFlight
    if (optionsReady && !force) return true
    const generation = optionsGeneration
    const flight = Promise.all([
      request.get<PermissionNode[]>({ url: '/api/role/permission-options' }),
      getOrganizationOptions('system:role:permission')
    ]).then(([menuOptions, organizationOptions]) => {
      if (generation !== optionsGeneration) return false
      permissions.value = menuOptions
      organizations.value = organizationOptions
      optionsReady = true
      return true
    })
    optionsFlight = flight
    try {
      return await flight
    } finally {
      if (optionsFlight === flight) optionsFlight = undefined
    }
  }
  defineExpose({ prepare })
  function handleClosed() {
    fieldsRef.value?.expandAll(false)
    expanded.value = false
    selectedAll.value = false
    if (saved) {
      saved = false
      emit('success')
    }
  }
  function toggleExpandAll() {
    expanded.value = !expanded.value
    fieldsRef.value?.expandAll(expanded.value)
  }
  function toggleSelectAll() {
    selectedAll.value = !selectedAll.value
    fieldsRef.value?.selectAll(selectedAll.value)
  }
  async function save() {
    const role = props.roleData
    if (!role || saving.value) return
    saving.value = true
    try {
      await request.put({
        url: `/api/role/${role.roleId}/permissions`,
        data: {
          roleName: role.roleName,
          roleCode: role.roleCode,
          description: role.description,
          enabled: role.enabled,
          ...rights.value
        }
      })
      ElMessage.success(t('pages.system.role.permissions.saved'))
      saved = true
      visible.value = false
    } finally {
      saving.value = false
    }
  }
</script>
