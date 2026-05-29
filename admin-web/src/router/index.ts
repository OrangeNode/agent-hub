import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import { moduleRouteGroups } from '../config/module-routes'
import { useAuthStore } from '../stores/auth'

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
      children: [
        {
          path: '',
          name: 'dashboard',
          component: DashboardView,
          meta: { title: '仪表盘', requiresAuth: true }
        },
        ...moduleRouteGroups
      ]
    }
  ]
})

router.beforeEach((to) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' && authStore.isLoggedIn) {
    return { name: 'dashboard' }
  }

  return true
})

export default router
