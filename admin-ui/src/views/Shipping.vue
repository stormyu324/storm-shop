<template>
  <div>
    <div class="page-head"><h1>运费</h1></div>
    <p class="muted intro">中文前台按人民币、英文前台按美元结算，两边分别设置。顾客结账时选择寄往的国家 / 地区：在某个分组里的国家用该分组的运费，其他国家用“默认运费”。
      运费 = 首重运费 + 超出首重后每公斤加价（不足 1 公斤按 1 公斤）；商品金额达到包邮门槛时免运费。重量取商品资料里的“重量（克）”，没填的按 0 克。</p>

    <el-card v-for="sec in sections" :key="sec.currency" v-loading="loading">
      <h3 class="section-title">{{ sec.currency === 'USD' ? '美元（英文前台）' : '人民币（中文前台）' }}</h3>

      <div class="sub-head">
        <b>按国家 / 地区单独设置</b>
        <el-button size="small" type="primary" plain @click="openZone(sec.currency)"><el-icon><Plus /></el-icon>&nbsp;添加国家分组</el-button>
      </div>
      <el-table v-if="sec.zones.length" :data="sec.zones" size="small">
        <el-table-column label="分组" min-width="200">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <div class="muted small">{{ row.countries.map(countryName).join('、') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="首重运费" width="110" align="right"><template #default="{ row }">{{ money(row.baseFee, sec.currency) }}</template></el-table-column>
        <el-table-column label="首重" width="90" align="right"><template #default="{ row }">{{ row.includedGrams }} 克</template></el-table-column>
        <el-table-column label="续重 / 公斤" width="110" align="right"><template #default="{ row }">{{ row.extraPerKg != null ? money(row.extraPerKg, sec.currency) : '不加价' }}</template></el-table-column>
        <el-table-column label="包邮门槛" width="110" align="right"><template #default="{ row }">{{ row.freeThreshold != null ? '满 ' + money(row.freeThreshold, sec.currency) : '不包邮' }}</template></el-table-column>
        <el-table-column width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="openZone(sec.currency, row)">修改</el-button>
            <el-button link type="danger" @click="removeZone(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-else class="muted small">还没有分组，所有国家都用下面的默认运费。</p>

      <div class="sub-head" style="margin-top: 22px"><b>默认运费（其他国家 / 地区）</b></div>
      <el-form label-position="top" class="default-form">
        <div class="checks">
          <el-checkbox v-model="sec.defaultRule.zonesOnly" :disabled="!sec.zones.length">只配送到上面列出的国家（其他国家不能下单）</el-checkbox>
          <el-checkbox v-model="sec.defaultRule.enabled">收取运费（不勾选则其他国家免运费）</el-checkbox>
        </div>
        <fees-fields :fees="sec.defaultRule" :currency="sec.currency" />
        <el-button type="primary" @click="saveDefault(sec)">保存默认运费</el-button>
      </el-form>
    </el-card>

    <el-dialog v-model="zoneDialog" :title="zone.id ? '修改国家分组' : '添加国家分组'" width="680px">
      <el-form label-position="top">
        <el-form-item label="分组名称" required><el-input v-model="zone.name" maxlength="50" placeholder="例如：美国、加拿大" /></el-form-item>
        <el-form-item label="国家 / 地区" required>
          <el-select v-model="zone.countries" multiple filterable placeholder="输入国家名称或代码搜索" style="width: 100%">
            <el-option v-for="c in countryList" :key="c.code" :label="`${c.name} ${c.code}`" :value="c.code" />
          </el-select>
        </el-form-item>
        <fees-fields :fees="zone" :currency="zone.currency" />
      </el-form>
      <template #footer>
        <el-button @click="zoneDialog = false">取消</el-button>
        <el-button type="primary" @click="saveZone">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { defineComponent, h, onMounted, reactive, ref } from 'vue'
import { ElCol, ElFormItem, ElInputNumber, ElMessage, ElMessageBox, ElRow } from 'element-plus'
import { api, errorText } from '../api'
import { money } from '../format'

// 一组运费参数的输入框（默认运费和分组共用）
const FeesFields = defineComponent({
  props: { fees: Object, currency: String },
  setup(props) {
    const sym = () => (props.currency === 'USD' ? '$' : '¥')
    const field = (label, key, extra) => h(ElCol, { sm: 6, xs: 12 }, () => h(ElFormItem, { label }, () => h(ElInputNumber, {
      modelValue: props.fees[key] ?? undefined,
      'onUpdate:modelValue': v => { props.fees[key] = v ?? null },
      min: 0, controls: false, style: 'width: 100%', ...extra
    })))
    return () => h(ElRow, { gutter: 12 }, () => [
      field(`首重运费（${sym()}）`, 'baseFee', { precision: 2 }),
      field('首重包含重量（克）', 'includedGrams', { precision: 0 }),
      field(`续重每公斤（${sym()}）`, 'extraPerKg', { precision: 2, placeholder: '不加价' }),
      field(`包邮门槛（${sym()}）`, 'freeThreshold', { precision: 2, placeholder: '不包邮' })
    ])
  }
})

const sections = ref([])
const loading = ref(false)
const countryList = ref([])
const countryNames = ref({})
const zoneDialog = ref(false)
const zone = reactive({})

const countryName = c => countryNames.value[c] || c

async function load() {
  loading.value = true
  try {
    sections.value = await api.get('/api/admin/shipping')
  } finally {
    loading.value = false
  }
}

async function saveDefault(sec) {
  try {
    const r = sec.defaultRule
    await api.put(`/api/admin/shipping/${sec.currency}/default`, {
      enabled: r.enabled, zonesOnly: r.zonesOnly, baseFee: r.baseFee ?? 0, includedGrams: r.includedGrams ?? 1000,
      extraPerKg: r.extraPerKg ?? null, freeThreshold: r.freeThreshold ?? null
    })
    ElMessage.success('默认运费已保存')
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

function openZone(currency, row) {
  Object.keys(zone).forEach(k => delete zone[k])
  Object.assign(zone, row
    ? { ...row, countries: [...row.countries], currency }
    : { id: null, name: '', countries: [], baseFee: 0, includedGrams: 1000, extraPerKg: null, freeThreshold: null, currency })
  zoneDialog.value = true
}

async function saveZone() {
  if (!zone.name?.trim()) return ElMessage.warning('请填写分组名称')
  if (!zone.countries.length) return ElMessage.warning('请至少选择一个国家 / 地区')
  const body = {
    name: zone.name, countries: zone.countries, baseFee: zone.baseFee ?? 0, includedGrams: zone.includedGrams ?? 1000,
    extraPerKg: zone.extraPerKg ?? null, freeThreshold: zone.freeThreshold ?? null
  }
  try {
    if (zone.id) await api.put(`/api/admin/shipping/${zone.currency}/zones/${zone.id}`, body)
    else await api.post(`/api/admin/shipping/${zone.currency}/zones`, body)
    ElMessage.success(`分组「${zone.name}」已保存`)
    zoneDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function removeZone(row) {
  await ElMessageBox.confirm(`删除分组「${row.name}」？其中的国家会改用默认运费。`, '删除分组', { type: 'warning', confirmButtonText: '删除' })
  await api.del(`/api/admin/shipping/zones/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  load()
  countryList.value = await api.get('/api/countries').catch(() => [])
  countryNames.value = Object.fromEntries(countryList.value.map(c => [c.code, c.name]))
})
</script>

<style scoped>
.intro { margin-top: -6px; line-height: 1.7; }
.small { font-size: 12px; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.checks { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
</style>
