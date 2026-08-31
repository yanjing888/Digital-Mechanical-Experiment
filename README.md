# 力学实验数字化平台

基于原型 `lab-digital-platform.html` 落地的前后端系统。前端视觉与交互样式保持一致；右下角物小智按角色提供能力入口。

## 目录

```
server/          # Express + MySQL API
  fracture/      # OpenCV 断口分析脚本
public/          # 前端静态页
lab-digital-platform.html  # 原始单页原型（保留对照）
requirements-fracture.txt  # 断口分析 Python 依赖
```

## 快速启动

```bash
npm install
npm run seed   # 初始化/重置演示数据
npm start      # http://localhost:3780
```

### 断口 OpenCV 分析（学生端「断口确认」）

需本机 Python 3，并安装依赖：

```bash
pip install -r requirements-fracture.txt
```

可选环境变量：

```
FRACTURE_PYTHON=python
# Windows 也可: FRACTURE_PYTHON=py -3
FRACTURE_TIMEOUT_MS=45000
```

上传/拍摄断口图后，服务端调用 `server/fracture/analyze.py`，返回过程图（灰度/阈值/边缘/轮廓）、几何特征，并与力—位移曲线做自洽说明。

开发热重载：

```bash
npm run dev
```

## 账号（演示）

登录页只输入账号和密码，**系统自动识别角色**（无需手动切换）。

| 身份 | 账号 | 密码 |
|------|------|------|
| 教师（固定） | `teacher` | `123456` |
| 学生 | 学号（上传名单后生成） | `123456` |

教师下发任务流程：下载模板 → 上传学生名单 → 新建并编排小组 → 选择小组下发。


## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/meta` | 实验目录、教师、小组 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/session` | 当前会话快照 |
| GET/POST | `/api/tasks` | 任务列表 / 下发 |
| POST | `/api/lab/*` | 现场实验流程 |
| POST | `/api/lab/fracture/analyze` | OpenCV 断口分析（过程图+报告） |
| GET/POST | `/api/reports/*` | 组报告 / 个人报告 |
| GET/POST | `/api/grading/*` | 评阅打分 |
| POST | `/api/grading/:sid/ai-review` | AI 评阅（Dify 工作流） |
| GET | `/api/assistant/status` | 智能体状态 |

## 智能体（物小智 / AI 评阅）

- 前端：右下角 FAB；教师评阅弹窗内有「AI 评阅组报告 / 个人报告」
- 后端：`server/services/dify.js` 调用 Dify Workflow
- 工作流 DSL 与接入说明：[`dify/README.md`](dify/README.md)、[`dify/lab-report-grading.yml`](dify/lab-report-grading.yml)
- 环境变量见 `.env.example`（`DIFY_ENABLED` / `DIFY_API_URL` / `DIFY_GRADING_API_KEY`）
- 未配置 Dify 时会走本地演示评阅，分数与评语仍可人工修改后保存

## 数据（MySQL）

默认连接配置写在 `.env`：

```
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASSWORD=your_password
DB_NAME=lab_digital_platform
```

首次启动会自动建库建表；空库会自动播种演示数据。手动重置：

```bash
npm run seed
```
