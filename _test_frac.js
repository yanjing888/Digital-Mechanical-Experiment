'use strict';
const { analyzeFracture } = require('./server/services/fracture');
const { spawnSync } = require('child_process');
const py = [
  'import base64,cv2,numpy as np',
  'img=np.zeros((200,280,3),np.uint8); img[:]=40',
  'cv2.ellipse(img,(140,100),(80,55),0,0,360,(130,130,130),-1)',
  'ok,buf=cv2.imencode(".jpg",img)',
  'print(base64.b64encode(buf.tobytes()).decode())'
].join('\n');
const out = spawnSync('python', ['-c', py], { encoding: 'utf8' });
if (out.status !== 0) {
  console.error(out.stderr);
  process.exit(1);
}
const b64 = out.stdout.trim();
analyzeFracture({
  imageBase64: 'data:image/jpeg;base64,' + b64,
  points: [{ d: 0, f: 0 }, { d: 1, f: 15 }, { d: 2, f: 3 }]
}).then((r) => {
  console.log('OK', r.fractureType, r.fractureConf, 'steps', r.steps.length, 'ms', r.elapsedMs);
}).catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
