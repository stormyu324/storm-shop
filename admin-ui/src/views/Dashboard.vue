<template>
  <div>
    <div class="page-head"><h1>概览</h1></div>
    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="s in stats" :key="s.label" :xs="12" :sm="8" :lg="4">
        <router-link :to="s.to" class="stat">
          <div class="muted">{{ s.label }}</div>
          <div class="n" :class="{ warn: s.warn }">{{ s.value }}</div>
        </router-link>
      </el-col>
    </el-row>
    <el-card>
      <h3 class="section-title">快速上架</h3>
      <div class="quick">
        <router-link to="/products/new" class="quick-item">
          <el-icon><Plus /></el-icon><div><b>添加商品</b><p class="muted">一个一个录入，可连续添加</p></div>
        </router-link>
        <router-link to="/import" class="quick-item">
          <el-icon><Upload /></el-icon><div><b>表格批量导入</b><p class="muted">Excel 填好另存 CSV，一次上架几十上百个</p></div>
        </router-link>
        <router-link to="/export" class="quick-item">
          <el-icon><Promotion /></el-icon><div><b>导出到亚马逊 / SHEIN</b><p class="muted">检查资料完整度，下载平台表格</p></div>
        </router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const data = ref(null)
const loading = ref(true)

const stats = computed(() => {
  const d = data.value || {}
  return [
    { label: '在售商品', value: d.onSale ?? '-', to: { path: '/products', query: { status: 'ON_SALE' } } },
    { label: '草稿（待上架）', value: d.drafts ?? '-', to: { path: '/products', query: { status: 'DRAFT' } } },
    { label: '已下架', value: d.offShelf ?? '-', to: { path: '/products', query: { status: 'OFF_SHELF' } } },
    { label: `库存 ≤ ${d.lowStockThreshold ?? 5} 的在售商品`, value: d.lowStock ?? '-', warn: d.lowStock > 0, to: { path: '/products', query: { status: 'ON_SALE' } } },
    { label: '待发货订单', value: d.ordersToFulfil ?? '-', warn: d.ordersToFulfil > 0, to: { path: '/orders', query: { tab: 'todo' } } },
    { label: '待付款订单', value: d.awaitingPayment ?? '-', to: { path: '/orders', query: { tab: 'PENDING_PAYMENT' } } }
  ]
})

onMounted(async () => {
  try {
    data.value = await api.get('/api/admin/dashboard')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.stat { display: block; background: #fff; border: 1px solid #e4eaf2; border-radius: 14px; padding: 16px 18px; margin-bottom: 16px; color: inherit; text-decoration: none; transition: box-shadow .15s, transform .15s; }
.stat:hover { box-shadow: 0 8px 24px rgba(15,23,42,.08); transform: translateY(-2px); }
.stat .muted { font-size: 13px; min-height: 2.6em; }
.n { font-size: 28px; font-weight: 700; margin-top: 4px; font-family: "JetBrains Mono", ui-monospace, monospace; }
.n.warn { color: #d97706; }
.quick { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.quick-item { display: flex; gap: 12px; align-items: flex-start; padding: 16px; border: 1px solid #e4eaf2; border-radius: 12px; color: inherit; text-decoration: none; }
.quick-item:hover { border-color: #93b4f5; text-decoration: none; }
.quick-item .el-icon { font-size: 22px; color: var(--el-color-primary); margin-top: 2px; }
.quick-item p { margin: 4px 0 0; font-size: 13px; }
</style>
