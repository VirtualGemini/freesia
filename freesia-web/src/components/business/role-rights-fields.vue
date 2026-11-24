<template>
  <ElFormItem :label="english ? 'Data permission' : '数据权限'">
    <ElSelect v-model="value.dataScope">
      <ElOption
        v-for="scope in scopes"
        :key="scope[0]"
        :value="scope[0]"
        :label="english ? scope[2] : scope[1]"
      />
    </ElSelect>
  </ElFormItem>
  <ElFormItem
    v-if="value.dataScope === 'CUSTOM_ORGS'"
    :label="english ? 'Organizations' : '指定组织'"
  >
    <div class="w-full">
      <ElCheckbox v-model="linked">{{
        english ? 'Select children with parent' : '父子联动'
      }}</ElCheckbox>
      <ElTree
        ref="organizationTree"
        :data="organizations"
        node-key="id"
        :props="{ label: 'name' }"
        show-checkbox
        check-strictly
        default-expand-all
        @check="readOrganizations"
      />
    </div>
  </ElFormItem>
  <ElFormItem :label="english ? 'Menu permissions' : '菜单权限（可使用）'">
    <ElTree
      ref="useTree"
      :data="permissionTree"
      node-key="id"
      :props="{ label: 'title' }"
      show-checkbox
      check-strictly
      default-expand-all
      @check="readPermissions"
    />
  </ElFormItem>
  <ElFormItem :label="english ? 'Grant permissions' : '可分配权限（可授予）'">
    <ElTree
      ref="grantTree"
      :data="permissionTree"
      node-key="id"
      :props="{ label: 'title' }"
      show-checkbox
      check-strictly
      default-expand-all
      @check="readPermissions"
    />
  </ElFormItem>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import request from '@/utils/http'
  import { getOrganizationOptions, type Organization } from '@/api/organization'
  import { useAccountStore } from '@/store/modules/user'

  export interface RoleRights {
    dataScope: string
    customOrganizationIds: string[]
    menuIds: string[]
    grantMenuIds: string[]
  }
  interface PermissionNode {
    id: string
    parentId: string | null
    title: string
    children?: PermissionNode[]
  }
  const value = defineModel<RoleRights>({ required: true })
  const props = defineProps<{ permission: string }>()
  const { locale } = useI18n()
  const english = computed(() => locale.value.startsWith('en'))
  const scopes = [
    ['SELF', '仅本人数据权限', 'Own data only'],
    ['SUB_ORG', '仅所在组织下级组织数据权限', 'Descendant organizations only'],
    ['ORG', '所在组织数据权限', 'Current organization'],
    ['ORG_AND_SUB', '所在组织及下级数据权限', 'Current organization and descendants'],
    ['CUSTOM_ORGS', '指定组织数据权限', 'Selected organizations'],
    ['ALL', '全部数据权限', 'All data']
  ]
  const useTree = ref()
  const grantTree = ref()
  const organizationTree = ref()
  const linked = ref(true)
  const organizations = ref<Organization[]>([])
  const permissions = ref<PermissionNode[]>([])
  const permissionTree = computed(() => {
    const nodes = new Map(
      permissions.value.map((node) => [node.id, { ...node, children: [] as PermissionNode[] }])
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
    value.value.menuIds = useTree.value?.getCheckedKeys(false) ?? []
    value.value.grantMenuIds = grantTree.value?.getCheckedKeys(false) ?? []
  }
  function readOrganizations(node: Organization) {
    const selected = new Set<string>(organizationTree.value?.getCheckedKeys(false) ?? [])
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
  onMounted(async () => {
    permissions.value = await request.get<PermissionNode[]>({ url: '/api/role/permission-options' })
    if ((useAccountStore().info.buttons ?? []).includes(props.permission)) {
      organizations.value = await getOrganizationOptions(props.permission)
    }
    await nextTick()
    installSelection()
  })
  watch(
    () => [value.value.dataScope, value.value.menuIds, value.value.grantMenuIds],
    () => nextTick(installSelection)
  )
</script>
