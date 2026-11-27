<template>
  <ElDrawer
    v-model="drawerVisible"
    :title="t('pages.system.account.editDrawer.title')"
    :size="drawerSize"
    append-to-body
    modal-class="account-drawer-modal"
    class="account-edit-drawer"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
    :before-close="beforeClose"
    @opened="tabTransitionReady = true"
    @closed="handleClosed"
  >
    <template v-if="localDetail">
      <div class="account-edit-page">
        <ElAlert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
        <section class="edit-hero">
          <div class="hero-identity">
            <ElUpload
              :http-request="handleAvatarUpload"
              :show-file-list="false"
              :before-upload="beforeAvatarUpload"
              disabled
              accept="image/*"
              class="hero-avatar-upload"
            >
              <div class="hero-avatar-trigger">
                <ElImage :src="heroAvatar" fit="cover" class="hero-avatar" />
                <div class="hero-avatar-overlay">
                  <FreesiaSvgIcon icon="ri:edit-line" class="hero-avatar-icon" />
                </div>
              </div>
            </ElUpload>
            <div class="hero-copy">
              <div class="hero-title-row">
                <h1 class="hero-title">{{ localDetail.header.username }}</h1>
                <ElTag :type="accountStatusTag.type">{{ t(accountStatusTag.textKey) }}</ElTag>
              </div>
              <p class="hero-subtitle">
                {{ localDetail.header.nickname || localDetail.header.realName || '-' }}
              </p>
            </div>
          </div>
        </section>

        <div
          ref="tabsShellRef"
          class="tab-scroll-shell"
          :class="{ 'is-scrollable': tabsScrollable }"
          @click.capture="tabsDrag.preventClickAfterDrag"
          @pointercancel="tabsDrag.endDrag"
          @pointerdown="startTabsDrag"
          @pointermove="tabsDrag.moveDrag"
          @pointerup="tabsDrag.endDrag"
        >
          <ElButton
            v-if="tabsScrollable"
            :icon="ArrowLeft"
            :disabled="!canScrollTabsLeft"
            link
            size="small"
            class="tab-scroll-nav tab-scroll-nav--left"
            aria-label="向左滚动标签"
            @click="scrollTabs('left')"
          />
          <ElTabs
            v-model="activeTab"
            class="edit-tabs tab-scroll-tabs"
            :class="{
              'is-dragging': tabsDrag.isDragging.value,
              'tabs-no-anim': !tabTransitionReady
            }"
          >
            <!-- 个人资料 -->
            <ElTabPane :label="t('pages.system.account.editDrawer.tabs.profile')" name="profile">
              <ElForm
                ref="profileFormRef"
                :model="profileForm"
                :rules="profileRules"
                :disabled="!hasAuth('system:account:update')"
                label-position="top"
                class="edit-form edit-form--compact"
              >
                <ElFormItem
                  :label="t('pages.system.accountCenter.basicSettings.fields.nickname')"
                  prop="nickname"
                >
                  <ElInput
                    v-model="profileForm.nickname"
                    :placeholder="
                      t('pages.system.accountCenter.basicSettings.placeholders.nickname')
                    "
                  />
                </ElFormItem>
                <div class="form-grid">
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.realName')"
                    prop="realName"
                  >
                    <ElInput
                      v-model="profileForm.realName"
                      disabled
                      :placeholder="
                        t('pages.system.accountCenter.basicSettings.placeholders.realName')
                      "
                    />
                  </ElFormItem>
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.sex')"
                    prop="gender"
                  >
                    <ElSelect
                      v-model="profileForm.gender"
                      disabled
                      class="w-full"
                      :placeholder="t('pages.system.accountCenter.basicSettings.placeholders.sex')"
                    >
                      <ElOption
                        v-for="opt in genderOptions"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.email')"
                    prop="email"
                  >
                    <ElInput
                      v-model="profileForm.email"
                      :placeholder="
                        t('pages.system.accountCenter.basicSettings.placeholders.email')
                      "
                    />
                  </ElFormItem>
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.mobile')"
                    prop="mobile"
                  >
                    <ElInput
                      v-model="profileForm.mobile"
                      :placeholder="
                        t('pages.system.accountCenter.basicSettings.placeholders.mobile')
                      "
                    />
                  </ElFormItem>
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.position')"
                    prop="position"
                  >
                    <ElInput
                      v-model="profileForm.position"
                      disabled
                      :placeholder="
                        t('pages.system.accountCenter.basicSettings.placeholders.position')
                      "
                    />
                  </ElFormItem>
                  <ElFormItem
                    :label="t('pages.system.accountCenter.basicSettings.fields.company')"
                    prop="company"
                  >
                    <ElInput
                      v-model="profileForm.company"
                      disabled
                      :placeholder="
                        t('pages.system.accountCenter.basicSettings.placeholders.company')
                      "
                    />
                  </ElFormItem>
                </div>
                <ElFormItem
                  :label="t('pages.system.accountCenter.basicSettings.fields.address')"
                  prop="address"
                >
                  <ElInput
                    v-model="profileForm.address"
                    disabled
                    :placeholder="
                      t('pages.system.accountCenter.basicSettings.placeholders.address')
                    "
                  />
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.accountCenter.basicSettings.fields.signature')"
                  prop="signature"
                >
                  <ElInput
                    v-model="profileForm.signature"
                    disabled
                    :placeholder="
                      t('pages.system.accountCenter.basicSettings.placeholders.signature')
                    "
                  />
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.accountCenter.basicSettings.fields.description')"
                  prop="introduction"
                >
                  <ElInput
                    v-model="profileForm.introduction"
                    disabled
                    type="textarea"
                    :rows="4"
                    :placeholder="
                      t('pages.system.accountCenter.basicSettings.placeholders.description')
                    "
                  />
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.accountCenter.basicSettings.fields.tags')"
                  prop="tags"
                >
                  <ElSelect
                    v-model="profileForm.tags"
                    disabled
                    multiple
                    filterable
                    allow-create
                    default-first-option
                    :max-collapse-tags="3"
                    class="w-full"
                    :placeholder="t('pages.system.accountCenter.basicSettings.placeholders.tags')"
                  />
                </ElFormItem>
              </ElForm>
            </ElTabPane>

            <!-- 账号设置 -->
            <ElTabPane :label="t('pages.system.account.editDrawer.tabs.account')" name="account">
              <ElForm
                ref="accountFormRef"
                :model="accountForm"
                :rules="accountRules"
                label-position="top"
                class="edit-form edit-form--compact"
              >
                <ElFormItem
                  :label="t('pages.system.account.dialog.fields.username')"
                  prop="username"
                >
                  <ElInput
                    v-model="accountForm.username"
                    :disabled="!hasAuth('system:account:update')"
                  />
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.account.dialog.fields.roleCodes')"
                  prop="roleIds"
                >
                  <ElSelect
                    v-model="accountForm.roleIds"
                    multiple
                    filterable
                    class="w-full"
                    :disabled="!hasAuth('system:account:assign-role')"
                  >
                    <ElOption
                      v-for="role in roleList"
                      :key="role.roleId"
                      :value="role.roleId"
                      :label="role.roleName"
                      :disabled="!manageableRoleIds.includes(role.roleId)"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.account.editDrawer.fields.organization')"
                  prop="organizationId"
                >
                  <ElSelect
                    v-model="accountForm.organizationId"
                    clearable
                    filterable
                    class="w-full"
                    :disabled="!hasAuth('system:account:transfer')"
                  >
                    <ElOption
                      v-for="node in organizationOptions"
                      :key="node.id"
                      :label="node.label"
                      :value="node.id"
                      :disabled="node.root"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem
                  :label="t('pages.system.account.editDrawer.fields.accountStatus')"
                  prop="accountStatus"
                >
                  <ElSelect
                    v-model="accountForm.accountStatus"
                    class="w-full"
                    :disabled="!canChangeStatus"
                  >
                    <ElOption
                      v-for="opt in statusOptions"
                      :key="opt.value"
                      :label="t(opt.labelKey)"
                      :value="opt.value"
                      :disabled="
                        String(opt.value) !== localDetail.account.status &&
                        !hasAuth(
                          opt.value === 2 ? 'system:account:disable' : 'system:account:enable'
                        )
                      "
                    />
                  </ElSelect>
                </ElFormItem>
              </ElForm>
            </ElTabPane>

            <!-- 账号安全 -->
            <ElTabPane :label="t('pages.system.account.editDrawer.tabs.security')" name="security">
              <div class="setting-list">
                <!-- 密码 -->
                <div class="setting-section setting-section--column">
                  <div class="setting-head">
                    <h3 class="setting-title">{{
                      t('pages.system.account.editDrawer.security.password.title')
                    }}</h3>
                    <p class="setting-desc">{{
                      t('pages.system.account.editDrawer.security.password.desc')
                    }}</p>
                  </div>
                  <!-- 用原生 form 包裹密码输入，消除浏览器 "Password field is not contained in a form" 警告；
                       @submit.prevent 拦截回车默认提交，改为触发重置密码 -->
                  <form class="setting-row" @submit.prevent="resetPassword">
                    <!-- 隐藏的 username 字段：供浏览器/密码管理器关联账号上下文，
                         消除 "Password forms should have (optionally hidden) username fields" 无障碍警告 -->
                    <input
                      :value="localDetail.header.username"
                      type="text"
                      name="username"
                      autocomplete="username"
                      aria-hidden="true"
                      tabindex="-1"
                      class="visually-hidden-username"
                    />
                    <ElInput
                      v-model="passwordValue"
                      :disabled="!canResetPassword || Boolean(pending)"
                      type="password"
                      show-password
                      autocomplete="new-password"
                      class="setting-block"
                      :placeholder="
                        t('pages.system.account.editDrawer.security.password.placeholder')
                      "
                    />
                    <ElButton
                      type="primary"
                      native-type="submit"
                      class="setting-row-btn"
                      :disabled="!canResetPassword || Boolean(pending)"
                      :aria-busy="pending === 'password'"
                    >
                      {{ t('pages.system.account.editDrawer.security.password.action') }}
                    </ElButton>
                  </form>
                </div>

                <!-- 安全邮箱 -->
                <div class="setting-section setting-section--column">
                  <div class="setting-head">
                    <h3 class="setting-title">{{
                      t('pages.system.account.editDrawer.security.email.title')
                    }}</h3>
                    <p class="setting-desc">{{
                      t('pages.system.account.editDrawer.security.email.desc')
                    }}</p>
                  </div>
                  <div class="setting-row">
                    <ElInput
                      v-model="emailValue"
                      disabled
                      class="setting-block"
                      :placeholder="t('pages.system.account.editDrawer.security.email.placeholder')"
                    />
                    <div class="setting-buttons setting-buttons--compact">
                      <ElButton
                        type="danger"
                        plain
                        class="freesia-batch-delete setting-email-btn"
                        disabled
                        @click="clearSecurityEmail"
                      >
                        {{ t('pages.system.account.editDrawer.security.email.clear') }}
                      </ElButton>
                    </div>
                  </div>
                </div>

                <!-- 邮箱二次验证 -->
                <div class="setting-section">
                  <div class="setting-head">
                    <h3 class="setting-title">{{
                      t('pages.system.account.editDrawer.security.emailMfa.title')
                    }}</h3>
                    <p class="setting-desc">{{
                      t('pages.system.account.editDrawer.security.emailMfa.desc')
                    }}</p>
                  </div>
                  <div class="setting-action">
                    <ElSwitch
                      :model-value="localDetail.security.emailMfaEnabled"
                      disabled
                      @change="onEmailMfaChange"
                    />
                  </div>
                </div>

                <!-- TOTP 二次验证（仅可关闭） -->
                <div class="setting-section">
                  <div class="setting-head">
                    <h3 class="setting-title">{{
                      t('pages.system.account.editDrawer.security.totp.title')
                    }}</h3>
                    <p class="setting-desc">{{
                      t('pages.system.account.editDrawer.security.totp.desc')
                    }}</p>
                  </div>
                  <div class="setting-action">
                    <ElSwitch
                      :model-value="localDetail.security.totpMfaEnabled"
                      disabled
                      @change="onTotpChange"
                    />
                  </div>
                </div>

                <!-- 登录方式 -->
                <div class="setting-section setting-section--column">
                  <div class="setting-head">
                    <h3 class="setting-title">{{
                      t('pages.system.account.editDrawer.security.loginMethods.title')
                    }}</h3>
                    <p class="setting-desc">{{
                      t('pages.system.account.editDrawer.security.loginMethods.desc')
                    }}</p>
                  </div>
                  <div class="login-method-list">
                    <div v-for="method in loginMethodKeys" :key="method" class="login-method-row">
                      <span class="login-method-name">{{ resolveLoginMethod(method) }}</span>
                      <ElSwitch
                        :model-value="loginMethodState[method]"
                        disabled
                        @change="(val) => onLoginMethodChange(method, val)"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </ElTabPane>

            <!-- 其它 -->
            <ElTabPane :label="t('pages.system.account.editDrawer.tabs.other')" name="other">
              <ElForm
                label-position="top"
                class="edit-form"
                :disabled="!hasAuth('system:account:update')"
              >
                <ElFormItem :label="t('pages.system.account.dialog.fields.remark')">
                  <ElInput
                    v-model="accountForm.remark"
                    type="textarea"
                    :rows="4"
                    :maxlength="500"
                    show-word-limit
                    :placeholder="t('pages.system.account.dialog.placeholders.remark')"
                  />
                </ElFormItem>
              </ElForm>
            </ElTabPane>
          </ElTabs>
          <ElButton
            v-if="tabsScrollable"
            :icon="ArrowRight"
            :disabled="!canScrollTabsRight"
            link
            size="small"
            class="tab-scroll-nav tab-scroll-nav--right"
            aria-label="向右滚动标签"
            @click="scrollTabs('right')"
          />
        </div>
      </div>
    </template>
    <template #footer>
      <div v-if="localDetail" class="account-edit-actions">
        <ElButton
          type="primary"
          :disabled="
            Boolean(pending) ||
            (!hasAuth('system:account:update') &&
              !hasAuth('system:account:assign-role') &&
              !hasAuth('system:account:transfer') &&
              !canChangeStatus)
          "
          :aria-busy="Boolean(pending)"
          @click="saveAccount"
        >
          {{ t('pages.system.account.editDrawer.save') }}
        </ElButton>
      </div>
    </template>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { computed, nextTick, reactive, ref, watch } from 'vue'
  import { useWindowSize } from '@vueuse/core'
  import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import type { FormInstance, FormRules, UploadRawFile } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { useHorizontalDragScroll } from '@/hooks'
  import { fetchFileUpload } from '@/api/file'
  import {
    fetchAdminDisableTotp,
    fetchAdminResetPassword,
    fetchAdminSetSecurityEmail,
    fetchAdminUpdateLoginMethods,
    fetchAdminUpdateMfaEmail,
    fetchAssignableAccountRoles,
    fetchChangeAccountStatus,
    fetchGetAccountDetailCard,
    fetchTransferAccountOrganization,
    fetchUpdateAccountProfile,
    fetchUpdateAccountRoles
  } from '@/api/system-manage'
  import { getOrganizationOptions, type Organization } from '@/api/organization'
  import { useAuth } from '@/hooks/core/useAuth'

  const props = defineProps<{
    visible: boolean
    detail?: Api.SystemManage.AccountDetailCard
  }>()

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
    (e: 'saved'): void
  }>()

  const { t } = useI18n()
  const { hasAuth } = useAuth()
  const { width } = useWindowSize()
  const activeTab = ref('profile')
  // tab 切换动画开关：抽屉滑入期间关闭（避免上次选中的 tab 颜色渐变重放造成"闪一下"），
  // 抽屉完全打开后(@opened)再开启，保证正常点击切换仍有过渡。
  const tabTransitionReady = ref(false)
  // 保存期间只阻止重复提交，不给按钮插入图标或改变宽度。
  const pending = ref('')
  const saveError = ref('')
  const statusUncertain = ref(false)
  // 保存成功或状态请求结果不明后置为 true；仅在抽屉关闭时通知列表刷新一次，
  // 避免每次保存都触发列表 loading 导致表格抖动。
  const dirty = ref(false)
  const localDetail = ref<Api.SystemManage.AccountDetailCard | undefined>(props.detail)
  const roleList = ref<Api.SystemManage.RoleOption[]>([])
  const manageableRoleIds = ref<string[]>([])
  const originalRoleIds = ref<string[]>([])
  const organizations = ref<Organization[]>([])
  const organizationOptions = computed(() => {
    const options: Array<{ id: string; label: string; root: boolean }> = []
    function visit(nodes: Organization[], depth: number) {
      for (const node of nodes) {
        options.push({
          id: node.id,
          label: `${'　'.repeat(depth)}${node.name} (${node.code})`,
          root: node.root
        })
        visit(node.children ?? [], depth + 1)
      }
    }
    visit(organizations.value, 0)
    const current = localDetail.value?.account
    if (current?.organizationId && !options.some((node) => node.id === current.organizationId)) {
      options.unshift({
        id: current.organizationId,
        label: current.organizationName || current.organizationId,
        root: true
      })
    }
    return options
  })

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const drawerSize = computed(() => {
    if (width.value <= 640) return '100%'
    if (width.value <= 960) return '86%'
    return '760px'
  })

  const profileFormRef = ref<FormInstance>()
  const accountFormRef = ref<FormInstance>()

  const profileForm = reactive({
    nickname: '',
    realName: '',
    gender: 0,
    email: '',
    mobile: '',
    position: '',
    company: '',
    address: '',
    signature: '',
    introduction: '',
    tags: [] as string[],
    avatar: ''
  })

  const accountForm = reactive({
    username: '',
    roleIds: [] as string[],
    organizationId: null as string | null,
    accountStatus: 1,
    remark: ''
  })

  const passwordValue = ref('')
  const emailValue = ref('')
  const loginMethodState = reactive<Record<string, boolean>>({})

  // ===== Tab 横向拖拽滚动（与账号信息抽屉一致，移动端/桌面端同款 touch 事件） =====
  const tabsShellRef = ref<HTMLElement>()
  const tabsScrollable = ref(false)
  const canScrollTabsLeft = ref(false)
  const canScrollTabsRight = ref(false)
  const tabsDrag = useHorizontalDragScroll()

  const getTabsScroller = (shell?: HTMLElement) =>
    shell?.querySelector<HTMLElement>('.el-tabs__nav-scroll')

  const updateTabsScrollState = () => {
    const scroller = getTabsScroller(tabsShellRef.value)
    if (!scroller) {
      tabsScrollable.value = false
      canScrollTabsLeft.value = false
      canScrollTabsRight.value = false
      return
    }
    const maxScrollLeft = scroller.scrollWidth - scroller.clientWidth
    tabsScrollable.value = maxScrollLeft > 1
    canScrollTabsLeft.value = scroller.scrollLeft > 1
    canScrollTabsRight.value = scroller.scrollLeft < maxScrollLeft - 1
  }

  const scrollTabs = (direction: 'left' | 'right') => {
    const scroller = getTabsScroller(tabsShellRef.value)
    if (!scroller) return
    const distance = Math.max(scroller.clientWidth * 0.8, 96)
    scroller.scrollBy({
      left: direction === 'left' ? -distance : distance,
      behavior: 'smooth'
    })
    window.setTimeout(updateTabsScrollState, 260)
  }

  // 仅当指针落在标签条（nav-scroll）上才发起拖拽，避免影响 Tab 内表单控件的交互。
  const startTabsDrag = (event: PointerEvent) => {
    const target = event.target as HTMLElement | null
    if (!target || !target.closest('.el-tabs__nav-scroll')) return
    tabsDrag.startDrag(event, getTabsScroller(tabsShellRef.value), updateTabsScrollState)
  }

  const genderOptions = computed(() => [
    { value: 0, label: t('pages.system.accountCenter.sex.unknown') },
    { value: 1, label: t('pages.system.accountCenter.sex.male') },
    { value: 2, label: t('pages.system.accountCenter.sex.female') },
    { value: 3, label: t('pages.system.accountCenter.sex.other') }
  ])

  const statusOptions = [
    { value: 1, labelKey: 'pages.system.account.status.enabled' },
    { value: 3, labelKey: 'pages.system.account.status.abnormal' },
    { value: 2, labelKey: 'pages.system.account.status.disabled' }
  ]
  const canChangeStatus = computed(() => {
    const account = localDetail.value?.account
    return (
      !!account &&
      !account.roleCodes.includes('R_SUPER') &&
      (hasAuth('system:account:disable') || hasAuth('system:account:enable'))
    )
  })
  const canResetPassword = computed(() => {
    const account = localDetail.value?.account
    return (
      !!account &&
      !account.roleCodes.includes('R_SUPER') &&
      account.status !== '2' &&
      hasAuth('system:account:reset-password')
    )
  })

  const profileRules = computed<FormRules>(() => ({
    realName: [
      {
        min: 2,
        max: 50,
        message: t('pages.system.accountCenter.basicSettings.validation.nameLength'),
        trigger: 'blur'
      }
    ],
    nickname: [
      {
        min: 2,
        max: 50,
        message: t('pages.system.accountCenter.basicSettings.validation.nameLength'),
        trigger: 'blur'
      }
    ],
    signature: [
      {
        max: 255,
        message: t('pages.system.accountCenter.basicSettings.validation.signatureLength'),
        trigger: 'blur'
      }
    ],
    position: [
      {
        max: 50,
        message: t('pages.system.accountCenter.basicSettings.validation.positionLength'),
        trigger: 'blur'
      }
    ],
    company: [
      {
        max: 100,
        message: t('pages.system.accountCenter.basicSettings.validation.companyLength'),
        trigger: 'blur'
      }
    ]
  }))

  const accountRules = computed<FormRules>(() => ({
    username: [
      {
        required: true,
        message: t('pages.system.account.dialog.validation.usernameRequired'),
        trigger: 'blur'
      },
      {
        min: 2,
        max: 50,
        message: t('pages.system.account.dialog.validation.usernameLength'),
        trigger: 'blur'
      }
    ]
  }))

  const heroAvatar = computed(() => profileForm.avatar || localDetail.value?.header.avatar || '')

  const accountStatusTag = computed(() => {
    const map: Record<
      string,
      { type: 'success' | 'info' | 'warning' | 'danger'; textKey: string }
    > = {
      '1': { type: 'success', textKey: 'pages.system.account.status.enabled' },
      '2': { type: 'info', textKey: 'pages.system.account.status.disabled' },
      '3': { type: 'warning', textKey: 'pages.system.account.status.abnormal' }
    }
    return (
      map[localDetail.value?.header.status ?? ''] || {
        type: 'info' as const,
        textKey: 'common.status.unknown'
      }
    )
  })

  const loginMethodKeys = computed(() => localDetail.value?.security.allowedLoginMethods || [])

  const resolveLoginMethod = (method: string) => {
    const mapping: Record<string, string> = {
      password: 'pages.system.account.detailCard.loginMethods.password',
      email_code: 'pages.system.account.detailCard.loginMethods.emailCode'
    }
    return t(mapping[method] || 'common.status.unknown')
  }

  const accountId = computed(() => localDetail.value?.account.accountId || '')

  const syncFromDetail = () => {
    const detail = localDetail.value
    if (!detail) return
    profileForm.nickname = detail.profile.nickname || ''
    profileForm.realName = detail.profile.realName || ''
    profileForm.gender = detail.profile.gender ?? 0
    profileForm.email = detail.profile.displayEmail || ''
    profileForm.mobile = detail.profile.displayMobile || ''
    profileForm.position = detail.profile.position || ''
    profileForm.company = detail.profile.company || ''
    profileForm.address = detail.profile.address || ''
    profileForm.signature = detail.profile.signature || ''
    profileForm.introduction = detail.profile.introduction || ''
    profileForm.tags = [...(detail.profile.tags || [])]
    profileForm.avatar = detail.header.avatar || ''

    accountForm.username = detail.account.username || ''
    accountForm.roleIds = (detail.account.roles || []).map((role) => role.roleId)
    originalRoleIds.value = [...accountForm.roleIds]
    accountForm.organizationId = detail.account.organizationId
    accountForm.accountStatus = Number(detail.account.status) || 1
    statusUncertain.value = false
    accountForm.remark = detail.account.remark || ''

    const disabled = detail.security.disabledLoginMethods || []
    ;(detail.security.allowedLoginMethods || []).forEach((method) => {
      loginMethodState[method] = !disabled.includes(method)
    })

    passwordValue.value = ''
    emailValue.value = detail.security.securityEmail || ''
  }

  const loadRoleList = async () => {
    const assigned = localDetail.value?.account.roles || []
    const available = hasAuth('system:account:assign-role')
      ? await fetchAssignableAccountRoles(accountId.value)
      : []
    manageableRoleIds.value = available.map((role) => role.roleId)
    roleList.value = [
      ...available,
      ...assigned.filter((role) => !manageableRoleIds.value.includes(role.roleId))
    ]
  }

  const reload = async () => {
    if (!accountId.value) return
    localDetail.value = await fetchGetAccountDetailCard(accountId.value)
    syncFromDetail()
    await loadRoleList()
    // 仅刷新抽屉自身视图；列表统一在抽屉关闭时刷新一次。
    dirty.value = true
  }

  watch(
    () => props.detail,
    (value) => {
      localDetail.value = value
      syncFromDetail()
    }
  )

  watch(
    () => props.visible,
    (visible) => {
      passwordValue.value = ''
      if (visible) {
        activeTab.value = 'profile'
        dirty.value = false
        saveError.value = ''
        localDetail.value = props.detail
        syncFromDetail()
        void loadRoleList()
        if (hasAuth('system:account:transfer')) {
          void getOrganizationOptions('system:account:transfer').then((nodes) => {
            organizations.value = nodes
          })
        } else organizations.value = []
        // 内容复用（未销毁）时清除上一次的校验态，避免重新打开仍显示红色报错。
        nextTick(() => {
          profileFormRef.value?.clearValidate()
          accountFormRef.value?.clearValidate()
        })
      }
    },
    { immediate: true }
  )

  const beforeClose = (done: () => void) => {
    if (!pending.value) done()
  }

  const handleClosed = () => {
    tabTransitionReady.value = false
    activeTab.value = 'profile'
    if (dirty.value) {
      dirty.value = false
      emit('saved')
    }
  }

  watch(
    () => [props.visible, activeTab.value, width.value, Boolean(localDetail.value)],
    () => {
      nextTick(updateTabsScrollState)
    },
    { immediate: true }
  )

  const beforeAvatarUpload = (file: UploadRawFile) => {
    const isImage = file.type?.startsWith('image/')
    const isLt2M = (file.size || 0) / 1024 / 1024 < 2
    if (!isImage) {
      ElMessage.error(t('pages.system.accountCenter.messages.onlyImageAllowed'))
      return false
    }
    if (!isLt2M) {
      ElMessage.error(t('pages.system.accountCenter.messages.imageSizeLimit'))
      return false
    }
    return true
  }

  const handleAvatarUpload = async (options: { file: File }) => {
    try {
      const avatarUrl = await fetchFileUpload(options.file, 'avatar')
      profileForm.avatar = avatarUrl
      ElMessage.success(t('pages.system.accountCenter.messages.avatarUploadSuccess'))
    } catch {
      ElMessage.error(t('pages.system.accountCenter.messages.avatarUploadFailed'))
    }
  }

  const saveAccount = async () => {
    if (pending.value || !localDetail.value) return
    pending.value = 'account'
    saveError.value = ''
    let savedOtherChanges = false
    try {
      const targetId = accountId.value
      const submitted = {
        username: accountForm.username.trim(),
        remark: accountForm.remark,
        nickname: profileForm.nickname,
        email: profileForm.email,
        phone: profileForm.mobile
      }
      const submittedRoleIds = [...accountForm.roleIds]
      const submittedRoles = roleList.value.filter((role) => submittedRoleIds.includes(role.roleId))
      const nextStatus = accountForm.accountStatus as 1 | 2 | 3
      const statusChanged =
        statusUncertain.value || String(nextStatus) !== localDetail.value.account.status
      const statusAction = nextStatus === 2 ? 'disable' : 'enable'
      const basicChanged =
        submitted.username !== localDetail.value.account.username ||
        submitted.remark !== (localDetail.value.account.remark ?? '') ||
        submitted.nickname !== (localDetail.value.profile.nickname ?? '') ||
        submitted.email !== (localDetail.value.profile.displayEmail ?? '') ||
        submitted.phone !== (localDetail.value.profile.displayMobile ?? '')
      const destination = accountForm.organizationId || null
      const organizationChanged = destination !== localDetail.value.account.organizationId
      const addRoleIds = accountForm.roleIds.filter(
        (id) => manageableRoleIds.value.includes(id) && !originalRoleIds.value.includes(id)
      )
      const removeRoleIds = originalRoleIds.value.filter(
        (id) => manageableRoleIds.value.includes(id) && !accountForm.roleIds.includes(id)
      )
      if (
        (basicChanged && !hasAuth('system:account:update')) ||
        (organizationChanged && !hasAuth('system:account:transfer')) ||
        (statusChanged && !hasAuth(`system:account:${statusAction}`)) ||
        ((addRoleIds.length > 0 || removeRoleIds.length > 0) &&
          !hasAuth('system:account:assign-role'))
      ) {
        ElMessage.error(t('pages.system.account.editDrawer.messages.permissionDenied'))
        return
      }
      if (basicChanged) {
        const validations = await Promise.all([
          profileFormRef.value?.validate().catch(() => false),
          accountFormRef.value?.validate().catch(() => false)
        ])
        if (validations.some((valid) => valid === false)) return
      }
      if (statusChanged && ![1, 2, 3].includes(nextStatus)) {
        throw new Error(t('pages.system.account.lifecycle.failed'))
      }
      if (organizationChanged) {
        await fetchTransferAccountOrganization(targetId, destination)
        localDetail.value.account.organizationId = destination
        localDetail.value.account.organizationName =
          organizations.value.find((node) => node.id === destination)?.name ?? null
        dirty.value = true
        savedOtherChanges = true
      }
      if (basicChanged) {
        await fetchUpdateAccountProfile(targetId, {
          ...submitted
        })
        localDetail.value.account.username = submitted.username
        localDetail.value.header.username = submitted.username
        localDetail.value.account.remark = submitted.remark
        localDetail.value.profile.nickname = submitted.nickname
        localDetail.value.header.nickname = submitted.nickname
        localDetail.value.profile.displayEmail = submitted.email
        localDetail.value.profile.displayMobile = submitted.phone
        dirty.value = true
        savedOtherChanges = true
      }
      if (addRoleIds.length || removeRoleIds.length) {
        await fetchUpdateAccountRoles(targetId, { addRoleIds, removeRoleIds })
        originalRoleIds.value = submittedRoleIds
        localDetail.value.account.roles = submittedRoles
        localDetail.value.account.roleCodes = localDetail.value.account.roles.map(
          (role) => role.roleCode
        )
        dirty.value = true
        savedOtherChanges = true
      }
      // Status is a separate authorized command, applied last so self-disable cannot
      // interrupt the preceding profile/organization/role requests.
      if (statusChanged) {
        // A timeout may hide a committed change. Even selecting the original value
        // must explicitly apply that desired state on the next Save.
        statusUncertain.value = true
        dirty.value = true
        if (!(await fetchChangeAccountStatus(targetId, nextStatus))) {
          throw new Error(t('pages.system.account.lifecycle.failed'))
        }
        dirty.value = true
        localDetail.value.account.status = String(nextStatus)
        localDetail.value.header.status = String(nextStatus)
        statusUncertain.value = false
      }
      ElMessage.success(t('pages.system.account.editDrawer.messages.success'))
      dirty.value = true
    } catch (failure) {
      // Keep the draft on failure; uncertain status is explicitly reapplied on retry.
      const message =
        failure instanceof Error ? failure.message : t('pages.system.account.lifecycle.failed')
      saveError.value = [
        message,
        t('pages.system.account.lifecycle.failed'),
        ...(savedOtherChanges ? [t('pages.system.account.lifecycle.partialSave')] : [])
      ].join(' ')
    } finally {
      pending.value = ''
    }
  }

  const resetPassword = async () => {
    if (pending.value || !canResetPassword.value) return
    const value = passwordValue.value
    if (!value.trim()) {
      ElMessage.error(t('pages.system.account.editDrawer.messages.passwordRequired'))
      return
    }
    const target = accountId.value
    pending.value = 'password'
    saveError.value = ''
    try {
      await fetchAdminResetPassword(target, { newPassword: value })
      if (accountId.value === target && passwordValue.value === value) passwordValue.value = ''
      ElMessage.success(t('pages.system.account.editDrawer.messages.passwordReset'))
      // Keep this mounted drawer and all other unsaved fields intact.
      dirty.value = true
    } catch {
      if (accountId.value === target) {
        saveError.value = t('pages.system.account.editDrawer.security.password.failed')
      }
      // A lost response is not evidence of rollback; do not replay automatically.
      dirty.value = true
    } finally {
      pending.value = ''
    }
  }

  const clearSecurityEmail = async () => {
    if (pending.value) return
    try {
      await ElMessageBox.confirm(
        t('pages.system.account.editDrawer.security.email.clearConfirm'),
        t('pages.system.account.editDrawer.security.email.clearConfirmTitle'),
        { type: 'warning' }
      )
    } catch {
      return
    }
    pending.value = 'emailClear'
    try {
      await fetchAdminSetSecurityEmail(accountId.value, { email: undefined })
      ElMessage.success(t('pages.system.account.editDrawer.messages.emailCleared'))
      await reload()
    } finally {
      pending.value = ''
    }
  }

  const onEmailMfaChange = async (value: boolean | string | number) => {
    if (pending.value) return
    pending.value = 'emailMfa'
    try {
      await fetchAdminUpdateMfaEmail(accountId.value, { enabled: Boolean(value) })
      ElMessage.success(t('pages.system.account.editDrawer.messages.mfaUpdated'))
      await reload()
    } catch {
      await reload()
    } finally {
      pending.value = ''
    }
  }

  const onTotpChange = async (value: boolean | string | number) => {
    if (value || pending.value) return
    try {
      await ElMessageBox.confirm(
        t('pages.system.account.editDrawer.security.totp.disableConfirm'),
        t('pages.system.account.editDrawer.security.totp.disableConfirmTitle'),
        { type: 'warning' }
      )
    } catch {
      return
    }
    pending.value = 'totp'
    try {
      await fetchAdminDisableTotp(accountId.value)
      ElMessage.success(t('pages.system.account.editDrawer.messages.totpDisabled'))
      await reload()
    } finally {
      pending.value = ''
    }
  }

  const onLoginMethodChange = async (method: string, value: boolean | string | number) => {
    if (pending.value) return
    loginMethodState[method] = Boolean(value)
    const enabledMethods = loginMethodKeys.value.filter((m) => loginMethodState[m])
    const disabledMethods = loginMethodKeys.value.filter((m) => !loginMethodState[m])
    pending.value = 'loginMethods'
    try {
      await fetchAdminUpdateLoginMethods(accountId.value, { enabledMethods, disabledMethods })
      ElMessage.success(t('pages.system.account.editDrawer.messages.loginMethodsUpdated'))
      await reload()
    } catch {
      await reload()
    } finally {
      pending.value = ''
    }
  }
