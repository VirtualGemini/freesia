<template>
  <div class="account-cell">
    <span class="account-name">{{ formatFallback(username) }}</span>
    <span class="account-id">{{ formatFallback(accountId) }}</span>
  </div>
</template>

<script setup lang="ts">
  import { formatFallback } from '../utils'

  defineProps<{
    username?: string
    accountId?: string
  }>()
</script>

<style scoped>
  .account-cell {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .account-name {
    line-height: 20px;
    color: var(--freesia-text-color);
    overflow-wrap: anywhere;
  }

  .account-id {
    font-size: 12px;
    line-height: 18px;
    color: var(--freesia-text-color-secondary, var(--el-text-color-secondary));
    overflow-wrap: anywhere;
  }
</style>
