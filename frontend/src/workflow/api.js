import { api } from '../api.js'
export async function call(path, method = 'GET', body) {
  const headers = { Authorization: `Bearer ${api.getToken()}` }
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(`/api/workflow${path}`, { method, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined })
  const data = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(data.error || `请求失败（${response.status}）`)
  return data
}
export function form(file, fields = {}) {
  const data = new FormData(); data.append('file', file)
  Object.entries(fields).forEach(([k, v]) => data.append(k, v))
  return data
}
export async function fileBlob(id, download = false) {
  const response = await fetch(`/api/workflow/files/${encodeURIComponent(id)}?download=${download}`, { headers: { Authorization: `Bearer ${api.getToken()}` } })
  if (!response.ok) throw new Error('文件不存在或无权访问')
  return response.blob()
}
export async function downloadFile(id, name) {
  const url = URL.createObjectURL(await fileBlob(id, true))
  const a = document.createElement('a'); a.href = url; a.download = name; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
