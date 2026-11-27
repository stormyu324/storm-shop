<template>
  <div>
    <div class="page-head">
      <h1>支付对账</h1>
      <div class="head-actions">
        <el-date-picker v-model="runDate" type="date" value-format="YYYY-MM-DD" placeholder="选择对账日期" />
        <el-button type="primary" :loading="running" @click="runNow">手动对账</el-button>
      </div>
    </div>

    <el-alert v-if="lastSummary" type="info" :closable="true" style="margin-bottom:16px"
              :title="`对账完成（${lastSummary.date}）：核对 ${lastSummary.checkedOrders} 单，一致 ${lastSummary.matched} 单，自动补单 ${lastSummary.autoRecovered} 单，异常 ${lastSummary.discrepancies} 单`" />

    <el-card>
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="未处理异常" name="unresolved" />
        <el-tab-pane label="全部记录" name="all" />
      </el-tabs>

      <el-table :data="page.items" v-loading="loading" row-key="id" empty-text="暂无对账异常记录">
        <el-table-column prop="runDate" label="对账日期" width="120" />
        <el-table-column label="订单号" min-width="165">
          <template #default="{ row }">
            <span class="mono">{{ row.orderNo }}</span>
            <div class="muted small">{{ dateTime(row.createdAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="160">
          <template #default="{ row }">
            <el-tag :type="issueTag(row.issueType)" size="small">{{ issueLabel(row.issueType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="240" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.resolved ? 'success' : 'danger'" size="small">{{ row.resolved ? '已处理' : '待处理' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="!row.resolved" link type="primary" @click="resolveIssue(row)">标记已处理</el-button>
            <span v-else class="muted small">{{ dateTime(row.resolvedAt) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination layout="total, prev, pager, next" :total="page.totalItems" :page-size="20"
                       :current-page="pageNo + 1" @current-change="p => { pageNo = p - 1; load() }" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'
import { dateTime } from '../format'

const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
const runDate = ref(yesterday)
const running = ref(false)
const lastSummary = ref(null)
const tab = ref('unresolved')
const pageNo = ref(0)
const page = ref({ items: [], totalItems: 0 })
const loading = ref(false)

const ISSUE_META = {
  AUTO_RECOVERED_PAID: { label: '已自动补单', type: 'success' },
  CANCELLED_BUT_PAID: { label: '已取消却已收款', type: 'danger' },
  PAID_NOT_CAPTURED_IN_STRIPE: { label: '本地已付未入账', type: 'warning' },
  STRIPE_QUERY_ERROR: { label: '查询通道失败', type: 'info' }
}
const issueLabel = t => ISSUE_META[t]?.label || t
const issueTag = t => ISSUE_META[t]?.type || 'info'

async function load() {
  loading.value = true
  try {
    page.value = await api.get('/api/admin/reconciliation/issues', {
      unresolvedOnly: tab.value === 'unresolved',
      page: pageNo.value,
      size: 20
    })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function onTab() {
  pageNo.value = 0
  load()
}

async function runNow() {
  running.value = true
  try {
    lastSummary.value = await api.post('/api/admin/reconciliation/run', undefined, { date: runDate.value })
    ElMessage.success('对账完成')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    running.value = false
  }
}

async function resolveIssue(row) {
  try {
    await ElMessageBox.confirm(`确认已在支付平台核实并处理订单 ${row.orderNo} 的异常？`, '标记已处理', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.post(`/api/admin/reconciliation/issues/${row.id}/resolve`)
    ElMessage.success('已标记处理')
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

onMounted(load)
</script>

<style scoped>
.head-actions { display: flex; gap: 10px; align-items: center; }
.small { font-size: 12px; }
</style>
