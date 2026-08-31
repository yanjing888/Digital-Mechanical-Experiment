'use strict';

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const SCRIPT = path.join(__dirname, '..', 'fracture', 'analyze.py');
const TIMEOUT_MS = Number(process.env.FRACTURE_TIMEOUT_MS || 45000);

function pythonCandidates() {
  const fromEnv = process.env.FRACTURE_PYTHON;
  if (fromEnv) return [fromEnv];
  if (process.platform === 'win32') {
    return ['py -3', 'python', 'python3'];
  }
  return ['python3', 'python'];
}

function runOne(cmd, args, input, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      windowsHide: true,
      shell: cmd.includes(' '),
      env: process.env
    });
    let stdout = '';
    let stderr = '';
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { child.kill(); } catch (_) {}
      reject(Object.assign(new Error('断口分析超时，请缩小图片后重试'), { status: 504 }));
    }, timeoutMs);

    child.stdout.on('data', (d) => { stdout += d.toString('utf8'); });
    child.stderr.on('data', (d) => { stderr += d.toString('utf8'); });
    child.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });
    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      let parsed = null;
      try { parsed = JSON.parse(stdout || '{}'); } catch (_) {}
      if (parsed && parsed.error) {
        reject(Object.assign(new Error(parsed.error), { status: 400 }));
        return;
      }
      if (code !== 0) {
        const msg = (parsed && parsed.error)
          || (stderr && stderr.trim())
          || ('断口分析进程退出码 ' + code);
        reject(Object.assign(new Error(msg), { status: 500 }));
        return;
      }
      if (!parsed || !parsed.fractureType) {
        reject(Object.assign(new Error(stderr.trim() || '断口分析无有效结果'), { status: 500 }));
        return;
      }
      resolve(parsed);
    });

    child.stdin.write(input);
    child.stdin.end();
  });
}

/**
 * @param {{ imageBase64: string, points?: Array }} payload
 */
async function analyzeFracture(payload) {
  if (!fs.existsSync(SCRIPT)) {
    throw Object.assign(new Error('缺少分析脚本 server/fracture/analyze.py'), { status: 500 });
  }
  const imageBase64 = payload && payload.imageBase64;
  if (!imageBase64 || typeof imageBase64 !== 'string') {
    throw Object.assign(new Error('请先提供断口图像'), { status: 400 });
  }
  // Guard oversized payloads (~8MB raw b64)
  if (imageBase64.length > 12 * 1024 * 1024) {
    throw Object.assign(new Error('图像过大，请压缩后再上传'), { status: 400 });
  }

  const input = JSON.stringify({
    imageBase64,
    points: Array.isArray(payload.points) ? payload.points : []
  });

  const candidates = pythonCandidates();
  let lastErr = null;
  for (const cand of candidates) {
    const parts = cand.split(/\s+/);
    const cmd = parts[0];
    const prefixArgs = parts.slice(1);
    try {
      return await runOne(cmd, prefixArgs.concat([SCRIPT]), input, TIMEOUT_MS);
    } catch (e) {
      lastErr = e;
      // spawn ENOENT -> try next candidate
      if (e && e.code === 'ENOENT') continue;
      // script-level errors should bubble
      if (e && e.status && e.status !== 500) throw e;
      if (e && /未安装 OpenCV|pip install/i.test(String(e.message || ''))) throw e;
      // other failures: still try next python if looks like missing binary
      if (/ENOENT|not found|不是内部或外部命令/i.test(String(e.message || ''))) continue;
      throw e;
    }
  }
  throw Object.assign(
    new Error(
      (lastErr && lastErr.message)
        || '未找到 Python。请安装 Python 3 并执行: pip install -r requirements-fracture.txt'
    ),
    { status: 500 }
  );
}

module.exports = { analyzeFracture };
