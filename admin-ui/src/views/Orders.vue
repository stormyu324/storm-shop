<template>
  <div>
    <div class="page-head">
      <h1>订单</h1>
      <div class="head-actions">
        <el-button @click="exportFulfillment">导出待发货 CSV</el-button>
        <el-button type="primary" @click="openBulkShip">批量导入发货</el-button>
      </div>
    </div>
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

    <el-dialog v-model="bulkOpen" title="批量导入物流发货" width="560px">
      <p class="muted" style="margin-top:0">
        上传 CSV 文件批量回填承运商、运单号并标记已发货（自动发送发货邮件）。
        支持直接上传「导出待发货 CSV」填完最后三列后的文件，或先
        <a href="/api/admin/orders/fulfillment/template" target="_blank">下载模板</a>。
      </p>
      <div class="bulk-upload">
        <input ref="fileInput" type="file" accept=".csv,text/csv" @change="onFileChange" />
        <el-button type="primary" :loading="importing" :disabled="!bulkFile" @click="submitBulkShip">开始导入</el-button>
      </div>
      <div v-if="bulkResult" class="bulk-result">
        <el-alert :type="bulkResult.failed > 0 ? 'warning' : 'success'" :closable="false"
                  :title="`共处理 ${bulkResult.total} 条：成功 ${bulkResult.succeeded} 条，失败 ${bulkResult.failed} 条`" />
        <el-table v-if="bulkResult.errors && bulkResult.errors.length" :data="bulkResult.errors" size="small" max-height="240" style="margin-top:12px">
          <el-table-column prop="row" label="行号" width="70" />
          <el-table-column prop="orderNo" label="订单号" width="160" />
          <el-table-column prop="message" label="失败原因" />
        </el-table>
      </div>
    </el-dialog>
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
const bulkOpen = ref(false)
const bulkFile = ref(null)
const importing = ref(false)
const bulkResult = ref(null)
const fileInput = ref(null)

function exportFulfillment() {
  window.location.href = '/api/admin/orders/fulfillment/export?toFulfil=true'
}

function openBulkShip() {
  bulkResult.value = null
  bulkFile.value = null
  if (fileInput.value) fileInput.value.value = ''
  bulkOpen.value = true
}

function onFileChange(e) {
  bulkFile.value = e.target.files?.[0] || null
}

async function submitBulkShip() {
  if (!bulkFile.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', bulkFile.value)
    bulkResult.value = await api.upload('/api/admin/orders/fulfillment/import', fd)
    if (bulkResult.value.succeeded > 0) {
      ElMessage.success(`已批量发货 ${bulkResult.value.succeeded} 单`)
      await load()
    }
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    importing.value = false
  }
}

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
.head-actions { display: flex; gap: 10px; }
.bulk-upload { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 14px 0; }
.bulk-result { margin-top: 14px; }
.detail { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; padding: 4px 16px; }
.detail p { margin: 6px 0 0; }
</style>
