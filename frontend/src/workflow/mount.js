import { createApp } from 'vue'
import Workspace from './Workspace.vue'
let app
export function unmountWorkflow() { if (app) app.unmount(); app = null }
export function mountWorkflow(root, page, snapshot) {
  unmountWorkflow()
  root.innerHTML = ''
  app = createApp(Workspace, { page, teacher: snapshot.role === 'teacher', sid: snapshot.student?.sid || '' })
  app.mount(root)
}
