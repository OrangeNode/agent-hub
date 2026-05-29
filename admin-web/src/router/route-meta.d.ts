import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    section?: string
    description?: string
    requiresAuth?: boolean
  }
}
