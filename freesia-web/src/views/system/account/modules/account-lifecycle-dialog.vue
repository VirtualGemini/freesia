<template>
  <slot />
</template>

<script setup lang="ts">
  import { ElAlert, ElMessageBox, ElTable, ElTableColumn } from 'element-plus'
  import {
    fetchAccountDeletionPreview,
    fetchAccountStatusPreview,
    fetchBatchDeleteAccount,
    fetchBatchDisableAccount,
    fetchDeleteAccount,
    type AccountDeletionPreview,
    type AccountStatusPreview
  } from '@/api/system-manage'
  import { getConfirmationTableLayout } from '@/utils/ui/confirmation-layout'
  import { useI18n } from 'vue-i18n'

  const visible = defineModel<boolean>({ required: true })
  const props = defineProps<{
    action: 'disable' | 'delete'
    accounts: Array<{ accountId: string; username: string }>
    initialPreview?: AccountDeletionPreview | AccountStatusPreview
    batch: boolean
  }>()
  const emit = defineEmits<{ completed: [] }>()
  const { t } = useI18n()
  const preview = ref<AccountDeletionPreview>()
  const statusTargets = ref<AccountStatusPreview['accounts']>([])
  const ready = ref(false)
  const loading = ref(false)
  const submitting = ref(false)
  const error = ref('')
  const displayAccounts = computed(() =>
    preview.value?.blockedAccounts.length
      ? preview.value.blockedAccounts
      : (preview.value?.accounts ?? statusTargets.value)
  )
  const message = computed(() => {
    if (preview.value?.blockedAccounts.length) {
      return t(
        `pages.system.account.lifecycle.${props.batch ? 'batchDeleteBlocked' : 'disableBeforeDelete'}`
      )
    }
    if (props.action === 'delete' && !props.batch) {
      return t('pages.system.account.messages.confirmDelete', {
        name: preview.value?.accounts[0]?.username ?? ''
      })
    }
    return t(`pages.system.account.lifecycle.${props.action}Consequences`)
  })
  const tableLayout = computed(() =>
    getConfirmationTableLayout(
      message.value,
      props.batch
        ? [
            [
              t('pages.system.account.search.username'),
              ...displayAccounts.value.map((account) => account.username)
            ]
          ]
        : [],
      !props.batch
    )
  )
  let generation = 0
  let confirmationGeneration = 0

  function initialize() {
    generation++
    loading.value = false
    error.value = ''
    const initial = props.initialPreview
    if (!initial) {
      preview.value = undefined
      statusTargets.value = []
      ready.value = false
      return
    }
    applyPreview(initial)
  }

  function applyPreview(result: AccountDeletionPreview | AccountStatusPreview) {
    if ('confirmationToken' in result) {
      preview.value = result
      statusTargets.value = []
    } else {
      preview.value = undefined
      statusTargets.value = result.accounts
    }
    ready.value =
      result.accounts.length > 0 && (!('confirmationToken' in result) || !!result.confirmationToken)
  }

  watch(() => props.initialPreview, initialize, { immediate: true, flush: 'sync' })

  function renderMessage(onClosed: () => void) {
    return h(
      'div',
      { 'aria-busy': loading.value || submitting.value, onVnodeUnmounted: onClosed },
      [
        error.value ? h(ElAlert, { title: error.value, type: 'error', closable: false }) : null,
        h('p', message.value),
        props.batch
          ? h(
              ElTable,
              {
                data: displayAccounts.value,
                border: true,
                class: 'mt-3',
                maxHeight: tableLayout.value.tableMaxHeight
              },
              () =>
                h(ElTableColumn, {
                  prop: 'username',
                  label: t('pages.system.account.search.username'),
                  minWidth: tableLayout.value.columnMinWidths[0]
                })
            )
          : null
      ]
    )
  }

  async function prepare() {
    if (loading.value || submitting.value) return
    const current = ++generation
    ready.value = false
    error.value = ''
    loading.value = true
    try {
      const ids = props.accounts.map((account) => account.accountId)
      const result =
        props.action === 'delete'
          ? await fetchAccountDeletionPreview(ids)
          : await fetchAccountStatusPreview(ids, props.action)
      if (current === generation && visible.value) applyPreview(result)
    } catch (failure) {
      if (current === generation) {
        error.value =
          failure instanceof Error ? failure.message : t('pages.system.account.lifecycle.failed')
      }
    } finally {
      if (current === generation) loading.value = false
    }
  }

  watch(visible, (isVisible) => {
    if (isVisible) void showConfirmation()
    else {
      generation++
      loading.value = false
    }
  })

  async function showConfirmation() {
    const current = ++confirmationGeneration
    let completed = false
    const onClosed = () => {
      if (current === confirmationGeneration) visible.value = false
    }
    try {
      await ElMessageBox.confirm(() => renderMessage(onClosed), t('common.tips'), {
        confirmButtonText: t(
          ready.value ? 'common.confirmAction' : 'pages.system.account.lifecycle.reload'
        ),
        cancelButtonText: t('common.cancel'),
        type: props.batch ? '' : 'warning',
        customStyle: tableLayout.value.style,
        ...(props.batch ? { customClass: 'freesia-table-confirmation' } : {}),
        beforeClose: async (action, instance, done) => {
          // Native MessageBox sets action before this hook, including ignored pending clicks.
          if (action === 'confirm') instance.action = 'cancel'
          if (current !== confirmationGeneration || !visible.value) return
          if (submitting.value) return
          if (action !== 'confirm') {
            done()
            return
          }
          if (loading.value) return
          // Hash navigation can close the native box without beforeClose; only a completed write
          // may resolve as confirmation, never a preview reload or an unsuccessful execution.
          instance.action = 'cancel'
          instance.confirmButtonDisabled = true
          try {
            if (!ready.value) {
              // A new preview never implicitly retries a failed or uncertain write.
              await prepare()
            } else if (await confirm(current)) {
              completed = true
              instance.action = 'confirm'
              done()
            }
          } finally {
            instance.customStyle = tableLayout.value.style
            instance.confirmButtonText = t(
              ready.value ? 'common.confirmAction' : 'pages.system.account.lifecycle.reload'
            )
            instance.confirmButtonDisabled = false
          }
        }
      })
      if (completed && current === confirmationGeneration) {
        ElMessage.success(t('pages.system.account.lifecycle.success'))
        emit('completed')
      }
    } catch (failure) {
      if (failure !== 'cancel' && failure !== 'close') {
        ElMessage.error(
          failure instanceof Error ? failure.message : t('pages.system.account.lifecycle.failed')
        )
      }
    } finally {
      onClosed()
    }
  }

  async function confirm(current: number): Promise<boolean> {
    if (!ready.value || submitting.value) return false
    const reviewedGeneration = generation
    const ids = props.accounts.map((account) => account.accountId)
    submitting.value = true
    error.value = ''
    try {
      let completed: boolean
      if (props.action === 'delete') {
        if (!preview.value?.confirmationToken) return false
        completed = props.batch
          ? await fetchBatchDeleteAccount(ids, preview.value.confirmationToken)
          : await fetchDeleteAccount(ids[0], preview.value.confirmationToken)
      } else {
        completed = await fetchBatchDisableAccount(ids)
      }
      if (!completed) throw new Error(t('pages.system.account.lifecycle.failed'))
      return current === confirmationGeneration && visible.value
    } catch (failure) {
      if (
        current !== confirmationGeneration ||
        reviewedGeneration !== generation ||
        !visible.value
      ) {
        return false
      }
      ready.value = false
      error.value =
        failure instanceof Error ? failure.message : t('pages.system.account.lifecycle.failed')
      if (props.action === 'delete') {
        try {
          const result = await fetchAccountDeletionPreview(ids)
          if (
            current === confirmationGeneration &&
            reviewedGeneration === generation &&
            visible.value
          ) {
            preview.value = result
            if (result.blockedAccounts.length) error.value = ''
          }
          // Refreshed information is display-only; execution always needs explicit review again.
        } catch {
          // Keep the previous display snapshot and original failure; never retry a write.
        }
      }
      return false
    } finally {
      submitting.value = false
    }
  }
</script>
