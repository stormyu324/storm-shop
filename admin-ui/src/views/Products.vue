<template>
  <div>
    <div class="page-head">
      <h1>商品</h1>
      <div>
        <el-button @click="$router.push('/import')"><el-icon><Upload /></el-icon>&nbsp;批量导入</el-button>
        <el-button type="primary" @click="$router.push('/products/new')"><el-icon><Plus /></el-icon>&nbsp;添加商品</el-button>
      </div>
    </div>

    <el-card>
      <div class="toolbar">
        <el-input v-model="filters.q" placeholder="名称 / 英文标题 / SKU" clearable style="width: 240px" @keyup.enter="search" @clear="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option v-for="(s, k) in STATUS" :key="k" :label="s.label" :value="k" />
        </el-select>
        <el-select v-model="filters.category" placeholder="全部分类" clearable style="width: 150px" @change="search">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button @click="search">搜索</el-button>
        <div style="flex: 1" />
        <template v-if="selected.length">
          <span class="muted">已选 {{ selected.length }} 个</span>
          <el-button size="small" type="success" plain @click="batch('ON_SALE')">上架</el-button>
          <el-button size="small" type="warning" plain @click="batch('OFF_SHELF')">下架</el-button>
          <el-button size="small" type="danger" plain @click="batch('DELETE')">删除</el-button>
        </template>
      </div>

      <el-table :data="page.items" v-loading="loading" @selection-change="selected = $event" row-key="id" empty-text="没有商品">
        <el-table-column type="selection" width="42" />
        <el-table-column label="商品" min-width="260">
          <template #default="{ row }">
            <div class="product-cell">
              <img :src="row.imageUrls[0] || PLACEHOLDER" class="thumb" alt="">
              <div>
                <router-link :to="`/products/${row.id}`" class="name">{{ row.name }}</router-link>
                <div class="muted small mono">{{ row.sku }}<span v-if="row.parentSku"> · 款号 {{ row.parentSku }}</span></div>
                <div class="muted small" v-if="row.color || row.size">{{ [row.color, row.size].filter(Boolean).join(' / ') }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ row.category?.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="售价" width="120" align="right">
          <template #default="{ row }">
            <div class="mono">{{ money(row.price) }}</div>
            <div class="muted small mono" v-if="row.priceUsd">{{ money(row.priceUsd, 'USD') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="库存" width="80" align="right">
          <template #default="{ row }"><span class="mono" :class="{ low: row.stock <= 5 }">{{ row.stock }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="STATUS[row.status].type" size="small">{{ row.statusLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="210" :fixed="wide ? 'right' : false">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/products/${row.id}`)">编辑</el-button>
            <el-button link @click="duplicate(row)">复制</el-button>
            <el-button link v-if="row.status !== 'ON_SALE'" type="success" @click="setStatus(row, 'ON_SALE')">上架</el-button>
            <el-button link v-else type="warning" @click="setStatus(row, 'OFF_SHELF')">下架</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination layout="total, prev, pager, next" :total="page.totalItems" :page-size="20"
                       :current-page="filters.page + 1" @current-change="p => { filters.page = p - 1; load() }" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'
import { money, PLACEHOLDER, STATUS } from '../format'

const route = useRoute()
const router = useRouter()
const filters = reactive({ q: '', status: '', category: '', page: 0 })
const page = ref({ items: [], totalItems: 0 })
const categories = ref([])
const selected = ref([])
const loading = ref(false)
const wide = window.innerWidth >= 900

function parseQuery(q) {
  return {
    q: q.q || '',
    status: q.status || '',
    category: q.category ? Number(q.category) : '',
    page: Number(q.page || 0)
  }
}

async function load() {
  loading.value = true
  try {
    page.value = await api.get('/api/admin/products', { ...filters, size: 20 })
    router.replace({ query: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== '' && v !== 0)) })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function search() {
  filters.page = 0
  load()
}

async function setStatus(row, status) {
  await api.post('/api/admin/products/batch', { ids: [row.id], action: status })
  ElMessage.success(status === 'ON_SALE' ? '已上架' : '已下架')
  load()
}

async function duplicate(row) {
  const copy = await api.post(`/api/admin/products/${row.id}/duplicate`)
  ElMessage.success('已复制为草稿，修改后即可上架')
  router.push(`/products/${copy.id}`)
}

async function remove(row) {
  await ElMessageBox.confirm(`删除「${row.name}」？删除后不能恢复。`, '删除商品', { type: 'warning', confirmButtonText: '删除' })
  await api.del(`/api/admin/products/${row.id}`)
  ElMessage.success('已删除')
  load()
}

async function batch(action) {
  const ids = selected.value.map(p => p.id)
  if (action === 'DELETE') {
    await ElMessageBox.confirm(`删除选中的 ${ids.length} 个商品？删除后不能恢复。`, '批量删除', { type: 'warning', confirmButtonText: '删除' })
  }
  const r = await api.post('/api/admin/products/batch', { ids, action })
  ElMessage.success(`已处理 ${r.affected} 个商品`)
  load()
}

// 地址栏里的筛选条件变了（例如点左侧菜单“商品”、从概览带条件跳过来）就重新加载
watch(() => route.fullPath, () => {
  if (route.name !== 'products') return
  const next = parseQuery(route.query)
  if (JSON.stringify(next) !== JSON.stringify({ ...filters })) {
    Object.assign(filters, next)
    load()
  }
})

onMounted(async () => {
  Object.assign(filters, parseQuery(route.query))
  categories.value = await api.get('/api/admin/categories').catch(() => [])
  load()
})
</script>

<style scoped>
.product-cell { display: flex; gap: 12px; align-items: center; }
.name { font-weight: 500; color: #0f172a; }
.small { font-size: 12px; }
.low { color: #d97706; font-weight: 600; }
</style>
