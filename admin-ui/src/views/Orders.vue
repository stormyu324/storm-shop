<template>
  <div>
    <div class="page-head"><h1>订单</h1></div>
    <el-card>
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="待发货" name="todo" />
        <el-tab-pane v-for="(s, k) in ORDER_STATUS" :key="k" :label="s.label" :name="k" />
      </el-tabs>
      <el-table :data="page.items" v-loading="loading" row-key="id" empty-text="没有订单">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <div><b>收货信息</b>
                <p>{{ row.customerName }}　{{ row.phone }}</p>
                <p>{{ countryName(row.country) }} {{ row.address }}</p>
                <p v-if="row.note" class="muted">备注：{{ row.note }}</p>
              </div>
              <div><b>商品</b>
                <p v-for="i in row.items" :key="i.productId + i.sku">
                  {{ i.productName }} <span class="muted mono">{{ i.sku }}</span> × {{ i.quantity }}　{{ money(i.subtotal, row.currency) }}
                </p>
                <p class="muted">商品 {{ money(row.itemsTotal, row.currency) }} + 运费 {{ money(row.shippingFee, row.currency) }}</p>
                <p v-if="Number(row.discountAmount)>0">优惠 {{ row.couponCode }} 减 {{ money(row.discountAmount,row.currency) }}</p>
              </div>
              <div><b>时间</b>
                <p>下单：{{ dateTime(row.createdAt) }}</p>
                <p v-if="row.paidAt">付款：{{ dateTime(row.paidAt) }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="订单号" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span><div class="muted small">{{ dateTime(row.createdAt) }}</div></template>
        </el-table-column>
        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">
            <div v-for="i in row.items" :key="i.productId + i.sku" class="small">{{ i.productName }} × {{ i.quantity }}</div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <div class="mono">{{ money(row.total, row.currency) }}</div>
            <div class="muted small" v-if="Number(row.shippingFee) > 0">含运费 {{ money(row.shippingFee, row.currency) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="收货人" min-width="150">
          <template #default="{ row }">{{ row.customerName }}<div class="muted small">{{ countryName(row.country) }}</div></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="ORDER_STATUS[row.status].type" size="small">{{ row.statusLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="170" :fixed="wide ? 'right' : false">
          <template #default="{ row }">
            <el-button v-for="s in row.nextStatuses" :key="s" link :type="s === 'CANCELLED' ? 'danger' : 'primary'" @click="change(row, s)">
              {{ actionLabel(s) }}
            </el-button>
            <el-button link type="primary" @click="care.open(row)">物流与售后</el-button>
            <el-button v-if="row.canConfirmOfflinePayment && can('ADMIN')" link type="success" @click="confirmReceipt(row)">确认收款</el-button>
            <span v-if="!row.nextStatuses.length && !(row.canConfirmOfflinePayment && can('ADMIN'))" class="muted small">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination layout="total, prev, pager, next" :total="page.totalItems" :page-size="20"
                       :current-page="pageNo + 1" @current-change="p => { pageNo = p - 1; load() }" />
      </div>
    </el-card>
    <OrderCare ref="care" @changed="load" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import OrderCare from '../components/OrderCare.vue'
const care = ref(null)
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'
import { can } from '../auth'
import { dateTime, money, ORDER_STATUS } from '../format'

const route = useRoute()
const router = useRouter()
const tab = ref(route.query.tab || 'all')
const pageNo = ref(0)
const page = ref({ items: [], totalItems: 0 })
const loading = ref(false)
const wide = window.innerWidth >= 900
const countries = ref({})

const ACTIONS = { SHIPPED: '标记发货', COMPLETED: '标记完成', CANCELLED: '取消订单' }
const actionLabel = s => ACTIONS[s] || ORDER_STATUS[s].label
const countryName = c => (c ? countries.value[c] || c : '')

async function load() {
  loading.value = true
  try {
    const params = { page: pageNo.value, size: 20 }
    if (tab.value === 'todo') params.toFulfil = true
    else if (tab.value !== 'all') params.status = tab.value
    page.value = await api.get('/api/admin/orders', params)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function onTab() {
  pageNo.value = 0
  router.replace({ query: tab.value === 'all' ? {} : { tab: tab.value } })
  load()
}

async function confirmReceipt(row) {
  try {
    await ElMessageBox.confirm('请核对订单金额和实际到账记录。确认已收到全额付款？', '确认线下收款', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.post('/api/admin/orders/' + row.id + '/offline-payment')
    ElMessage.success('已记录收款')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function change(row, status) {
  if (status === 'SHIPPED') { care.value.open(row); return }
  if (status === 'CANCELLED') {
    await ElMessageBox.confirm(
      row.status === 'PAID' || row.status === 'SHIPPED'
        ? '取消订单会把库存加回去。已付款的订单请先在 Stripe 后台退款。确定取消吗？'
        : '取消订单会把库存加回去，确定吗？', '取消订单', { type: 'warning', confirmButtonText: '取消订单', cancelButtonText: '再想想' })
  }
  try {
    await api.post(`/api/admin/orders/${row.id}/status`, { status })
    ElMessage.success(`订单状态已更新为「${ORDER_STATUS[status].label}」`)
  } catch (e) {
    ElMessage.error(errorText(e))
  }
  load()
}

onMounted(async () => {
  load()
  const list = await api.get('/api/countries').catch(() => [])
  countries.value = Object.fromEntries(list.map(c => [c.code, c.name]))
})
</script>

<style scoped>
.small { font-size: 12px; }
.detail { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; padding: 4px 16px; }
.detail p { margin: 6px 0 0; }
</style>
