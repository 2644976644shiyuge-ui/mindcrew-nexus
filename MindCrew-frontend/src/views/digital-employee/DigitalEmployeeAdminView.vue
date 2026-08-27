<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { digitalEmployeeApi } from '@/api/digitalEmployee'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await digitalEmployeeApi.adminList()
  } finally {
    loading.value = false
  }
}

function create() {
  router.push({ name: 'DigitalEmployeeCreate' })
}

function edit(id: number) {
  router.push({ name: 'DigitalEmployeeEdit', params: { id } })
}

async function publish(row: any) {
  await digitalEmployeeApi.publish(row.id)
  ElMessage.success('已发布')
  load()
}

async function unpublish(row: any) {
  await digitalEmployeeApi.unpublish(row.id)
  ElMessage.success('已下线')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除「${row.name}」？`, '确认')
  await digitalEmployeeApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <header>
      <h1>数字员工管理</h1>
      <el-button type="primary" @click="create">创建数字员工</el-button>
    </header>
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="primaryScenario" label="场景" width="140" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="visibility" label="可见性" width="100" />
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row.id)">编辑</el-button>
          <el-button v-if="row.status !== 'published'" link @click="publish(row)">发布</el-button>
          <el-button v-else link @click="unpublish(row)">下线</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.wrap {
  padding: 24px;
}
header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}
</style>