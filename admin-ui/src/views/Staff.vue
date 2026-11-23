<template>
  <div>
    <div class="page-head">
      <h1>员工权限</h1>
      <el-button type="primary" @click="open()">添加员工</el-button>
    </div>
    <el-card>
      <p class="muted">商品岗位管理上架与配送规则，客服处理订单和发货，运营管理优惠券与报表，管理员审核退款和分配权限。</p>
      <el-table :data="page.items">
        <el-table-column prop="username" label="用户名" />
        <el-table-column label="岗位">
          <template #default="{ row }">{{ roles[row.role] }}</template>
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
    <el-dialog v-model="visible" title="员工设置" width="min(520px,94vw)">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="form.username" :disabled="Boolean(form.id)" maxlength="80" /></el-form-item>
        <el-form-item :label="form.id ? '新密码（留空保持现有密码）' : '初始密码（至少 12 字符）'">
          <el-input v-model="form.password" type="password" autocomplete="new-password" maxlength="72" />
        </el-form-item>
        <el-form-item label="岗位">
          <el-select v-model="form.role"><el-option v-for="(label, key) in roles" :key="key" :value="key" :label="label" /></el-select>
        </el-form-item>
        <el-switch v-model="form.enabled" active-text="启用账号" />
      </el-form>
      <template #footer><el-button :loading="busy" type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorText } from '../api'
const roles = { CATALOG: '商品', SUPPORT: '客服', OPERATIONS: '运营', ADMIN: '管理员' }
const page = ref({ items: [], totalItems: 0 })
const pageNo = ref(0)
const form = ref({})
const visible = ref(false)
const busy = ref(false)
function open(row) {
  form.value = row ? { ...row, password: '' } : { username: '', password: '', role: 'SUPPORT', enabled: true }
  visible.value = true
}
async function load() {
  try {
    page.value = await api.get('/api/admin/staff', { page: pageNo.value })
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
async function save() {
  busy.value = true
  try {
    await (form.value.id ? api.put('/api/admin/staff/' + form.value.id, form.value) : api.post('/api/admin/staff', form.value))
    visible.value = false
    form.value = {}
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
