<template>
  <slot />
</template>

<script setup lang="ts">
  import { ElAlert, ElMessageBox, ElTable, ElTableColumn } from 'element-plus'
  import {
    fetchBatchDeleteRole,
    fetchDeleteRole,
    fetchRoleBoundAccounts,
    type RoleDeletionImpact
  } from '@/api/system-manage'
  import { getConfirmationTableLayout } from '@/utils/ui/confirmation-layout'
  import { useI18n } from 'vue-i18n'

  const visible = defineModel<boolean>({ required: true })
  const props = defineProps<{ initialPreview: RoleDeletionImpact; batch: boolean }>()
  const emit = defineEmits<{ deleted: [] }>()
  const { t } = useI18n()
  const impact = ref<RoleDeletionImpact>(props.initialPreview)
  const previewReady = ref(true)
  const loading = ref(false)
  const submitting = ref(false)
  const error = ref('')
  const message = computed(() =>
    t(`pages.system.role.deletion.${props.batch ? 'batchConsequences' : 'consequences'}`)
  )
  const tableLayout = computed(() =>
    getConfirmationTableLayout(message.value, [
      [t('pages.system.role.columns.roleName'), ...impact.value.roles.map((role) => role.roleName)],
      [t('pages.system.role.columns.roleCode'), ...impact.value.roles.map((role) => role.roleCode)],
      [
        t('pages.system.role.deletion.accounts'),
        ...impact.value.roles.flatMap((role) =>
          role.records.map((member) =>
            member.nickname ? `${member.username}(${member.nickname})` : member.username
          )
        )
      ]
    ])
  )
  let generation = 0
  let confirmationGeneration = 0

  watch(
    () => props.initialPreview,
    (preview) => {
      generation++
      impact.value = preview
      previewReady.value = true
      loading.value = false
      error.value = ''
    },
    { flush: 'sync' }
  )

  watch(
    visible,
    (isVisible) => {
      if (isVisible) void showConfirmation()
      else {
        generation++
        loading.value = false
      }
    },
    { immediate: true }
  )

  function renderMessage(onClosed: () => void) {
    return h(
      'div',
      { 'aria-busy': loading.value || submitting.value, onVnodeUnmounted: onClosed },
      [
        error.value ? h(ElAlert, { title: error.value, type: 'error', closable: false }) : null,
        h('p', message.value),
        h(
          ElTable,
          {
            data: impact.value.roles,
            border: true,
            class: 'mt-3',
            maxHeight: tableLayout.value.tableMaxHeight
          },
          () => [
            h(ElTableColumn, {
              prop: 'roleName',
              label: t('pages.system.role.columns.roleName'),
              minWidth: tableLayout.value.columnMinWidths[0]
            }),
            h(ElTableColumn, {
              prop: 'roleCode',
              label: t('pages.system.role.columns.roleCode'),
              minWidth: tableLayout.value.columnMinWidths[1]
            }),
            h(
              ElTableColumn,
              {
                label: t('pages.system.role.deletion.accounts'),
                minWidth: tableLayout.value.columnMinWidths[2]
              },
              {
                default: ({ row }: { row: RoleDeletionImpact['roles'][number] }) =>
                  row.records.map((member) =>
                    h(
                      'div',
                      { key: member.accountId },
                      member.nickname ? `${member.username}(${member.nickname})` : member.username
                    )
                  )
              }
            )
          ]
        )
      ]
    )
  }

  async function loadPreview() {
    if (!visible.value || loading.value || submitting.value) return
    const current = ++generation
    previewReady.value = false
    error.value = ''
    loading.value = true
    try {
      const result = await fetchRoleBoundAccounts(impact.value.roles.map((role) => role.roleId))
      if (current === generation && visible.value) {
        impact.value = result
        previewReady.value = true
      }
    } catch (failure) {
      if (current === generation) {
        error.value =
          failure instanceof Error ? failure.message : t('pages.system.role.deletion.previewFailed')
      }
    } finally {
      if (current === generation) loading.value = false
    }
  }

  async function showConfirmation() {
    const current = ++confirmationGeneration
    let completed = false
    const onClosed = () => {
      if (current === confirmationGeneration) visible.value = false
    }
    try {
      await ElMessageBox.confirm(() => renderMessage(onClosed), t('common.tips'), {
        confirmButtonText: t('common.confirmAction'),
        cancelButtonText: t('common.cancel'),
        type: '',
        customClass: 'freesia-table-confirmation',
        customStyle: tableLayout.value.style,
        beforeClose: async (action, instance, done) => {
          if (action === 'confirm') instance.action = 'cancel'
          if (current !== confirmationGeneration || !visible.value) return
          if (submitting.value) return
          if (action !== 'confirm') {
            done()
            return
          }
          if (loading.value) return
          instance.action = 'cancel'
          instance.confirmButtonDisabled = true
          try {
            if (!previewReady.value) {
              await loadPreview()
            } else if (await confirmDeletion(current)) {
              completed = true
              instance.action = 'confirm'
              done()
            }
          } finally {
            instance.customStyle = tableLayout.value.style
            instance.confirmButtonText = t(
              previewReady.value ? 'common.confirmAction' : 'pages.system.role.deletion.reload'
            )
            instance.confirmButtonDisabled = false
          }
        }
      })
      if (completed && current === confirmationGeneration) {
        ElMessage.success(t('pages.system.role.messages.deleteSuccess'))
        emit('deleted')
      }
    } catch (failure) {
      if (failure !== 'cancel' && failure !== 'close') {
        ElMessage.error(
          failure instanceof Error ? failure.message : t('pages.system.role.deletion.notCompleted')
        )
      }
    } finally {
      onClosed()
    }
  }

  async function confirmDeletion(current: number): Promise<boolean> {
    if (!visible.value || !previewReady.value || loading.value || submitting.value) return false
    submitting.value = true
    error.value = ''
    try {
      const ids = impact.value.roles.map((role) => role.roleId)
      const confirmation = impact.value.confirmationToken
      const deleted = props.batch
        ? await fetchBatchDeleteRole(ids, confirmation)
        : await fetchDeleteRole(ids[0], confirmation)
      if (!deleted) throw new Error(t('pages.system.role.deletion.notCompleted'))
      return current === confirmationGeneration && visible.value
    } catch (failure) {
      if (current !== confirmationGeneration || !visible.value) return false
      // A failed/uncertain execution never falls back to a plain confirmation or a silent retry.
      previewReady.value = false
      error.value =
        failure instanceof Error ? failure.message : t('pages.system.role.deletion.notCompleted')
      return false
    } finally {
      submitting.value = false
    }
  }
</script>
