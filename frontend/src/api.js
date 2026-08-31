const TOKEN_KEY = 'lab-platform-token'

function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

function setToken(t) {
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

async function request(method, path, body) {
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json' }
  }
  const token = getToken()
  if (token) opts.headers.Authorization = 'Bearer ' + token
  if (body != null) opts.body = JSON.stringify(body)
  const res = await fetch('/api' + path, opts)
  let data = null
  try { data = await res.json() } catch { data = null }
  if (!res.ok) {
    const err = new Error((data && data.error) || ('请求失败 ' + res.status))
    err.status = res.status
    err.data = data
    throw err
  }
  return data
}

async function upload(path, file) {
  const fd = new FormData()
  fd.append('file', file)
  const opts = { method: 'POST', headers: {}, body: fd }
  const token = getToken()
  if (token) opts.headers.Authorization = 'Bearer ' + token
  const res = await fetch('/api' + path, opts)
  let data = null
  try { data = await res.json() } catch { data = null }
  if (!res.ok) {
    const err = new Error((data && data.error) || ('上传失败 ' + res.status))
    err.status = res.status
    throw err
  }
  return data
}

function downloadTemplate() {
  const token = getToken()
  return fetch('/api/roster/template', {
    headers: token ? { Authorization: 'Bearer ' + token } : {}
  }).then((res) => {
    if (!res.ok) throw new Error('下载模板失败')
    return res.blob()
  }).then((blob) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '学生名单模板.xlsx'
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  })
}

export const api = {
  getToken,
  setToken,
  meta: () => request('GET', '/meta'),
  login: (payload) => request('POST', '/auth/login', payload),
  logout: () => request('POST', '/auth/logout', {}),
  session: () => request('GET', '/session'),
  createTask: (payload) => request('POST', '/tasks', payload),
  downloadRosterTemplate: downloadTemplate,
  uploadRoster: (file) => upload('/roster/upload', file),
  createGroup: (name) => request('POST', '/groups', { name }),
  renameGroup: (id, name) => request('PATCH', '/groups/' + encodeURIComponent(id), { name }),
  deleteGroup: (id) => request('DELETE', '/groups/' + encodeURIComponent(id)),
  moveToGroup: (id, memberSids) => request('POST', '/groups/' + encodeURIComponent(id) + '/members', { memberSids }),
  ungroup: (memberSids) => request('POST', '/groups/ungroup', { memberSids }),
  autoGroup: (payload) => request('POST', '/groups/auto', payload || {}),
  labStart: () => request('POST', '/lab/start', {}),
  labDoor: (closed) => request('POST', '/lab/door', { closed }),
  labStartMachine: () => request('POST', '/lab/start-machine', {}),
  labAcq: (payload) => request('POST', '/lab/acq', payload),
  labPause: (paused) => request('POST', '/lab/pause', { paused }),
  labRupture: (payload) => request('POST', '/lab/rupture', payload || {}),
  labFractureAnalyze: (payload) => request('POST', '/lab/fracture/analyze', payload),
  labFracture: (payload) => request('POST', '/lab/fracture', payload),
  labFractureSelf: (payload) => request('POST', '/lab/fracture/self', payload),
  labFinish: () => request('POST', '/lab/finish', {}),
  confirmGroup: () => request('POST', '/reports/group/confirm', {}),
  submitGroup: () => request('POST', '/reports/group/submit', {}),
  savePersonal: (payload) => request('POST', '/reports/personal/save', payload),
  submitPersonal: (payload) => request('POST', '/reports/personal/submit', payload),
  gradingDetail: (sid) => request('GET', '/grading/' + encodeURIComponent(sid)),
  gradingSave: (sid, payload) => request('POST', '/grading/' + encodeURIComponent(sid), payload),
  gradingAiReview: (sid, reportType) => request('POST', '/grading/' + encodeURIComponent(sid) + '/ai-review', { reportType }),
  deductionCatalog: () => request('GET', '/grading/deduction-catalog'),
  addDeduction: (sid, payload) => request('POST', '/grading/' + encodeURIComponent(sid) + '/deduction', payload),
  removeDeduction: (sid, did) => request('DELETE', '/grading/' + encodeURIComponent(sid) + '/deduction/' + encodeURIComponent(did)),
  setMaterial: (sid, materialType) => request('POST', '/grading/' + encodeURIComponent(sid) + '/material', { materialType }),
  assistantStatus: () => request('GET', '/assistant/status')
}

export default api
