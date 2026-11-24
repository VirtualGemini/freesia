<template>
  <ElDrawer
    v-model="visible"
    :title="english ? 'Manage account' : '管理账号'"
    :size="drawerSize"
    destroy-on-close
  >
    <template v-if="detail">
      <ElForm label-width="auto">
        <ElFormItem :label="english ? 'Username' : '用户名'"
          ><ElInput v-model="profile.username"
        /></ElFormItem>
        <ElFormItem :label="english ? 'Nickname' : '昵称'"
          ><ElInput v-model="profile.nickname" maxlength="100"
        /></ElFormItem>
        <ElFormItem :label="english ? 'Contact email' : '联系邮箱'"
          ><ElInput v-model="profile.email" maxlength="254"
        /></ElFormItem>
        <ElFormItem :label="english ? 'Contact phone' : '联系电话'"
          ><ElInput v-model="profile.phone" maxlength="32"
        /></ElFormItem>
        <ElFormItem :label="english ? 'Remark' : '备注'"
          ><ElInput v-model="profile.remark" type="textarea" maxlength="500"
        /></ElFormItem>
        <ElButton v-if="allowed('update')" :loading="saving" @click="saveProfile">{{
          english ? 'Save profile' : '保存资料'
        }}</ElButton>
        <ElDivider />
        <ElFormItem :label="english ? 'Assigned roles' : '已分配角色'">
          <ElSpace wrap
            ><ElTag v-for="role in detail.account.roles" :key="role.roleId"
              >{{ role.roleName }} ({{ role.roleCode }})</ElTag
            ></ElSpace
          >
        </ElFormItem>
        <template v-if="allowed('assign-role')">
          <ElFormItem :label="english ? 'Manageable roles' : '可管理角色'">
            <ElSelect v-model="selectedRoles" multiple filterable class="w-full">
              <ElOption
                v-for="role in roles"
                :key="role.roleId"
                :value="role.roleId"
                :label="role.roleName"
              />
            </ElSelect>
          </ElFormItem>
          <ElAlert
            :title="
              english
                ? 'Other assigned roles are preserved.'
                : '只增减本次可管理角色，其它已有角色保持不变。'
            "
            type="info"
            :closable="false"
            class="mb-3"
          />
          <ElButton :loading="saving" @click="saveRoles">{{
            english ? 'Save role changes' : '保存角色变更'
          }}</ElButton>
        </template>
        <ElDivider />
        <ElFormItem :label="english ? 'Current organization' : '当前组织'">{{
          detail.account.organizationName || (english ? 'None' : '无组织')
        }}</ElFormItem>
        <template v-if="allowed('transfer')">
          <ElFormItem :label="english ? 'Transfer to' : '调入组织'">
            <ElTreeSelect
              v-model="destination"
              :data="organizations"
              :props="{ label: 'name', value: 'id', disabled: 'root' }"
              node-key="id"
              check-strictly
              clearable
              class="w-full"
            />
          </ElFormItem>
          <ElButton :loading="saving" @click="transfer">{{
            english ? 'Transfer organization' : '组织调动'
          }}</ElButton>
        </template>
        <ElAlert
          :title="
            english
              ? 'Account lifecycle and credential recovery are not connected here.'
              : '账号启停、删除及凭据恢复将在后续能力接通，此处不提供假保存。'
          "
          type="info"
          :closable="false"
          class="mt-5"
        />
      </ElForm>
    </template>
  </ElDrawer>
</template>
<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useWindowSize } from '@vueuse/core'
  import request from '@/utils/http'
  import { useAccountStore } from '@/store/modules/user'
  import { fetchGetAccountDetailCard } from '@/api/system-manage'
  import { getOrganizationOptions, type Organization } from '@/api/organization'
  const props = defineProps<{ modelValue: boolean; detail?: Api.SystemManage.AccountDetailCard }>()
  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'saved'): void
  }>()
  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })
  const detail = ref(props.detail)
  const { locale } = useI18n()
  const english = computed(() => locale.value.startsWith('en'))
  const { width } = useWindowSize()
  const drawerSize = computed(() => Math.min(720, Math.floor(width.value * 0.96)))
  const account = useAccountStore()
  const allowed = (action: string) => account.info.buttons?.includes(`system:account:${action}`)
  const profile = reactive({ username: '', remark: '', nickname: '', email: '', phone: '' })
  const roles = ref<Api.SystemManage.RoleOption[]>([])
  const selectedRoles = ref<string[]>([])
  const originalRoles = ref<string[]>([])
  const organizations = ref<Organization[]>([])
  const destination = ref<string | null>(null)
  const saving = ref(false)
  async function install() {
    const data = props.detail
    if (!data) return
    detail.value = data
    Object.assign(profile, {
      username: data.account.username,
      remark: data.account.remark ?? '',
      nickname: data.profile.nickname ?? '',
      email: data.profile.displayEmail ?? '',
      phone: data.profile.displayMobile ?? ''
    })
    destination.value = data.account.organizationId ?? null
    if (allowed('assign-role')) {
      roles.value = await request.get<Api.SystemManage.RoleOption[]>({
        url: '/api/role/options',
        params: { accountId: data.account.accountId }
      })
      const manageable = new Set(roles.value.map((role) => role.roleId))
      selectedRoles.value = (data.account.roles ?? [])
        .map((role) => role.roleId)
        .filter((id) => manageable.has(id))
      originalRoles.value = [...selectedRoles.value]
    }
    if (allowed('transfer'))
      organizations.value = await getOrganizationOptions('system:account:transfer')
  }
  watch(
    () => props.modelValue,
    (open) => {
      if (open) install()
    }
  )
  async function run(work: () => Promise<unknown>) {
    saving.value = true
    try {
      await work()
      detail.value = await fetchGetAccountDetailCard(detail.value!.account.accountId)
      emit('saved')
    } finally {
      saving.value = false
    }
  }
  async function saveProfile() {
    await run(() =>
      request.put({ url: `/api/account/${detail.value!.account.accountId}`, data: { ...profile } })
    )
  }
  async function saveRoles() {
    await run(() =>
      request.put({
        url: `/api/account/${detail.value!.account.accountId}/roles`,
        data: {
          addRoleIds: selectedRoles.value.filter((id) => !originalRoles.value.includes(id)),
          removeRoleIds: originalRoles.value.filter((id) => !selectedRoles.value.includes(id))
        }
      })
    )
    originalRoles.value = [...selectedRoles.value]
  }
  async function transfer() {
    await run(() =>
      request.put({
        url: `/api/account/${detail.value!.account.accountId}/organization`,
        data: { organizationId: destination.value || null }
      })
    )
  }
</script>
