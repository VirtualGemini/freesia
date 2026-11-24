<template>
  <ElDialog
    v-model="visible"
    :title="english ? 'Role permissions' : '角色权限'"
    width="min(800px, 94vw)"
  >
    <ElForm label-width="auto"
      ><RoleRightsFields v-if="visible" v-model="rights" permission="system:role:permission"
    /></ElForm>
    <template #footer>
      <ElButton @click="visible = false">{{ english ? 'Cancel' : '取消' }}</ElButton>
      <ElButton type="primary" :loading="saving" @click="save">{{
        english ? 'Save' : '保存'
      }}</ElButton>
    </template>
  </ElDialog>
</template>
<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import request from '@/utils/http'
  import RoleRightsFields, { type RoleRights } from '@/components/business/role-rights-fields.vue'
  const props = defineProps<{ modelValue: boolean; roleData?: Api.SystemManage.RoleListItem }>()
  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'success'): void
  }>()
  const { locale } = useI18n()
  const english = computed(() => locale.value.startsWith('en'))
  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const saving = ref(false)
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
  async function save() {
    const role = props.roleData
    if (!role) return
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
      emit('success')
      visible.value = false
    } finally {
      saving.value = false
    }
  }
</script>
