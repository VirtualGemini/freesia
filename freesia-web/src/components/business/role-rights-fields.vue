<template>
  <ElFormItem v-show="mode === 'permission'" :label="t('pages.system.role.permissions.data')">
    <ElSelect v-model="value.dataScope">
      <ElOption
        v-for="scope in scopes"
        :key="scope"
        :value="scope"
        :label="t(`pages.system.role.permissions.scopes.${scope}`)"
      />
    </ElSelect>
  </ElFormItem>
  <ElFormItem
    v-if="mode === 'permission' && value.dataScope === 'CUSTOM_ORGS'"
    :label="t('pages.system.role.permissions.organizations')"
  >
    <div class="w-full">
      <ElCheckbox v-model="linked">{{ t('pages.system.role.permissions.linked') }}</ElCheckbox>
      <ElTree
        ref="organizationTree"
        :data="organizations"
        node-key="id"
        :props="{ label: 'name' }"
        show-checkbox
        check-strictly
        @check="readOrganizations"
      />
    </div>
  </ElFormItem>
  <ElFormItem v-show="mode === 'permission'" :label="t('pages.system.role.permissions.use')">
    <ElTree
      ref="useTree"
      class="role-rights-tree"
      :data="permissionTree"
      node-key="id"
      :props="{ label: 'title' }"
      show-checkbox
      check-strictly
      @check="readPermissions"
    />
  </ElFormItem>
  <ElTree
    v-show="mode === 'grant'"
    ref="grantTree"
    class="role-rights-tree"
    :data="permissionTree"
    node-key="id"
    :props="{ label: 'title' }"
    show-checkbox
    check-strictly
    @check="readPermissions"
  />
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import type { TreeInstance } from 'element-plus'
  import type { Organization } from '@/api/organization'

  export interface RoleRights {
    dataScope: string
    customOrganizationIds: string[]
    menuIds: string[]
    grantMenuIds: string[]
  }
  export interface PermissionNode {
    id: string
    parentId: string | null
    title: string
    children?: PermissionNode[]
  }
  const value = defineModel<RoleRights>({ required: true })
  const props = defineProps<{
    mode: 'permission' | 'grant'
    permissions: PermissionNode[]
    organizations: Organization[]
  }>()
  const { t } = useI18n()
  const scopes = ['SELF', 'SUB_ORG', 'ORG', 'ORG_AND_SUB', 'CUSTOM_ORGS', 'ALL']
  const useTree = ref<TreeInstance>()
  const grantTree = ref<TreeInstance>()
  const organizationTree = ref<TreeInstance>()
  const linked = ref(true)
  const permissionTree = computed(() => {
    const nodes = new Map(
      props.permissions.map((node) => [node.id, { ...node, children: [] as PermissionNode[] }])
    )
    const roots: PermissionNode[] = []
    for (const node of nodes.values()) {
      const parent = node.parentId ? nodes.get(node.parentId) : undefined
      if (parent) parent.children.push(node)
      else roots.push(node)
    }
    return roots
  })
  function readPermissions() {
    if (props.mode === 'permission')
      value.value.menuIds = (useTree.value?.getCheckedKeys(false) ?? []).map(String)
    if (props.mode === 'grant')
      value.value.grantMenuIds = (grantTree.value?.getCheckedKeys(false) ?? []).map(String)
  }
  function readOrganizations(node: Organization) {
    const selected = new Set((organizationTree.value?.getCheckedKeys(false) ?? []).map(String))
    if (linked.value) {
      const checked = selected.has(node.id)
      function visit(children: Organization[]) {
        for (const child of children) {
          if (checked) selected.add(child.id)
          else selected.delete(child.id)
          visit(child.children ?? [])
        }
      }
      visit(node.children ?? [])
      organizationTree.value?.setCheckedKeys([...selected])
    }
    value.value.customOrganizationIds = [...selected]
  }
  function installSelection() {
    useTree.value?.setCheckedKeys(value.value.menuIds)
    grantTree.value?.setCheckedKeys(value.value.grantMenuIds)
    organizationTree.value?.setCheckedKeys(value.value.customOrganizationIds)
  }
  function activeTree() {
    return props.mode === 'permission' ? useTree.value : grantTree.value
  }
  function expandAll(expanded: boolean) {
    const tree = activeTree()
    if (!tree) return
    for (const node of Object.values(tree.store.nodesMap) as Array<{ expanded: boolean }>) {
      node.expanded = expanded
    }
  }
  function selectAll(selected: boolean) {
    const tree = activeTree()
    if (!tree) return
    const keys: string[] = []
    function collect(nodes: Array<{ id: string; children?: Array<{ id: string }> }>) {
      for (const node of nodes) {
        keys.push(node.id)
        collect(node.children ?? [])
      }
    }
    collect(permissionTree.value)
    tree.setCheckedKeys(selected ? keys : [])
    readPermissions()
  }
  defineExpose({ expandAll, selectAll })
  onMounted(installSelection)
  watch(organizationTree, installSelection, { flush: 'post' })
  watch(
    () => [value.value, props.mode, props.permissions],
    () => {
      linked.value = true
      installSelection()
    },
    { flush: 'post' }
  )
</script>

<style scoped>
  /* 父子同时展开时由父层承载动画，避免父层按尚未展开的子树测量高度。 */
  .role-rights-tree :deep(.el-collapse-transition-enter-active .el-tree-node__children) {
    max-height: none !important;
    transition: none !important;
  }
</style>
