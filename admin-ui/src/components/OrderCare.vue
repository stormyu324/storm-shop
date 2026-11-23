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
      <p>整单全额退款。在线支付审批通过后由系统退款，线下支付需核实退款凭据。已发货商品请验收退货后再调整库存。</p>
      <el-empty v-if="!cases.length" description="暂无售后申请" :image-size="50" />
      <el-card v-for="c in cases" :key="c.id" style="margin-bottom: 12px">
        <p>{{ labels[c.state] || c.state }} · {{ dateTime(c.createdAt) }}</p>
        <p>{{ c.reason }}</p>
        <ul>
          <li v-for="(h, index) in c.history" :key="index">{{ dateTime(h.createdAt) }} · {{ labels[h.state] || h.state }} {{ h.note }}</li>
        </ul>
        <p v-if="c.decisionNote">{{ c.decisionNote }}</p>
        <el-button v-if="can('ADMIN') && ['REQUESTED', 'FAILED'].includes(c.state)" :disabled="busy" @click="decide(c, 'APPROVE')">
          批准全额退款
        </el-button>
        <el-button v-if="can('ADMIN') && c.state === 'REQUESTED'" :disabled="busy" @click="decide(c, 'REJECT')">驳回</el-button>
        <el-button v-if="can('ADMIN') && c.state === 'MANUAL_REQUIRED'" :disabled="busy" @click="decide(c, 'CONFIRM_MANUAL')">
          确认线下退款已完成
        </el-button>
      </el-card>
      <el-button :disabled="busy" @click="createCase">代顾客登记售后</el-button>
      <el-button :disabled="busy" @click="execute(reload)">刷新进度</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { ref } from 'vue'
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
async function decide(c, action) {
  await execute(async () => {
    let note = ''
    if (action === 'APPROVE') {
      await ElMessageBox.confirm('批准后将按订单总额退款。请先核实支付平台状态和退货情况，确认批准？', '审核退款', { type: 'warning' })
    } else {
      note = await prompt(action === 'REJECT' ? '请填写驳回原因' : '请填写已完成的线下退款凭据或参考号')
    }
    await api.post(base() + '/after-sales/' + c.id, { action, note })
  })
}
</script>
