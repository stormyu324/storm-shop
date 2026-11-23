<template>
  <div>
    <div class="page-head"><h1>运营数据</h1></div>
    <el-card>
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
      <el-button :loading="busy" @click="load">查询</el-button>
      <p class="muted">按下单日期统计，退款按当前订单状态归集。人民币和美元分别统计，付款转化率为已付款订单数占创建订单数的比例。</p>
    </el-card>
    <el-card v-for="s in summaries" :key="s.currency" style="margin-top: 18px">
      <h2>{{ s.currency }}</h2>
      <div class="metrics">
        <el-statistic title="创建订单" :value="s.orders" />
        <el-statistic title="已付款订单" :value="s.paidOrders" />
        <el-statistic title="收款总额（含运费）" :value="Number(s.paid)" :precision="2" />
        <el-statistic title="退款总额" :value="Number(s.refunded)" :precision="2" />
        <el-statistic title="净收款" :value="Number(s.net)" :precision="2" />
        <el-statistic title="付款客单价" :value="Number(s.averageOrder)" :precision="2" />
        <el-statistic title="订单付款转化率 %" :value="Number(s.paymentConversion)" :precision="2" />
      </div>
      <h3>每日收款</h3>
      <div class="bars" aria-label="每日收款柱状图">
        <div v-for="day in s.daily" :key="day.date" class="bar-column" :title="day.date + ' · ' + money(day.paid, s.currency)">
          <span
            class="bar"
            :style="{ height: Math.max(2, (Number(day.paid) / Math.max(1, ...s.daily.map(d => Number(d.paid)))) * 100) + 'px' }"
          ></span>
          <span>{{ day.date.slice(5) }}</span>
        </div>
      </div>
      <el-table :data="s.topProducts" empty-text="暂无已付款商品">
        <el-table-column prop="title" label="热销商品（已扣除全额退款订单）" />
        <el-table-column prop="quantity" label="件数" />
      </el-table>
      <details>
        <summary>查看每日数据</summary>
        <el-table :data="s.daily">
          <el-table-column prop="date" label="日期" />
          <el-table-column prop="orders" label="订单" />
          <el-table-column prop="paid" label="收款" />
          <el-table-column prop="refunded" label="退款" />
        </el-table>
      </details>
    </el-card>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorText } from '../api'
import { money } from '../format'
const range = ref(null)
const summaries = ref([])
const busy = ref(false)
async function load() {
  busy.value = true
  try {
    summaries.value = await api.get('/api/admin/analytics', range.value ? { start: range.value[0], end: range.value[1] } : {})
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<style scoped>
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 20px; margin-bottom: 24px; }
.bars { display: flex; gap: 8px; align-items: end; overflow: auto; padding: 12px 0 20px; }
.bar-column { display: flex; flex-direction: column; align-items: center; justify-content: end; min-width: 38px; gap: 8px; font-size: 12px; }
.bar { width: 24px; background: #257256; border-radius: 4px 4px 0 0; }
</style>
