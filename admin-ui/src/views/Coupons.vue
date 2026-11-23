<template>
  <div>
    <div class="page-head">
      <h1>优惠券与促销</h1>
      <el-button type="primary" @click="open()">创建优惠券</el-button>
    </div>
    <el-card>
      <el-table :data="page.items" v-loading="busy">
        <el-table-column prop="code" label="优惠码" />
        <el-table-column prop="currency" label="币种" width="80" />
        <el-table-column label="优惠">
          <template #default="{ row }">
            {{ row.kind === 'PERCENT' ? row.value + '% 折扣' : money(row.value, row.currency) + ' 满减' }}
          </template>
        </el-table-column>
        <el-table-column label="门槛">
          <template #default="{ row }">{{ money(row.minimum, row.currency) }}</template>
        </el-table-column>
        <el-table-column label="已用 / 上限">
          <template #default="{ row }">{{ row.usedCount }} / {{ row.usageLimit }}</template>
        </el-table-column>
        <el-table-column label="有效期" min-width="180">
          <template #default="{ row }">{{ dateTime(row.startsAt) }} 至 {{ dateTime(row.endsAt) }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }"><el-button link @click="open(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination
        layout="prev,pager,next"
        :total="page.totalItems"
        :page-size="20"
        @current-change="p => { pageNo = p - 1; load() }"
      />
    </el-card>
    <el-dialog v-model="visible" title="优惠券设置" width="min(580px,94vw)">
      <el-form label-position="top">
        <el-form-item label="优惠码"><el-input v-model="form.code" maxlength="40" :disabled="Boolean(form.id)" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kind" :disabled="Boolean(form.id)">
            <el-option value="FIXED" label="满减金额" />
            <el-option value="PERCENT" label="折扣百分比（20 表示减免 20%）" />
          </el-select>
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="form.currency" :disabled="Boolean(form.id)">
            <el-option value="CNY" />
            <el-option value="USD" />
          </el-select>
        </el-form-item>
        <el-form-item label="减免金额或百分比">
          <el-input-number v-model="form.value" :min="0.01" :precision="2" :disabled="Boolean(form.id)" />
        </el-form-item>
        <el-form-item label="商品金额门槛"><el-input-number v-model="form.minimum" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="使用上限"><el-input-number v-model="form.usageLimit" :min="1" /></el-form-item>
        <el-switch v-model="form.enabled" active-text="启用" />
      </el-form>
      <template #footer><el-button :loading="busy" type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorText } from '../api'
import { money, dateTime } from '../format'
const page = ref({ items: [], totalItems: 0 })
const pageNo = ref(0)
const busy = ref(false)
const visible = ref(false)
const form = ref({})
async function load() {
  try {
    page.value = await api.get('/api/admin/coupons', { page: pageNo.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
function open(row) {
  form.value = row
    ? { ...row }
    : {
        code: '',
        kind: 'FIXED',
        currency: 'CNY',
        value: 10,
        minimum: 100,
        startsAt: new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 19),
        endsAt: '',
        usageLimit: 100,
        enabled: true
      }
  visible.value = true
}
async function save() {
  busy.value = true
  try {
    await (form.value.id ? api.put('/api/admin/coupons/' + form.value.id, form.value) : api.post('/api/admin/coupons', form.value))
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
