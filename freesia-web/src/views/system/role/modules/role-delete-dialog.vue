<template>
  <ElDialog
    v-model="visible"
    :title="t('pages.system.role.messages.deleteTitle')"
    width="min(760px, 94vw)"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @open="loadPreview"
    @closed="reset"
  >
    <div v-loading="loading" class="role-delete-impact">
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="impact">
        <ElAlert
          :title="
            t('pages.system.role.deletion.summary', {
              roles: impact.roles.length,
              count: impact.visibleCount
            })
          "
          type="warning"
          :closable="false"
          show-icon
        />
        <p>{{ t('pages.system.role.deletion.consequences') }}</p>
        <p>
          {{
            t(
              impact.accountsComplete
                ? 'pages.system.role.deletion.complete'
                : 'pages.system.role.deletion.partial'
            )
          }}
        </p>
        <ElTable :data="impact.roles" max-height="320" border>
          <ElTableColumn
            prop="roleName"
            :label="t('pages.system.role.columns.roleName')"
            min-width="100"
          >
            <template #default="{ row }">
              <div class="role-name">{{ row.roleName }}</div>
              <div class="role-code">{{ row.roleCode }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn
            prop="visibleCount"
            :label="t('pages.system.role.deletion.visibleCount')"
            width="96"
          />
          <ElTableColumn
            :label="t('pages.system.role.deletion.accounts')"
            min-width="120"
            :show-overflow-tooltip="false"
          >
            <template #default="{ row }">
              <span v-if="row.records.length === 0">
                {{ t('pages.system.role.deletion.noVisibleAccounts') }}
              </span>
              <div v-for="member in row.records" :key="member.accountId" class="account-name">
                {{ member.username }}
              </div>
            </template>
          </ElTableColumn>
        </ElTable>
        <p>{{ t('pages.system.role.deletion.recheck') }}</p>
      </template>
    </div>
    <template #footer>
      <ElSpace wrap>
        <ElButton :disabled="submitting" @click="visible = false">
          {{ t('common.cancel') }}
        </ElButton>
        <ElButton v-if="!impact" :loading="loading" @click="loadPreview">
          {{ t('pages.system.role.deletion.reload') }}
        </ElButton>
        <ElButton
          type="danger"
          :disabled="!impact || loading"
          :loading="submitting"
          @click="confirmDeletion"
        >
          {{ t('common.confirm') }}
        </ElButton>
      </ElSpace>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import {
    fetchBatchDeleteRole,
    fetchDeleteRole,
    fetchRoleBoundAccounts,
    type RoleDeletionImpact
  } from '@/api/system-manage'
  import { useI18n } from 'vue-i18n'

  const visible = defineModel<boolean>({ required: true })
  const props = defineProps<{ roleIds: string[] }>()
  const emit = defineEmits<{ deleted: [] }>()
  const { t } = useI18n()
  const impact = ref<RoleDeletionImpact>()
  const loading = ref(false)
  const submitting = ref(false)
  const error = ref('')
  let generation = 0

  function reset() {
    generation++
    impact.value = undefined
    error.value = ''
    loading.value = false
  }

  async function loadPreview() {
    const current = ++generation
    impact.value = undefined
    error.value = ''
    loading.value = true
    try {
      const result = await fetchRoleBoundAccounts([...props.roleIds])
      if (current === generation && visible.value) impact.value = result
    } catch (failure) {
      if (current === generation) {
        error.value =
          failure instanceof Error ? failure.message : t('pages.system.role.deletion.previewFailed')
      }
    } finally {
      if (current === generation) loading.value = false
    }
  }

  async function confirmDeletion() {
    if (!impact.value || submitting.value) return
    submitting.value = true
    error.value = ''
    try {
      const ids = impact.value.roles.map((role) => role.roleId)
      const confirmation = impact.value.confirmationToken
      const deleted =
        ids.length === 1
          ? await fetchDeleteRole(ids[0], confirmation)
          : await fetchBatchDeleteRole(ids, confirmation)
      if (!deleted) throw new Error(t('pages.system.role.deletion.notCompleted'))
      visible.value = false
      ElMessage.success(t('pages.system.role.messages.deleteSuccess'))
      emit('deleted')
    } catch (failure) {
      // A failed/uncertain execution never falls back to a plain confirmation or a silent retry.
      impact.value = undefined
      error.value =
        failure instanceof Error ? failure.message : t('pages.system.role.deletion.notCompleted')
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .role-delete-impact {
    min-height: 120px;

    p {
      margin: 12px 0;
      line-height: 1.6;
    }
  }

  .role-name,
  .role-code,
  .account-name {
    overflow-wrap: anywhere;
  }

  .role-code {
    color: var(--el-text-color-secondary);
  }
</style>
