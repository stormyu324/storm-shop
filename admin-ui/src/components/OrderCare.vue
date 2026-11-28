<template>
  <el-dialog v-model="visible" title="物流与售后" width="min(720px, 94vw)">
    <template v-if="order">
      <p>{{ order.orderNo }} · {{ order.statusLabel }}</p>
      <p>通知邮箱 {{ order.email || '下单时未填写' }}</p>
      <el-form label-position="top">
        <el-form-item label="承运商"><el-input v-model="shipping.carrier" maxlength="100" /></el-form-item>
        <el-form-item label="运单号"><el-input v-model="shipping.trackingNumber" maxlength="100" /></el-form-item>
        <el-form-item label="查询链接（HTTPS，可选）"><el-input v-model="shipping.trackingUrl" maxlength="254" /></el-form-item>
        <el-button :loading="busy" :disabled="!['PLACED', 'PAID', 'SHIPPED'].includes(order.status)" @click="ship">
          保存物流并发货
        </el-button>
      </el-form>
      <el-divider />
      <h3>售后申请</h3>
      <p>
        订单金额 {{ order.currency }} {{ order.total }}，已退 {{ order.refundedAmount || 0 }}，还可退 {{ order.refundableAmount }}。
        可以退全部或部分金额；已发货的可以要求顾客先寄回，收到后再退款并选择要重新入库的商品。
        在线支付由系统原路退款，线下支付需填写退款凭据。
      </p>
      <el-empty v-if="!cases.length" description="暂无售后申请" :image-size="50" />
      <el-card v-for="c in cases" :key="c.id" style="margin-bottom: 12px">
        <p>
          {{ labels[c.state] || c.state }} · {{ dateTime(c.createdAt) }}
          <template v-if="c.refundAmount"> · 退款 {{ order.currency }} {{ c.refundAmount }}</template>
          <template v-if="c.returnRequired"> · 需寄回商品</template>
        </p>
        <p v-if="c.returnTracking">退货物流：{{ c.returnCarrier }} {{ c.returnTracking }}</p>
        <p>{{ c.reason }}</p>
        <ul>
          <li v-for="(h, index) in c.history" :key="index">{{ dateTime(h.createdAt) }} · {{ labels[h.state] || h.state }} {{ h.note }}</li>
        </ul>
        <p v-if="c.decisionNote">{{ c.decisionNote }}</p>
        <el-button v-if="can('ADMIN') && ['REQUESTED', 'FAILED'].includes(c.state)" :disabled="busy" @click="openForm(c, 'APPROVE')">
          批准退款
        </el-button>
        <el-button v-if="can('ADMIN') && ['AWAITING_RETURN', 'RETURN_SHIPPED'].includes(c.state)" type="primary" :disabled="busy"
          @click="openForm(c, 'RECEIVE')">
          确认收到退货
        </el-button>
        <el-button v-if="can('ADMIN') && ['REQUESTED', 'AWAITING_RETURN', 'RETURN_SHIPPED'].includes(c.state)" :disabled="busy"
          @click="decide(c, 'REJECT')">驳回</el-button>
        <el-button v-if="can('ADMIN') && c.state === 'MANUAL_REQUIRED'" :disabled="busy" @click="decide(c, 'CONFIRM_MANUAL')">
          确认线下退款已完成
        </el-button>
      </el-card>
      <el-button :disabled="busy" @click="createCase">代顾客登记售后</el-button>
      <el-button :disabled="busy" @click="execute(reload)">刷新进度</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="form.visible" :title="form.action === 'APPROVE' ? '批准退款' : '确认收到退货'" width="min(560px, 94vw)">
    <el-form v-if="order" label-position="top">
      <template v-if="form.action === 'APPROVE'">
        <el-form-item :label="'退款金额（最多 ' + order.refundableAmount + '）'">
          <el-input-number v-model="form.amount" :min="0.01" :max="Number(order.refundableAmount)" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item v-if="order.shippedAt">
          <el-checkbox v-model="form.requireReturn">要求顾客先寄回商品，收到后再退款</el-checkbox>
        </el-form-item>
      </template>
      <el-form-item v-if="showRestock" label="重新入库的商品（没退回或不能再卖的填 0）">
        <div v-for="item in order.items" :key="item.id" style="display: flex; gap: 12px; align-items: center; width: 100%">
          <span style="flex: 1">{{ item.productName }}（买了 {{ item.quantity }}，已入库 {{ item.returnedQuantity || 0 }}）</span>
          <el-input-number v-model="form.restock[item.id]" :min="0" :max="item.quantity - (item.returnedQuantity || 0)" size="small" />
        </div>
      </el-form-item>
      <el-form-item label="备注（会发给顾客）">
        <el-input v-model="form.note" type="textarea" maxlength="1000" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="form.visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submitForm">确定</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'