</script>

<style scoped>
  .account-edit-drawer :deep(.el-drawer__body) {
    padding: 0;
  }

  .account-edit-page {
    width: min(100%, 760px);
    height: 100%;
    padding: 20px 24px 28px;
    margin: 0 auto;
    overflow-y: auto;
  }

  .edit-hero {
    padding-bottom: 8px;
  }

  .hero-identity {
    display: grid;
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 16px;
    align-items: center;
  }

  .hero-avatar-upload :deep(.el-upload) {
    display: block;
  }

  .hero-avatar-trigger {
    position: relative;
    width: 80px;
    height: 80px;
    overflow: hidden;
    cursor: pointer;
    border-radius: 50%;
  }

  .hero-avatar {
    width: 80px;
    height: 80px;
    border-radius: 50%;
  }

  .hero-avatar-overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgb(0 0 0 / 0%);
    opacity: 0;
    transition:
      background-color 0.18s ease,
      opacity 0.18s ease;
  }

  .hero-avatar-trigger:hover .hero-avatar-overlay {
    background: rgb(0 0 0 / 35%);
    opacity: 1;
  }

  .hero-avatar-icon {
    font-size: 22px;
    color: #fff;
  }

  .hero-copy {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    min-height: 80px;
  }

  .hero-title-row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }

  .hero-title {
    margin: 0;
    font-size: 24px;
    font-weight: 600;
    line-height: 32px;
    color: var(--freesia-text-color);
    overflow-wrap: anywhere;
  }

  .hero-subtitle {
    margin: 8px 0 0;
    font-size: 14px;
    line-height: 22px;
    color: var(--freesia-text-color-secondary, var(--el-text-color-secondary));
    overflow-wrap: anywhere;
  }

  .edit-form {
    max-width: 640px;
  }

  .edit-form--compact {
    padding-top: 7px;
  }

  .edit-form--compact :deep(.el-form-item) {
    margin-bottom: 12px;
  }

  .edit-form--compact :deep(.el-form-item__label) {
    height: 22px !important;
    margin-bottom: 15px;
    line-height: 22px !important;
  }

  .edit-form :deep(.el-select),
  .edit-form :deep(.el-input),
  .edit-form :deep(.el-textarea) {
    width: 100%;
  }

  .form-grid {
    display: grid;

    /* 响应式：每列至少 220px，容器更窄时自动降为单列，避免输入框被无限压缩 */
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
    column-gap: 20px;
  }

  .account-edit-actions {
    width: min(100%, 760px);
    padding: 0 24px;
    margin: 0 auto;
    text-align: left;
  }

  .w-full {
    width: 100%;
  }

  .setting-list {
    display: flex;
    flex-direction: column;
  }

  .setting-section {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 16px 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .setting-section--column {
    flex-direction: column;
    align-items: stretch;
  }

  .setting-head {
    min-width: 0;
  }

  .setting-title {
    margin: 0;
    font-size: 15px;
    font-weight: 500;
    line-height: 22px;
    color: var(--freesia-text-color);
  }

  .setting-desc {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 18px;
    color: var(--freesia-text-color-secondary, var(--el-text-color-secondary));
  }

  .setting-action {
    flex: 0 0 auto;
  }

  .setting-row {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    width: 100%;
    margin-top: 12px;
  }

  /* 隐藏的 username 字段：用 0 尺寸而非 display:none / visibility:hidden，
     后两者会让浏览器忽略该字段、无法与密码框关联（autofill 上下文失效）。 */
  .visually-hidden-username {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }

  .setting-block {
    /* 输入框不再拉伸撑满：固定一个合适基准宽度，让保存/清除按钮紧贴其右侧并整体左对齐；
       窄屏时缩到最小宽度（新边界 220px）后按钮整体换行，而不是把输入框压扁。 */
    flex: 0 1 360px;
    min-width: min(220px, 100%);
  }

  .setting-row-btn {
    flex-shrink: 0;
  }

  .setting-buttons {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  /* 安全邮箱两个按钮（保存 + 清除）更紧凑 */
  .setting-buttons--compact {
    gap: 4px;
  }

  /* 安全邮箱按钮固定最小宽度：loading 时左侧多出的 spinner 不再撑宽按钮、挤压周围元素。
     min-width 预留出 spinner 占位，文字保持居中，宽度在 loading 前后保持一致。 */
  .setting-email-btn {
    min-width: 96px;
  }

  .login-method-list {
    display: flex;
    flex-direction: column;
    width: 100%;
    margin-top: 12px;
  }

  .login-method-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 0;
  }

  .login-method-name {
    line-height: 22px;
    color: var(--freesia-text-color);
  }

  /* ===== Tab 横向拖拽滚动（与账号信息抽屉一致） ===== */
  .edit-tabs {
    margin-top: 14px;
  }

  .tab-scroll-shell {
    position: relative;
  }

  .tab-scroll-shell.is-scrollable .tab-scroll-tabs :deep(.el-tabs__header) {
    padding-right: 28px;
    padding-left: 28px;
  }

  .tab-scroll-tabs :deep(.el-tabs__nav-wrap.is-scrollable) {
    padding: 0;
  }

  .tab-scroll-tabs :deep(.el-tabs__nav-prev),
  .tab-scroll-tabs :deep(.el-tabs__nav-next) {
    display: none;
  }

  .tab-scroll-tabs :deep(.el-tabs__nav-scroll) {
    overflow-x: auto;
    scrollbar-width: none;
    touch-action: pan-y;
    cursor: grab;
    user-select: none;
  }

  .tab-scroll-tabs :deep(.el-tabs__nav-scroll::-webkit-scrollbar) {
    display: none;
  }

  .tab-scroll-tabs :deep(.el-tabs__nav) {
    flex-wrap: nowrap;
    transform: none !important;
  }

  .tab-scroll-tabs.is-dragging :deep(.el-tabs__nav-scroll) {
    cursor: grabbing;
  }

  .tab-scroll-nav {
    position: absolute;
    top: 0;
    z-index: 2;
    width: 24px;
    height: 32px;
    padding: 0;
    color: var(--freesia-text-color-secondary, var(--el-text-color-secondary));
  }

  .tab-scroll-nav--left {
    left: 0;
  }

  .tab-scroll-nav--right {
    right: 0;
  }

  .tab-scroll-nav.is-disabled {
    opacity: 0.35;
  }

  .edit-tabs :deep(.el-tabs__header) {
    margin-bottom: 2px;
  }

  .tabs-no-anim :deep(.el-tabs__item) {
    transition: none !important;
  }

  @media (width <= 640px) {
    .account-edit-actions {
      padding: 0 16px;
    }

    .account-edit-page {
      width: 100%;
      padding: 16px 16px 24px;
    }

    .form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
