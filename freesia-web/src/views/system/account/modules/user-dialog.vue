<template>
  <ElDialog
    v-model="dialogVisible"
    :title="
      dialogType === 'add'
        ? t('pages.system.account.dialog.addTitle')
        : t('pages.system.account.dialog.editTitle')
    "
    width="520px"
    align-center
    :before-close="beforeClose"
    @closed="emit('closed')"
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="92px">
      <ElFormItem :label="t('pages.system.account.dialog.fields.username')" prop="username">
        <ElInput
          v-model="formData.username"
          :placeholder="t('pages.system.account.dialog.placeholders.username')"
        />
      </ElFormItem>
      <ElFormItem :label="t('pages.system.account.dialog.fields.password')" prop="password">
        <ElInput
          v-model="formData.password"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="
            dialogType === 'add'
              ? t('pages.system.account.dialog.placeholders.initialPassword')
              : t('pages.system.account.dialog.placeholders.passwordOptional')
          "
        />
      </ElFormItem>
      <ElFormItem :label="t('pages.system.account.dialog.fields.remark')" prop="remark">
        <ElInput
          v-model="formData.remark"
          type="textarea"
          :rows="4"
          :maxlength="255"
          show-word-limit
          :placeholder="t('pages.system.account.dialog.placeholders.remark')"
        />
      </ElFormItem>
      <ElFormItem :label="english ? 'Organization' : '所属组织'">
        <ElTreeSelect
          v-model="formData.organizationId"
          :data="organizations"
          :props="{ label: 'name', value: 'id', disabled: 'root' }"
          node-key="id"
          check-strictly
          clearable
        />
      </ElFormItem>
      <ElFormItem :label="t('pages.system.account.dialog.fields.roleCodes')" prop="roleCodes">
        <ElSelect v-model="formData.roleCodes" multiple filterable>
          <ElOption
            v-for="role in roleList"
            :key="role.roleCode"
            :value="role.roleCode"
            :label="role.roleName"
          />
        </ElSelect>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="dialogVisible = false">{{ t('common.cancel') }}</ElButton>
        <ElButton
          type="primary"
          :disabled="validating || submitting"
          :aria-busy="validating || submitting"
          @click="handleSubmit"
          >{{ t('table.form.submit') }}</ElButton
        >
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import request from '@/utils/http'
  import { getOrganizationOptions, type Organization } from '@/api/organization'
  import { useAccountStore } from '@/store/modules/user'
  import type { FormInstance, FormRules } from 'element-plus'
  import { useI18n } from 'vue-i18n'

  interface Props {
    visible: boolean
    type: string
    submitting?: boolean
    accountData?: Partial<Api.SystemManage.AccountListItem> & {
      roleCodes?: string[]
    }
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit', value: Api.SystemManage.AccountSaveCommand): void
    (e: 'closed'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()
  const { t, locale } = useI18n()
  const english = computed(() => locale.value.startsWith('en'))
  const organizations = ref<Organization[]>([])
  const account = useAccountStore()

  const roleList = ref<Api.SystemManage.RoleOption[]>([])

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const dialogType = computed(() => props.type)
  const formRef = ref<FormInstance>()

  const formData = reactive({
    username: '',
    password: '',
    remark: '',
    roleCodes: [] as string[],
    organizationId: null as string | null
  })
  const rules = computed<FormRules>(() => ({
    username: [
      {
        required: true,
        message: t('pages.system.account.dialog.validation.usernameRequired'),
        trigger: 'blur'
      },
      {
        min: 3,
        max: 50,
        message: t('pages.system.account.dialog.validation.usernameLength'),
        trigger: 'blur'
      }
    ],
    password:
      dialogType.value === 'add'
        ? [
            {
              required: true,
              message: t('pages.system.account.dialog.validation.passwordRequired'),
              trigger: 'blur'
            }
          ]
        : [],
    remark: [
      {
        max: 255,
        message: t('pages.system.account.dialog.validation.remarkLength'),
        trigger: 'blur'
      }
    ]
  }))

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.accountData
    const row = props.accountData

    Object.assign(formData, {
      username: isEdit && row ? row.username || '' : '',
      password: '',
      remark: isEdit && row ? row.remark || '' : '',
      roleCodes: isEdit && row ? (Array.isArray(row.roleCodes) ? row.roleCodes : []) : [],
      organizationId: row?.organizationId ?? null
    })
  }

  const loadRoleList = async () => {
    if (account.info.buttons?.includes('system:account:assign-role')) {
      roleList.value = await request.get<Api.SystemManage.RoleOption[]>({
        url: '/api/role/options',
        params: { organizationId: formData.organizationId ?? undefined, organizationSelected: true }
      })
    }
    organizations.value = await getOrganizationOptions('system:account:create')
  }

  watch(
    () => [props.visible, props.type, props.accountData],
    ([visible]) => {
      if (visible) {
        loadRoleList()
        initFormData()
        nextTick(() => {
          formRef.value?.clearValidate()
        })
      }
    },
    { immediate: true }
  )

  watch(
    () => formData.organizationId,
    async () => {
      if (dialogVisible.value && account.info.buttons?.includes('system:account:assign-role')) {
        roleList.value = await request.get<Api.SystemManage.RoleOption[]>({
          url: '/api/role/options',
          params: {
            organizationId: formData.organizationId ?? undefined,
            organizationSelected: true
          }
        })
        const legal = new Set(roleList.value.map((role) => role.roleCode))
        formData.roleCodes = formData.roleCodes.filter((code) => legal.has(code))
      }
    }
  )

  const validating = ref(false)
  const beforeClose = (done: () => void) => {
    if (!props.submitting && !validating.value) done()
  }
  const handleSubmit = async () => {
    if (!formRef.value || validating.value || props.submitting) return
    validating.value = true
    try {
      const valid = await formRef.value.validate().catch(() => false)
      if (!valid) return

      emit('submit', {
        username: formData.username.trim(),
        password: formData.password || undefined,
        organizationId: formData.organizationId,
        remark: formData.remark.trim() || undefined,
        roleCodes: [...formData.roleCodes]
      })
    } finally {
      validating.value = false
    }
  }
</script>
