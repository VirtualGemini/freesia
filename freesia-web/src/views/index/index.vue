<!-- 布局容器 -->
<template>
  <div class="app-layout">
    <aside id="app-sidebar">
      <FreesiaSidebarMenu />
    </aside>

    <main id="app-main">
      <div id="app-header">
        <FreesiaHeaderBar />
      </div>
      <div id="app-content">
        <FreesiaPageContent />
      </div>
    </main>

    <div id="app-global">
      <FreesiaGlobalComponent />
    </div>
  </div>
</template>

<script setup lang="ts">
  defineOptions({ name: 'AppLayout' })
</script>

<style lang="scss" scoped>
  @use './style';
</style>
