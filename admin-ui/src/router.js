import { createRouter, createWebHistory } from 'vue-router'
import { auth, loadSession } from './auth'
import AdminLayout from './components/AdminLayout.vue'

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true, title: '登录' } },
    {
      path: '/',
      component: AdminLayout,
      children: [
        { path: '', name: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '概览' } },
        { path: 'products', name: 'products', component: () => import('./views/Products.vue'), meta: { title: '商品' } },
        { path: 'products/new', name: 'product-new', component: () => import('./views/ProductEdit.vue'), meta: { title: '添加商品', menu: 'products' } },
        { path: 'products/:id', name: 'product-edit', component: () => import('./views/ProductEdit.vue'), meta: { title: '编辑商品', menu: 'products' } },
        { path: 'import', name: 'import', component: () => import('./views/Import.vue'), meta: { title: '批量导入' } },
        { path: 'export', name: 'export', component: () => import('./views/Export.vue'), meta: { title: '平台导出' } },
        { path: 'orders', name: 'orders', component: () => import('./views/Orders.vue'), meta: { title: '订单' } },
        { path: 'categories', name: 'categories', component: () => import('./views/Categories.vue'), meta: { title: '分类' } },
        { path: 'shipping', name: 'shipping', component: () => import('./views/Shipping.vue'), meta: { title: '运费' } },
        // 旧版后台的地址，收藏夹里的链接继续可用
        { path: 'products/:id/edit', redirect: to => `/products/${to.params.id}` },
        { path: 'products/import', redirect: '/import' }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach(async (to) => {
  if (!auth.loaded) {
    try {
      await loadSession()
    } catch (e) {
      // 后端没启动时也让页面显示出来，由登录页提示错误
    }
  }
  if (!to.meta.public && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.authenticated) {
    return { path: '/' }
  }
  document.title = `${to.meta.title || '后台'} - Storm 商城后台`
})

export default router
