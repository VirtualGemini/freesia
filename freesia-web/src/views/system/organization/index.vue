<template>
  <ElCard class="freesia-table-card">
    <template #header>
      <ElSpace wrap>
        <span>{{ english ? 'Organizations' : '组织管理' }}</span>
        <ElButton :loading="loading" @click="load">{{ english ? 'Refresh' : '刷新' }}</ElButton>
      </ElSpace>
    </template>
    <ElAlert
      :title="
        english
          ? 'Organizations have no status. Data permissions define access.'
          : '组织不设启停状态，仅作为组织树和数据权限的承载。'
      "
      type="info"
      :closable="false"
      class="mb-4"
    />
    <ElTable v-loading="loading" :data="organizations" row-key="id" default-expand-all>
      <ElTableColumn prop="name" :label="english ? 'Name' : '名称'" min-width="180" />
      <ElTableColumn prop="code" :label="english ? 'Code' : '编码'" min-width="150" />
      <ElTableColumn prop="remark" :label="english ? 'Remark' : '备注'" min-width="180" />
      <ElTableColumn :label="english ? 'Actions' : '操作'" min-width="240">
        <template #default="{ row }">
          <ElButton v-if="allowed('create')" link type="primary" @click="open(row, true)">
            {{ english ? 'Add child' : '新增下级' }}
          </ElButton>
          <ElButton v-if="allowed('update')" link type="primary" @click="open(row, false)">
            {{ english ? 'Edit' : '编辑' }}
          </ElButton>
          <ElButton v-if="!row.root && allowed('delete')" link type="danger" @click="remove(row)">
            {{ english ? 'Delete' : '删除' }}
          </ElButton>
        </template>
      </ElTableColumn>
      <template #empty>{{ english ? 'No accessible organizations' : '暂无可访问的组织' }}</template>
    </ElTable>
    <ElDialog v-model="visible" :title="english ? 'Organization' : '组织'" width="min(560px, 94vw)">
      <ElForm :model="form" label-width="auto" @submit.prevent="save">
        <ElFormItem :label="english ? 'Parent' : '上级组织'">
          <ElTreeSelect
            v-model="form.parentId"
            :data="organizations"
            :props="{ label: 'name', value: 'id' }"
            node-key="id"
            check-strictly
            :disabled="editingRoot"
          />
        </ElFormItem>
        <ElFormItem :label="english ? 'Code' : '编码'" required>
          <ElInput v-model="form.code" :disabled="editingRoot" maxlength="80" />
        </ElFormItem>
        <ElFormItem :label="english ? 'Name' : '名称'" required>
          <ElInput v-model="form.name" maxlength="100" />
        </ElFormItem>
        <ElFormItem :label="english ? 'Remark' : '备注'">
          <ElInput v-model="form.remark" type="textarea" maxlength="500" />
        </ElFormItem>
        <ElFormItem :label="english ? 'Order' : '排序'">
          <ElInputNumber v-model="form.sort" :disabled="editingRoot" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="visible = false">{{ english ? 'Cancel' : '取消' }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">{{
          english ? 'Save' : '保存'
        }}</ElButton>
      </template>
    </ElDialog>
  </ElCard>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessageBox, ElMessage } from 'element-plus'
  import { useAccountStore } from '@/store/modules/user'
  import {
    getOrganizations,
    createOrganization,
    updateOrganization,
    deleteOrganization,
    type Organization,
    type OrganizationInput
  } from '@/api/organization'

  defineOptions({ name: 'Organization' })
  const { locale } = useI18n()
  const english = computed(() => locale.value.startsWith('en'))
  const account = useAccountStore()
  const allowed = (action: string) =>
    account.info.buttons?.includes(`system:organization:${action}`)
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

  async function load() {
    loading.value = true
    try {
      organizations.value = await getOrganizations()
    } finally {
      loading.value = false
    }
  }
  function open(row: Organization, child: boolean) {
    editing.value = child ? undefined : row.id
    editingRoot.value = !child && row.root
    Object.assign(
      form,
      child
        ? { parentId: row.id, code: '', name: '', remark: null, sort: 0 }
        : {
            parentId: row.parentId,
            code: row.code,
            name: row.name,
            remark: row.remark,
            sort: row.sort
          }
    )
    visible.value = true
  }
  async function save() {
    if (!form.code.trim() || !form.name.trim()) {
      ElMessage.warning(english.value ? 'Enter a code and name' : '请输入编码和名称')
      return
    }
    saving.value = true
    try {
      if (editing.value) await updateOrganization(editing.value, { ...form })
      else await createOrganization({ ...form })
      visible.value = false
      await load()
    } finally {
      saving.value = false
    }
  }
  async function remove(row: Organization) {
    try {
      await ElMessageBox.confirm(english.value ? `Delete ${row.name}?` : `确定删除 ${row.name}？`)
      await deleteOrganization(row.id)
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
  onMounted(load)
</script>
