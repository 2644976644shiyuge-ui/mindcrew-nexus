<template>
  <div class="office-3d-page">
    <iframe
      src="/3d-office/"
      class="office-iframe"
      frameborder="0"
      allow="fullscreen; accelerometer; gyroscope"
      allowfullscreen
    />
  </div>
</template>

<script setup lang="ts">
// 3D 办公室可视化 · 嵌入 React + Three.js 应用
// 源项目：nexus-3d-office（独立 React + Vite 构建，部署在 /3d-office/）
</script>

<style scoped>
.office-3d-page {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #f5f5f7;
}
.office-iframe {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}
</style>
