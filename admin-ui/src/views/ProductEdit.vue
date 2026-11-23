<template>
  <div v-loading="loading">
    <div class="page-head">
      <h1>{{ isNew ? '添加商品' : '编辑商品' }}</h1>
      <router-link to="/products">← 返回商品列表</router-link>
    </div>

    <el-alert v-if="conflict" type="warning" show-icon :closable="false" class="mb"
              title="这个商品在你编辑期间被修改过（可能有新订单扣减了库存）。为避免覆盖，请重新加载后再修改。">
      <el-button size="small" @click="reload">重新加载最新数据</el-button>
    </el-alert>

    <el-form :model="form" label-position="top" @submit.prevent>
      <el-card>
        <h3 class="section-title">基本信息</h3>
        <el-form-item label="商品名称" required :error="errors.name">
          <el-input v-model="form.name" maxlength="200" show-word-limit placeholder="例如：纯棉圆领T恤 白色 M码" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :sm="8">
            <el-form-item label="分类" :error="errors.categoryId">
              <el-select v-model="form.categoryId" clearable placeholder="不分类" style="width: 100%">
                <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :sm="8">
            <el-form-item label="SKU（商品编码）" :error="errors.sku">
              <el-input v-model="form.sku" maxlength="64" placeholder="留空自动生成" />
            </el-form-item>
          </el-col>
          <el-col :sm="8">
            <el-form-item label="库存" :error="errors.stock">
              <el-input-number v-model="form.stock" :min="0" :step="1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :sm="8">
            <el-form-item label="售价（¥）" required :error="errors.price">
              <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :sm="8">
            <el-form-item label="原价 / 划线价（¥）" :error="errors.originalPrice">
              <el-input-number v-model="form.originalPrice" :min="0" :precision="2" :controls="false" placeholder="可不填" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :sm="8">
            <el-form-item label="保存后的状态">
              <el-radio-group v-model="form.status">
                <el-radio-button value="ON_SALE">上架</el-radio-button>
                <el-radio-button value="DRAFT">草稿</el-radio-button>
                <el-radio-button value="OFF_SHELF">下架</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="商品描述" :error="errors.description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="10000" />
        </el-form-item>
      </el-card>

      <el-card>
        <h3 class="section-title">图片 <span class="muted hint">第一张为主图，可拖入多张；用箭头调整顺序</span></h3>
        <div class="images">
          <div v-for="(url, i) in form.imageUrls" :key="url + i" class="image">
            <img :src="url" alt="">
            <span v-if="i === 0" class="main-tag">主图</span>
            <div class="image-actions">
              <el-button size="small" circle :disabled="i === 0" @click="moveImage(i, -1)"><el-icon><ArrowLeft /></el-icon></el-button>
              <el-button size="small" circle :disabled="i === form.imageUrls.length - 1" @click="moveImage(i, 1)"><el-icon><ArrowRight /></el-icon></el-button>
              <el-button size="small" circle type="danger" @click="form.imageUrls.splice(i, 1)"><el-icon><Delete /></el-icon></el-button>
            </div>
          </div>
          <el-upload class="uploader" drag multiple accept="image/jpeg,image/png,image/gif,image/webp"
                     :show-file-list="false" :http-request="uploadImage">
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="muted">拖入或点击上传</div>
          </el-upload>
        </div>
        <div class="toolbar" style="margin-top: 12px; margin-bottom: 0">
          <el-input v-model="newUrl" placeholder="或者粘贴网络图片地址 https://..." style="max-width: 420px" @keyup.enter="addUrl" />
          <el-button @click="addUrl">添加</el-button>
          <span v-if="uploading" class="muted">正在上传 {{ uploading }} 张…</span>
        </div>
      </el-card>

      <el-card>
        <el-collapse v-model="openSections">
          <el-collapse-item name="cross">
            <template #title>
              <span class="collapse-title">
                <span class="section-title" style="margin: 0">跨境 / 平台资料</span>
                <span class="muted hint">英文前台、导出亚马逊 / SHEIN 时使用，可选填</span>
              </span>
            </template>
            <div class="ai-bar">
              <el-button :disabled="!aiEnabled" :loading="aiLoading" @click="generateEnglish">✨ AI 生成英文标题、卖点和描述</el-button>
              <span class="muted hint">{{ aiHint }}</span>
            </div>
            <el-row :gutter="16">
              <el-col :sm="16">
                <el-form-item label="英文标题" :error="errors.titleEn">
                  <el-input v-model="form.titleEn" maxlength="200" show-word-limit placeholder="e.g. Classic Cotton Crew Neck T-Shirt" />
                </el-form-item>
              </el-col>
              <el-col :sm="8">
                <el-form-item label="美元售价（USD）" :error="errors.priceUsd">
                  <el-input-number v-model="form.priceUsd" :min="0" :precision="2" :controls="false" placeholder="留空按汇率换算" style="width: 100%" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :sm="8"><el-form-item label="品牌" :error="errors.brand"><el-input v-model="form.brand" maxlength="100" placeholder="亚马逊必填，无品牌可填 Generic" /></el-form-item></el-col>
              <el-col :sm="8"><el-form-item label="款号（父 SKU）" :error="errors.parentSku"><el-input v-model="form.parentSku" maxlength="64" placeholder="同款不同颜色/尺码填同一个" /></el-form-item></el-col>
              <el-col :sm="8"><el-form-item label="条码 UPC / EAN" :error="errors.barcode"><el-input v-model="form.barcode" maxlength="14" placeholder="12~14 位数字" /></el-form-item></el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :sm="8"><el-form-item label="颜色"><el-input v-model="form.color" maxlength="50" placeholder="e.g. White" /></el-form-item></el-col>
              <el-col :sm="8"><el-form-item label="尺码"><el-input v-model="form.size" maxlength="50" placeholder="e.g. M" /></el-form-item></el-col>
              <el-col :sm="8"><el-form-item label="材质"><el-input v-model="form.material" maxlength="100" placeholder="e.g. 100% Cotton" /></el-form-item></el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :sm="6"><el-form-item label="重量（克）" :error="errors.weightGrams"><el-input-number v-model="form.weightGrams" :min="0" :controls="false" style="width: 100%" /></el-form-item></el-col>
              <el-col :sm="6"><el-form-item label="包装长（厘米）"><el-input-number v-model="form.lengthCm" :min="0" :precision="1" :controls="false" style="width: 100%" /></el-form-item></el-col>
              <el-col :sm="6"><el-form-item label="宽（厘米）"><el-input-number v-model="form.widthCm" :min="0" :precision="1" :controls="false" style="width: 100%" /></el-form-item></el-col>
              <el-col :sm="6"><el-form-item label="高（厘米）"><el-input-number v-model="form.heightCm" :min="0" :precision="1" :controls="false" style="width: 100%" /></el-form-item></el-col>
            </el-row>
            <el-form-item label="英文卖点（每行一条，最多 5 条）" :error="errors.bulletPointsEn">
              <el-input v-model="form.bulletPointsText" type="textarea" :rows="5" placeholder="SOFT &amp; BREATHABLE: 100% cotton keeps you cool all day" />
            </el-form-item>
            <el-form-item label="英文描述" :error="errors.descriptionEn">
              <el-input v-model="form.descriptionEn" type="textarea" :rows="5" maxlength="10000" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <div class="footer-bar">
        <el-button type="primary" :loading="saving" @click="save(false)">保存</el-button>
        <el-button v-if="isNew" :loading="saving" @click="save(true)">保存并继续添加</el-button>
        <el-button @click="$router.push('/products')">取消</el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorText } from '../api'

