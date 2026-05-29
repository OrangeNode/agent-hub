import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import { routeMenuDefinitions } from './route-registry'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'
import { useTabsStore } from '../stores/tabs'

const protectedChildren = routeMenuDefinitions.map((item) => ({
  path: item.path.replace(/^\//, ''),
  name: item.name,
  component: item.component,
  meta: {
    title: item.title,
    icon: item.icon,
    permission: item.permission,
    requiresAuth: true
  }
}))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { title: '登录' }
    },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/dashboard',
      children: protectedChildren
    }
  ]
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  const menuStore = useMenuStore()

  authStore.restore()

  if (to.path === '/login') {
    if (authStore.isLoggedIn) {
      return '/dashboard'
    }
    return true
  }

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (authStore.isLoggedIn && !authStore.profile) {
    try {
      await authStore.refreshProfile()
    } catch {
      authStore.logout()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  if (authStore.isLoggedIn && !menuStore.hasMenus) {
    try {
      await menuStore.loadUserMenus()
    } catch {
      authStore.logout()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  return true
})

router.afterEach((to) => {
  if (to.meta.requiresAuth) {
    useTabsStore().addTab({
      name: to.name,
      path: to.path,
      meta: to.meta
    })
  }
})

export default router
