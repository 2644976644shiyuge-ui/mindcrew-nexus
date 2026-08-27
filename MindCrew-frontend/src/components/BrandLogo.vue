<template>
  <img
    v-if="brand.logoUrl"
    class="brand-logo-img"
    :src="brand.logoUrl"
    :alt="brand.systemName"
    :style="logoStyle"
  />
  <MindCrewIcon v-else :size="size" :color="color" :accent-color="accentColor" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useBrandStore } from '@/stores/brand'
import MindCrewIcon from '@/components/MindCrewIcon.vue'

const props = withDefaults(defineProps<{
  size?: number | string
  color?: string
  accentColor?: string
}>(), {
  size: 32,
  color: '#ffffff',
  accentColor: '#38bdf8',
})

const brand = useBrandStore()
const logoSize = computed(() => `${Number(props.size) || 32}px`)
const logoStyle = computed(() => ({
  width: logoSize.value,
  height: logoSize.value,
}))
</script>

<style scoped>
.brand-logo-img {
  display: block;
  object-fit: contain;
  border-radius: 8px;
}
</style>
