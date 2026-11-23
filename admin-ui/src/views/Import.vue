<template>
  <div>
    <div class="page-head"><h1>批量导入</h1></div>
    <el-card>
      <h3 class="section-title">1. 下载模板，用 Excel / WPS 填写</h3>
      <p class="muted">只有<b>名称</b>和<b>售价</b>必填；<b>SKU 相同则更新、不同则新建</b>，所以同一张表可以反复导入来改价、补库存。
        分类不存在会自动创建；多张图片网址用 <code>|</code> 分隔；UTF-8 和 GBK 编码都能识别。</p>
      <a href="/admin/products/import/template.csv"><el-button><el-icon><Download /></el-icon>&nbsp;下载 CSV 模板</el-button></a>
      <details class="cols">
        <summary class="muted">查看全部列</summary>
        <p class="mono small">名称, SKU, 分类, 售价, 原价, 库存, 状态, 图片, 描述, 英文标题, 美元售价, 品牌, 款号, 颜色, 尺码, 材质, 重量(g), 长(cm), 宽(cm), 高(cm), 条码, 英文卖点, 英文描述</p>
      </details>
    </el-card>

    <el-card>
      <h3 class="section-title">2. 另存为 CSV 后上传</h3>
      <div class="toolbar">
        <span>没填“状态”的行导入为</span>
        <el-radio-group v-model="defaultStatus">
          <el-radio-button value="ON_SALE">上架</el-radio-button>
          <el-radio-button value="DRAFT">草稿</el-radio-button>
          <el-radio-button value="OFF_SHELF">下架</el-radio-button>
        </el-radio-group>
      </div>
      <el-upload drag accept=".csv,text/csv" :show-file-list="false" :http-request="upload" :disabled="loading">
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div>{{ loading ? '正在导入…' : '把 CSV 文件拖到这里，或点击选择' }}</div>
      </el-upload>
    </el-card>

    <el-card v-if="result">
      <h3 class="section-title">导入结果</h3>
      <el-alert :type="result.errors.length ? 'warning' : 'success'" :closable="false" show-icon
                :title="`新建 ${result.created} 个，更新 ${result.updated} 个` + (result.errors.length ? `，${result.errors.length} 行有问题没有导入` : '')" />
      <el-table v-if="result.errors.length" :data="result.errors" style="margin-top: 12px" max-height="400">
        <el-table-column label="行号" prop="line" width="80" />
        <el-table-column label="问题" prop="message" />
      </el-table>
      <p style="margin-bottom: 0"><router-link to="/products">去商品列表查看 →</router-link></p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorText } from '../api'

const defaultStatus = ref('ON_SALE')
const loading = ref(false)
const result = ref(null)

async function upload({ file }) {
  loading.value = true
  result.value = null
  try {
    const fd = new FormData()
    fd.append('file', file)
    result.value = await api.upload('/api/admin/products/import', fd, { defaultStatus: defaultStatus.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.upload-icon { font-size: 40px; color: #94a3b8; }
.cols { margin-top: 12px; }
.small { font-size: 12px; }
code { background: #eef2f8; padding: 1px 5px; border-radius: 4px; }
</style>