import { can } from '../auth'
import { dateTime } from '../format'
const emit = defineEmits(['changed'])
const visible = ref(false)
const busy = ref(false)
const order = ref(null)
const cases = ref([])
const shipping = ref({ carrier: '', trackingNumber: '', trackingUrl: '' })
const labels = {
  REQUESTED: '待审核',
  AWAITING_RETURN: '等待顾客寄回',
  RETURN_SHIPPED: '顾客已寄出退货',
  PROCESSING: '退款处理中',
  MANUAL_REQUIRED: '等待线下退款',
  SUCCEEDED: '退款完成',
  REJECTED: '已驳回',
  FAILED: '处理异常，等待核查'
}
const base = () => '/api/admin/orders/' + order.value.id
async function reload() {
  order.value = await api.get(base())
  cases.value = await api.get(base() + '/after-sales')
}
async function open(row) {
  order.value = row
  cases.value = []
  shipping.value = { carrier: row.carrier || '', trackingNumber: row.trackingNumber || '', trackingUrl: row.trackingUrl || '' }
  visible.value = true
  try {
    await reload()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
defineExpose({ open })
async function execute(action) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
    await reload()
    emit('changed')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errorText(e))
  } finally {
    busy.value = false
  }
}
async function ship() {
  if (!shipping.value.carrier.trim() || !shipping.value.trackingNumber.trim()) {
    ElMessage.warning('请填写承运商和运单号')
    return
  }
  await execute(() => api.post(base() + '/shipping', shipping.value))
}
async function prompt(message) {
  const result = await ElMessageBox.prompt(message, '售后处理', {
    inputValidator: value => Boolean(value && value.trim() && value.length <= 1000) || '请填写 1 至 1000 字'
  })
  return result.value
}
async function createCase() {
  await execute(async () => {
    const reason = await prompt('请填写顾客的售后原因')
    await api.post(base() + '/after-sales', { reason })
  })
}
const form = ref({ visible: false, action: '', caseId: null, amount: 0, requireReturn: false, restock: {}, note: '' })
// 退回入库：确认收到退货时；或没发货、不需寄回的部分退款（全额退款且没发货时系统会自动把剩余商品放回库存）
const showRestock = computed(() =>
  form.value.action === 'RECEIVE' || (form.value.action === 'APPROVE' && !form.value.requireReturn && !order.value?.shippedAt)
)
function openForm(c, action) {
  const restock = {}
  order.value.items.forEach(i => { restock[i.id] = 0 })
  form.value = {
    visible: true,
    action,
    caseId: c.id,
    amount: Number(c.refundAmount || order.value.refundableAmount),
    requireReturn: false,
    restock,
    note: ''
  }
}
async function submitForm() {
  const f = form.value
  const restock = Object.entries(f.restock)
    .filter(([, qty]) => qty > 0)
    .map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity }))
  const body = { action: f.action, note: f.note, restock: showRestock.value ? restock : [] }
  if (f.action === 'APPROVE') {
    body.amount = f.amount
    body.requireReturn = f.requireReturn
  }
  await execute(async () => {
    await api.post(base() + '/after-sales/' + f.caseId, body)
    form.value.visible = false
  })
}
async function decide(c, action) {
  await execute(async () => {
    let note = ''
    note = await prompt(action === 'REJECT' ? '请填写驳回原因' : '请填写已完成的线下退款凭据或参考号')
    await api.post(base() + '/after-sales/' + c.id, { action, note })
  })
}
</script>
