<template>
  <div>
    <div class="page-head"><h1>分类</h1></div>
    <el-card>
      <div class="toolbar">
        <el-input v-model="draft.name" placeholder="新分类名称" maxlength="50" style="width: 180px" @keyup.enter="create" />
        <el-input v-model="draft.nameEn" placeholder="英文名（可选）" maxlength="50" style="width: 180px" @keyup.enter="create" />
        <el-input-number v-model="draft.sortOrder" :step="1" controls-position="right" style="width: 120px" title="排序，数字越小越靠前" />
        <el-button type="primary" @click="create">添加分类</el-button>
      </div>
      <el-table :data="list" v-loading="loading" empty-text="还没有分类">
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><el-input v-model="row.name" maxlength="50" /></template>
        </el-table-column>
        <el-table-column label="英文名（英文前台显示）" min-width="160">
          <template #default="{ row }"><el-input v-model="row.nameEn" maxlength="50" placeholder="不填显示中文名" /></template>
        </el-table-column>
        <el-table-column label="排序" width="140">
          <template #default="{ row }"><el-input-number v-model="row.sortOrder" controls-position="right" style="width: 110px" /></template>
        </el-table-column>
        <el-table-column label="商品数" width="90" align="right">
          <template #default="{ row }">
            <router-link :to="{ path: '/products', query: { category: row.id } }" class="mono">{{ row.productCount }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="save(row)">保存</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'

const list = ref([])
const loading = ref(false)
const draft = reactive({ name: '', nameEn: '', sortOrder: 0 })

async function load() {
  loading.value = true
  try {
    list.value = await api.get('/api/admin/categories')
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!draft.name.trim()) return
  try {
    await api.post('/api/admin/categories', { ...draft })
    ElMessage.success(`已添加分类「${draft.name.trim()}」`)
    Object.assign(draft, { name: '', nameEn: '', sortOrder: 0 })
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function save(row) {
  try {
    await api.put(`/api/admin/categories/${row.id}`, { name: row.name, nameEn: row.nameEn, sortOrder: row.sortOrder ?? 0 })
    ElMessage.success('已保存')
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`删除分类「${row.name}」？`, '删除分类', { type: 'warning', confirmButtonText: '删除' })
  try {
    await api.del(`/api/admin/categories/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

onMounted(load)
</script>
