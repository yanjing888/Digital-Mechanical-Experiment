'use strict';

window.LabAPI = (function () {
  var TOKEN_KEY = 'lab-platform-token';

  function getToken() {
    return localStorage.getItem(TOKEN_KEY) || '';
  }

  function setToken(t) {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  }

  async function request(method, path, body) {
    var opts = {
      method: method,
      headers: { 'Content-Type': 'application/json' }
    };
    var token = getToken();
    if (token) opts.headers.Authorization = 'Bearer ' + token;
    if (body != null) opts.body = JSON.stringify(body);
    var res = await fetch('/api' + path, opts);
    var data = null;
    try { data = await res.json(); } catch (_) { data = null; }
    if (!res.ok) {
      var err = new Error((data && data.error) || ('请求失败 ' + res.status));
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  async function upload(path, file) {
    var fd = new FormData();
    fd.append('file', file);
    var opts = { method: 'POST', headers: {}, body: fd };
    var token = getToken();
    if (token) opts.headers.Authorization = 'Bearer ' + token;
    var res = await fetch('/api' + path, opts);
    var data = null;
    try { data = await res.json(); } catch (_) { data = null; }
    if (!res.ok) {
      var err = new Error((data && data.error) || ('上传失败 ' + res.status));
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function downloadTemplate() {
    var token = getToken();
    return fetch('/api/roster/template', {
      headers: token ? { Authorization: 'Bearer ' + token } : {}
    }).then(function (res) {
      if (!res.ok) throw new Error('下载模板失败');
      return res.blob();
    }).then(function (blob) {
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = '学生名单模板.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    });
  }

  return {
    getToken: getToken,
    setToken: setToken,
    meta: function () { return request('GET', '/meta'); },
    login: function (payload) { return request('POST', '/auth/login', payload); },
    logout: function () { return request('POST', '/auth/logout', {}); },
    session: function () { return request('GET', '/session'); },
    createTask: function (payload) { return request('POST', '/tasks', payload); },
    downloadRosterTemplate: downloadTemplate,
    uploadRoster: function (file) { return upload('/roster/upload', file); },
    createGroup: function (name) { return request('POST', '/groups', { name: name }); },
    renameGroup: function (id, name) { return request('PATCH', '/groups/' + encodeURIComponent(id), { name: name }); },
    deleteGroup: function (id) { return request('DELETE', '/groups/' + encodeURIComponent(id)); },
    moveToGroup: function (id, memberSids) {
      return request('POST', '/groups/' + encodeURIComponent(id) + '/members', { memberSids: memberSids });
    },
    ungroup: function (memberSids) { return request('POST', '/groups/ungroup', { memberSids: memberSids }); },
    labStart: function () { return request('POST', '/lab/start', {}); },
    labDoor: function (closed) { return request('POST', '/lab/door', { closed: closed }); },
    labStartMachine: function () { return request('POST', '/lab/start-machine', {}); },
    labAcq: function (payload) { return request('POST', '/lab/acq', payload); },
    labPause: function (paused) { return request('POST', '/lab/pause', { paused: paused }); },
    labRupture: function (payload) { return request('POST', '/lab/rupture', payload || {}); },
    labFractureAnalyze: function (payload) { return request('POST', '/lab/fracture/analyze', payload); },
    labFracture: function (payload) { return request('POST', '/lab/fracture', payload); },
    labFinish: function () { return request('POST', '/lab/finish', {}); },
    confirmGroup: function () { return request('POST', '/reports/group/confirm', {}); },
    submitGroup: function () { return request('POST', '/reports/group/submit', {}); },
    savePersonal: function (payload) { return request('POST', '/reports/personal/save', payload); },
    submitPersonal: function (payload) { return request('POST', '/reports/personal/submit', payload); },
    gradingDetail: function (sid) { return request('GET', '/grading/' + encodeURIComponent(sid)); },
    gradingSave: function (sid, payload) { return request('POST', '/grading/' + encodeURIComponent(sid), payload); },
    gradingAiReview: function (sid, reportType) {
      return request('POST', '/grading/' + encodeURIComponent(sid) + '/ai-review', { reportType: reportType });
    },
    assistantStatus: function () { return request('GET', '/assistant/status'); }
  };
})();
