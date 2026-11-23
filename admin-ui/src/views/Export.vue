<template>
  <div>
    <div class="page-head"><h1>导出到亚马逊 / SHEIN</h1></div>
    <p class="muted">在本站维护一份商品资料，导出成表格后到平台卖家后台批量上传，不用在每个平台重复录入。</p>
    <div class="toolbar">
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option v-for="(s, k) in STATUS" :key="k" :label="s.label" :value="k" />
      </el-select>
      <el-select v-model="category" placeholder="全部分类" clearable style="width: 150px" @change="load">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
    </div>

    <el-alert v-if="r?.imageBaseUrlIsLocal" type="error" :closable="false" show-icon class="mb"
              :title="`当前图片链接以 ${r.imageBaseUrl} 开头，平台无法访问本机地址。网站部署上线后请设置环境变量 PUBLIC_BASE_URL（例如 https://www.myshop.com）再导出，或在商品里直接使用网络图片地址。`" />

    <el-card v-loading="loading">
      <h3 class="section-title">资料完整度 <span class="muted" v-if="r">共 {{ r.total }} 个商品</span></h3>
      <div class="readiness" v-if="r">
        <div v-for="item in items" :key="item.label" class="item" :class="item.warn ? 'warn' : 'ok'">
          <div class="muted">{{ item.label }}</div>
          <div class="n">{{ item.value }}</div>
        </div>
      </div>
      <div v-if="r && r.missingTitleEn > 0" class="ai-bar">
        <el-button type="primary" :disabled="!r.aiEnabled" :loading="filling" @click="fill">✨ 用 AI 补全缺少的英文资料</el-button>
        <span class="muted small">{{ r.aiEnabled ? '为缺英文标题的商品生成英文标题、5 条卖点和描述，只填空着的内容；每次最多 20 个' : '需要先设置环境变量 ANTHROPIC_API_KEY 才能使用' }}</span>
      </div>
      <template v-if="r && r.incomplete.length">
        <p class="muted">以下商品缺少关键资料（最多显示 30 个）：</p>
        <el-table :data="r.incomplete" size="small">
          <el-table-column label="商品"><template #default="{ row }">{{ row.name }} <span class="muted mono">{{ row.sku }}</span></template></el-table-column>
          <el-table-column label="缺少" prop="missing" />
          <el-table-column width="80"><template #default="{ row }"><router-link :to="`/products/${row.id}`">补充</router-link></template></el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card>
      <h3 class="section-title">下载表格</h3>
      <div class="export-option">
        <div><b>亚马逊格式</b><p class="muted">列名按亚马逊库存文件（flat file）的字段名，价格为美元；填了“款号”的商品会自动生成父商品行和变体关系。</p></div>
        <a :href="`/admin/export/amazon.csv${qs}`"><el-button type="primary">下载亚马逊表格</el-button></a>
      </div>
      <div class="export-option">
        <div><b>通用资料表（SHEIN 等平台）</b><p class="muted">包含全部中英文资料和完整图片链接；列和“批量导入”一致，改完可以直接导回本站。</p></div>
        <a :href="`/admin/export/products.csv${qs}`"><el-button>下载通用资料表</el-button></a>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { api, errorText } from '../api'
import { STATUS } from '../format'

const status = ref('ON_SALE')
const category = ref('')
const categories = ref([])
const r = ref(null)
const loading = ref(false)
const filling = ref(false)

// 下载接口里 status 缺省表示“上架”，所以“全部状态”要显式传空值
const qs = computed(() => `?status=${encodeURIComponent(status.value || '')}` + (category.value ? `&category=${category.value}` : ''))

const items = computed(() => r.value ? [
  { label: '缺英文标题', value: r.value.missingTitleEn, warn: r.value.missingTitleEn > 0 },
  { label: '缺品牌', value: r.value.missingBrand, warn: r.value.missingBrand > 0 },
  { label: '缺条码 UPC/EAN', value: r.value.missingBarcode, warn: r.value.missingBarcode > 0 },
  { label: '缺图片', value: r.value.missingImage, warn: r.value.missingImage > 0 },
  { label: '缺重量', value: r.value.missingWeight, warn: r.value.missingWeight > 0 },
  { label: '美元价按汇率换算', value: r.value.autoUsdPrice, warn: false }
] : [])

async function load() {
  loading.value = true
  try {
    r.value = await api.get('/api/admin/export/readiness', { status: status.value, category: category.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function fill() {
  filling.value = true
  try {
    const res = await api.post('/api/admin/ai/english-fill', undefined, { status: status.value, category: category.value })
    ElNotification({
      type: res.failures.length ? 'warning' : 'success',
      title: `已为 ${res.filled} 个商品生成英文资料`,
      message: [res.remaining ? `还有 ${res.remaining} 个，可以再点一次继续。` : '', res.failures.slice(0, 3).join('；'), '建议抽查几个，确认无误后再导出。'].filter(Boolean).join(' '),
      duration: 8000
    })
    load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    filling.value = false
  }
}

onMounted(async () => {
  categories.value = await api.get('/api/admin/categories').catch(() => [])
  load()
})
</script>

<style scoped>
.mb { margin-bottom: 16px; }
.small { font-size: 13px; }
.readiness { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
.item { border: 1px solid #e4eaf2; border-radius: 12px; padding: 12px 14px; }
.item .muted { font-size: 13px; }
.item .n { font-size: 24px; font-weight: 700; font-family: "JetBrains Mono", ui-monospace, monospace; }
.item.warn .n { color: #d97706; }
.item.ok .n { color: #059669; }
.ai-bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.export-option { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px solid #eef2f8; }
.export-option:last-child { border-bottom: none; }
.export-option p { margin: 4px 0 0; font-size: 13px; }
</style>
