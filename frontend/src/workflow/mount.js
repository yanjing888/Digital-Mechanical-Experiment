import { createApp } from 'vue'
import Workspace from './Workspace.vue'
let app
export function unmountWorkflow() { if (app) app.unmount(); app = null }
export function mountWorkflow(root, page, snapshot) {
  unmountWorkflow()
  root.innerHTML = ''
  app = createApp(Workspace, { page, teacher: snapshot.role === 'teacher', sid: snapshot.student?.sid || '', taskId: snapshot.student?.taskId || '', students: snapshot.role === 'teacher' ? snapshot.meta?.students || [] : [] })
  app.mount(root)
}