const route = useRoute()
const router = useRouter()
const isNew = computed(() => !route.params.id)

function empty(keep = {}) {
  return {
    name: '', sku: '', categoryId: keep.categoryId ?? null, price: undefined, originalPrice: undefined, stock: 0,
    description: '', status: keep.status || 'ON_SALE', imageUrls: [],
    titleEn: '', priceUsd: undefined, brand: keep.brand || '', parentSku: '', barcode: '', color: '', size: '', material: '',
    weightGrams: undefined, lengthCm: undefined, widthCm: undefined, heightCm: undefined,
    bulletPointsText: '', descriptionEn: '', version: null
  }
}

const form = reactive(empty())
const errors = ref({})
const categories = ref([])
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const uploading = ref(0)
const newUrl = ref('')
const openSections = ref([])
const aiEnabled = ref(false)
const aiLoading = ref(false)
const aiHint = ref('')

function fromProduct(p) {
  Object.assign(form, empty(), {
    name: p.name, sku: p.sku, categoryId: p.category?.id ?? null, price: num(p.price), originalPrice: num(p.originalPrice),
    stock: p.stock, description: p.description || '', status: p.status, imageUrls: [...p.imageUrls],
    titleEn: p.titleEn || '', priceUsd: num(p.priceUsd), brand: p.brand || '', parentSku: p.parentSku || '',
    barcode: p.barcode || '', color: p.color || '', size: p.size || '', material: p.material || '',
    weightGrams: num(p.weightGrams), lengthCm: num(p.lengthCm), widthCm: num(p.widthCm), heightCm: num(p.heightCm),
    bulletPointsText: (p.bulletPointsEn || []).join('\n'), descriptionEn: p.descriptionEn || '', version: p.version
  })
  if (p.titleEn || p.brand || p.barcode || p.priceUsd || p.parentSku) openSections.value = ['cross']
}

