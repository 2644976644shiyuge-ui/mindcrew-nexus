import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { brandApi, type BrandSettings } from '@/api/brand'

const DEFAULT_BRAND: BrandSettings = {
  systemName: 'ZYCOO Nexus',
  logoUrl: null,
}

export const useBrandStore = defineStore('brand', () => {
  const systemName = ref(DEFAULT_BRAND.systemName)
  const logoUrl = ref<string | null>(DEFAULT_BRAND.logoUrl)
  const loaded = ref(false)

  const tabTitle = computed(() => `${systemName.value} · 智能知识库`)

  function applyBrand(brand: Partial<BrandSettings>) {
    systemName.value = (brand.systemName || DEFAULT_BRAND.systemName).trim()
    logoUrl.value = brand.logoUrl || null
    document.title = tabTitle.value
  }

  async function fetchBrand() {
    try {
      const brand = await brandApi.getPublic()
      applyBrand(brand)
    } finally {
      loaded.value = true
    }
  }

  return { systemName, logoUrl, loaded, tabTitle, applyBrand, fetchBrand }
})
