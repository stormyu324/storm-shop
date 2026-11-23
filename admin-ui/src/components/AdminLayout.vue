<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '208px'" class="aside">
      <router-link to="/" class="brand">
        <span class="logo"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M13.5 2 4 13.5h6.5L9 22l10-12h-6.6L13.5 2z"/></svg></span>
        <span v-show="!collapsed">Storm<span class="accent">商城</span></span>
      </router-link>
      <el-menu :default-active="active" :collapse="collapsed" router class="menu">
        <el-menu-item index="/"><el-icon><Odometer /></el-icon><template #title>概览</template></el-menu-item>
        <el-menu-item index="/products"><el-icon><Goods /></el-icon><template #title>商品</template></el-menu-item>
        <el-menu-item index="/import"><el-icon><Upload /></el-icon><template #title>批量导入</template></el-menu-item>
        <el-menu-item index="/export"><el-icon><Promotion /></el-icon><template #title>平台导出</template></el-menu-item>
        <el-menu-item index="/orders"><el-icon><Tickets /></el-icon><template #title>订单</template></el-menu-item>
        <el-menu-item index="/categories"><el-icon><Menu /></el-icon><template #title>分类</template></el-menu-item>
        <el-menu-item index="/shipping"><el-icon><Van /></el-icon><template #title>运费</template></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-button text @click="collapsed = !collapsed" :icon="collapsed ? 'Expand' : 'Fold'" />
        <div class="spacer" />
        <a href="/" target="_blank" class="header-link"><el-icon><Shop /></el-icon> 查看商城</a>
        <a href="/admin" class="header-link">旧版后台</a>
        <el-dropdown @command="onCommand">
          <span class="user"><el-icon><User /></el-icon> {{ auth.username }} <el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="docs">接口文档</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, logout } from '../auth'

const route = useRoute()
const router = useRouter()
const collapsed = ref(window.innerWidth < 900)
const active = computed(() => '/' + (route.meta.menu || route.path.split('/')[1] || ''))

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await logout()
    router.push({ name: 'login' })
  } else if (cmd === 'docs') {
    window.open('/swagger-ui.html', '_blank')
  }
}
</script>

<style scoped>
.layout { min-height: 100vh; }
.aside { background: #fff; border-right: 1px solid #e4eaf2; transition: width .2s; overflow: hidden; }
.brand { display: flex; align-items: center; gap: 10px; height: 60px; padding: 0 16px; font-weight: 700; font-size: 17px; color: #0f172a; text-decoration: none; white-space: nowrap; }
.logo { flex: none; width: 32px; height: 32px; border-radius: 9px; background: var(--grad); color: #fff; display: inline-flex; align-items: center; justify-content: center; }
.accent { background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
.menu { border-right: none; }
.header { background: #fff; border-bottom: 1px solid #e4eaf2; display: flex; align-items: center; gap: 18px; height: 60px; }
.spacer { flex: 1; }
.header-link { display: inline-flex; align-items: center; gap: 4px; color: #334155; font-size: 14px; }
.user { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; color: #334155; outline: none; }
.main { padding: 22px; }
@media (max-width: 600px) { .header-link { display: none; } .main { padding: 14px; } }
</style>