function num(v) {
  return v === null || v === undefined ? undefined : Number(v)
}

function blankToNull(v) {
  return v === '' || v === undefined ? null : v
}

function payload() {
  return {
    name: form.name, sku: blankToNull(form.sku), categoryId: form.categoryId, price: form.price ?? null,
    originalPrice: form.originalPrice ?? null, stock: form.stock ?? 0, description: form.description, status: form.status,
    imageUrls: form.imageUrls, titleEn: blankToNull(form.titleEn), priceUsd: form.priceUsd ?? null,
    brand: blankToNull(form.brand), parentSku: blankToNull(form.parentSku), barcode: form.barcode || '',
    color: blankToNull(form.color), size: blankToNull(form.size), material: blankToNull(form.material),
    weightGrams: form.weightGrams ?? null, lengthCm: form.lengthCm ?? null, widthCm: form.widthCm ?? null,
    heightCm: form.heightCm ?? null, descriptionEn: blankToNull(form.descriptionEn),
    bulletPointsEn: form.bulletPointsText.split('\n').map(s => s.trim()).filter(Boolean),
    version: form.version
  }
}

async function load() {
  conflict.value = false
  errors.value = {}
  if (isNew.value) {
    Object.assign(form, empty({ categoryId: route.query.categoryId ? Number(route.query.categoryId) : null }))
    return
  }
  loading.value = true
  try {
    fromProduct(await api.get(`/api/admin/products/${route.params.id}`))
  } catch (e) {
    ElMessage.error(errorText(e))
    router.push('/products')
  } finally {
    loading.value = false
  }
}

function reload() {
  load()
}

async function save(next) {
  if (!form.name.trim()) {
    errors.value = { name: '请填写商品名称' }
    return
  }
  if (form.price === undefined || form.price === null) {
    errors.value = { price: '请填写售价' }
    return
  }
  saving.value = true
  errors.value = {}
  try {
    const saved = isNew.value
      ? await api.post('/api/admin/products', payload())
      : await api.put(`/api/admin/products/${route.params.id}`, payload())
    ElMessage.success(`已保存「${saved.name}」（${saved.statusLabel}）`)
    if (next) {
      Object.assign(form, empty({ categoryId: form.categoryId, status: form.status, brand: form.brand }))
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      router.push('/products')
    }
  } catch (e) {
    if (e.status === 400 && Object.keys(e.fields).length) {
      errors.value = e.fields
      ElMessage.error('有内容需要修改，请查看标红的地方')
    } else if (e.status === 422 && e.message.includes('编辑期间被修改过')) {
      conflict.value = true
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      ElMessage.error(errorText(e))
    }
  } finally {
    saving.value = false
  }
}

function moveImage(i, d) {
  const list = form.imageUrls
  ;[list[i], list[i + d]] = [list[i + d], list[i]]
}

async function uploadImage({ file }) {
  uploading.value++
  try {
    const fd = new FormData()
    fd.append('files', file)
    const r = await api.upload('/api/admin/images', fd)
    form.imageUrls.push(...r.urls)
  } catch (e) {
    ElMessage.error(`${file.name}：${e.message}`)
  } finally {
    uploading.value--
  }
}

