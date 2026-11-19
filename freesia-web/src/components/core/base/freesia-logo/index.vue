<!-- 系统logo -->
<template>
  <div class="flex-cc">
    <img :style="logoStyle" :src="logoUrl" :alt="product.name" class="block object-contain" />
  </div>
</template>

<script setup lang="ts">
  import { product } from '@/config/product'

  const logoUrl = `${import.meta.env.BASE_URL}${product.logo.replace(/^\//, '')}`
  defineOptions({ name: 'FreesiaLogo' })

  interface Props {
    /** logo 大小 */
    size?: number | string
  }

  const props = withDefaults(defineProps<Props>(), {
    size: 36
  })

  const logoStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
</script>
