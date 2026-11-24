<template>
  <div class="freesia-full-height">
    <ElCard class="freesia-table-card !mt-0">
      <FreesiaTableHeader :loading="loading" @refresh="load">
        <template #left>
          <ElButton
            v-if="allowed('create')"
            :disabled="!organizations.length"
            @click="open()"
            v-ripple
          >
            {{ t('pages.system.organization.actions.add') }}
          </ElButton>
        </template>
      </FreesiaTableHeader>

      <ElTable
        v-loading="loading"
        :data="organizations"
        row-key="id"
        height="calc(100vh - 292px)"
        stripe
        default-expand-all
      >
        <ElTableColumn
          prop="name"
          :label="t('pages.system.organization.fields.name')"
          min-width="220"
        />
        <ElTableColumn
          prop="code"
          :label="t('pages.system.organization.fields.code')"
          min-width="180"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="sort"
          :label="t('pages.system.organization.fields.sort')"
          width="100"
        />
        <ElTableColumn
          prop="remark"
          :label="t('pages.system.organization.fields.remark')"
          min-width="180"
          show-overflow-tooltip
        />
        <ElTableColumn
          :label="t('pages.system.organization.fields.operation')"
          width="170"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="freesia-table-actions">
              <FreesiaButtonTable
                v-if="allowed('create')"
                type="add"
                :title="t('pages.system.organization.actions.addChild')"
                @click="open(undefined, row)"
              />
              <FreesiaButtonTable
                v-if="allowed('update')"
                type="edit"
                :title="t('pages.system.organization.actions.edit')"
                @click="open(row)"
              />
              <FreesiaButtonTable
                v-if="!row.root && allowed('delete')"
                type="delete"
                :title="t('pages.system.organization.actions.delete')"
                @click="remove(row)"
              />
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      v-model="visible"
      :title="
        t(
          editing ? 'pages.system.organization.dialog.edit' : 'pages.system.organization.dialog.add'
        )
      "
      width="560px"
    >
      <ElForm :model="form" label-width="92px" @submit.prevent="save">
        <ElFormItem :label="t('pages.system.organization.fields.parent')">
          <ElSelect
            v-model="form.parentId"
            class="w-full"
            clearable
            filterable
            :disabled="editingRoot"
          >
            <ElOption
              v-for="item in organizationOptions"
              :key="item.id"
              :label="item.label"
              :value="item.id"
              :disabled="item.id === editing"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('pages.system.organization.fields.name')" required>
          <ElInput v-model="form.name" maxlength="100" />
        </ElFormItem>
        <ElFormItem :label="t('pages.system.organization.fields.code')" required>
          <ElInput v-model="form.code" :disabled="editingRoot" maxlength="80" />
        </ElFormItem>
        <ElFormItem :label="t('pages.system.organization.fields.sort')">
          <ElInputNumber v-model="form.sort" :disabled="editingRoot" :min="0" />
        </ElFormItem>
        <ElFormItem :label="t('pages.system.organization.fields.remark')">
          <ElInput v-model="form.remark" type="textarea" maxlength="500" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="visible = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :disabled="saving" @click="save">
          {{ t('table.form.submit') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessageBox, ElMessage } from 'element-plus'
  import FreesiaButtonTable from '@/components/core/forms/freesia-button-table/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import {
    getOrganizations,
    createOrganization,
    updateOrganization,
    deleteOrganization,
    type Organization,
    type OrganizationInput
  } from '@/api/organization'

  defineOptions({ name: 'Organization' })
  const { t } = useI18n()
  const { hasAuth } = useAuth()
  const allowed = (action: string) => hasAuth(`system:organization:${action}`)
  const organizations = ref<Organization[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const visible = ref(false)
  const editing = ref<string>()
  const editingRoot = ref(false)
  const form = reactive<OrganizationInput>({
    parentId: null,
    code: '',
    name: '',
    remark: null,
    sort: 0
  })
  const organizationOptions = computed(() => {
    const options: Array<{ id: string; label: string }> = []
    function visit(nodes: Organization[], depth: number) {
      for (const node of nodes) {
        options.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name} (${node.code})` })
        visit(node.children ?? [], depth + 1)
      }
    }
    visit(organizations.value, 0)
    return options
  })

  async function load() {
    loading.value = true
    try {
      organizations.value = await getOrganizations()
    } finally {
      loading.value = false
    }
  }
  function open(row?: Organization, parent?: Organization) {
    editing.value = row?.id
    editingRoot.value = row?.root ?? false
    Object.assign(
      form,
      row
        ? {
            parentId: row.parentId,
            code: row.code,
            name: row.name,
            remark: row.remark,
            sort: row.sort
          }
        : {
            parentId: parent?.id ?? organizations.value[0]?.id ?? null,
            code: '',
            name: '',
            remark: null,
            sort: 0
          }
    )
    visible.value = true
  }
  async function save() {
    if (saving.value) return
    if (!form.code.trim() || !form.name.trim()) {
      ElMessage.warning(t('pages.system.organization.messages.required'))
      return
    }
    saving.value = true
    try {
      if (editing.value) await updateOrganization(editing.value, { ...form })
      else await createOrganization({ ...form })
      visible.value = false
      ElMessage.success(t('pages.system.organization.messages.saveSuccess'))
      await load()
    } finally {
      saving.value = false
    }
  }
  async function remove(row: Organization) {
    try {
      await ElMessageBox.confirm(
        t('pages.system.organization.messages.confirmDelete', { name: row.name }),
        t('pages.system.organization.messages.deleteTitle'),
        { type: 'warning' }
      )
      await deleteOrganization(row.id)
      ElMessage.success(t('pages.system.organization.messages.deleteSuccess'))
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
  onMounted(load)
</script>

<style scoped>
  .freesia-table-actions {
    display: flex;
    align-items: center;
  }
</style>