function addUrl() {
  const u = newUrl.value.trim()
  if (!u) return
  if (!/^https?:\/\//.test(u) && !u.startsWith('/')) {
    ElMessage.warning('请填写以 http:// 或 https:// 开头的图片地址')
    return
  }
  if (!form.imageUrls.includes(u)) form.imageUrls.push(u)
  newUrl.value = ''
}

async function generateEnglish() {
  if (!form.name.trim()) {
    ElMessage.warning('请先填写商品名称')
    return
  }
  if ((form.titleEn || form.bulletPointsText || form.descriptionEn)) {
    await ElMessageBox.confirm('会覆盖已填写的英文标题、卖点和描述，确定吗？', 'AI 生成', { type: 'warning' })
  }
  aiLoading.value = true
  aiHint.value = '正在生成，通常需要 10～30 秒…'
  try {
    const r = await api.post('/api/admin/ai/english-draft', {
      name: form.name, categoryId: form.categoryId, description: form.description, brand: form.brand,
      color: form.color, size: form.size, material: form.material, weightGrams: form.weightGrams ?? null,
      lengthCm: str(form.lengthCm), widthCm: str(form.widthCm), heightCm: str(form.heightCm), titleEn: form.titleEn
    })
    form.titleEn = r.titleEn
    form.bulletPointsText = (r.bulletPointsEn || []).join('\n')
    form.descriptionEn = r.descriptionEn || ''
    aiHint.value = '已生成，请检查后再保存（AI 可能出错，尤其是尺寸、材质等事实信息）'
  } catch (e) {
    aiHint.value = e.message
  } finally {
    aiLoading.value = false
  }
}

function str(v) {
  return v === undefined || v === null ? null : String(v)
}

watch(() => route.fullPath, () => {
  if (route.name === 'product-new' || route.name === 'product-edit') load()
})

onMounted(async () => {
  load()
  categories.value = await api.get('/api/admin/categories').catch(() => [])
  try {
    aiEnabled.value = (await api.get('/api/admin/ai/status')).enabled
    aiHint.value = aiEnabled.value
      ? '根据上面填写的中文名称、描述、品牌、颜色、尺码、材质等生成，生成后可以再修改'
      : '需要先设置环境变量 ANTHROPIC_API_KEY 才能使用'
  } catch (e) {
    aiHint.value = ''
  }
})
</script>

<style scoped>
.mb { margin-bottom: 16px; }
.hint { font-size: 13px; font-weight: 400; }
.images { display: flex; flex-wrap: wrap; gap: 12px; }
.image { position: relative; width: 120px; height: 120px; border-radius: 10px; overflow: hidden; border: 1px solid #e4eaf2; background: #f1f5fb; }
.image img { width: 100%; height: 100%; object-fit: cover; }
.main-tag { position: absolute; left: 6px; top: 6px; background: var(--grad); color: #fff; font-size: 11px; padding: 1px 7px; border-radius: 999px; }
.image-actions { position: absolute; inset: auto 0 0 0; display: flex; justify-content: center; gap: 4px; padding: 6px; background: linear-gradient(transparent, rgba(15,23,42,.55)); opacity: 0; transition: opacity .15s; }
.image:hover .image-actions { opacity: 1; }
.image-actions .el-button + .el-button { margin-left: 0; }
.uploader { width: 120px; }
.uploader :deep(.el-upload-dragger) { width: 120px; height: 120px; padding: 18px 6px; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 12px; }
.upload-icon { font-size: 28px; color: #94a3b8; }
.collapse-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; line-height: 1.4; }
:deep(.el-collapse) { border: none; }
:deep(.el-collapse-item__header) { height: auto; min-height: 44px; border-bottom: none; }
:deep(.el-collapse-item__wrap) { border-bottom: none; }
.ai-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.footer-bar { position: sticky; bottom: 0; background: rgba(245,248,252,.92); backdrop-filter: blur(6px); padding: 14px 0; margin-top: 16px; z-index: 5; }
@media (hover: none) { .image-actions { opacity: 1; } }
</style>
