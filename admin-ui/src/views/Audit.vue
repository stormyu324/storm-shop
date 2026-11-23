<template>
 <div><div class="page-head"><h1>后台操作记录</h1><el-button @click="load">刷新</el-button></div>
 <el-card><p class="muted">记录操作人、接口、目标、时间与响应状态。</p>
 <el-table :data="page.items"><el-table-column label="时间" width="170"><template #default="{row}">{{ dateTime(row.createdAt) }}</template></el-table-column>
 <el-table-column prop="actor" label="操作人" /><el-table-column prop="action" label="操作" min-width="280" />
 <el-table-column prop="target" label="目标" /><el-table-column prop="result" label="响应状态" width="100" /></el-table>
 <el-pagination layout="prev,pager,next" :total="page.totalItems" :page-size="30" @current-change="n=>{pageNo=n-1;load()}" />
 </el-card></div>
</template>
<script setup>
import { onMounted,ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api,errorText } from '../api'
import { dateTime } from '../format'
const page=ref({items:[],totalItems:0}),pageNo=ref(0)
async function load() {try {page.value=await api.get('/api/admin/audit',{page:pageNo.value})} catch(e) {ElMessage.error(errorText(e))}}
onMounted(load)
</script>
