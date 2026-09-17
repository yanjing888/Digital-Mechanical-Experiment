# 力学实验数字化平台

本系统用于实验后的断口采集、原设备数据归档、课堂操作记录和报告评阅。安全门联锁、实验控制与原始采集由拉力设备原有客户端负责；平台不控制设备启动、暂停或停止。

## 当前现场流程

1. 教师完成分组并下发任务。
2. 学生在原设备客户端完成装样、关门联锁、实验与原始数据采集。
3. 实验完成后，学生在平台填写试件和设备编号，按规程拆样并采集断口照片。
4. 学生导入本次设备导出的 CSV、TXT、TSV、XLS 或 XLSX 文件，明确选择数据起始行、力列、位移列和力单位。
5. 平台将原始数据文件、照片、操作扣分明细和归档快照关联到同一实验记录；之后学生可提交个人 Word/PDF 报告。

## 启动生产版本

生产使用的是 Spring Boot + Vue 版本：

```powershell
# 终端 1：断口分析服务
cd fracture-service
python -m uvicorn app:app --host 127.0.0.1 --port 8090

# 终端 2：构建并启动后端
cd backend
mvn package
java -Dfile.encoding=UTF-8 -jar target/lab-digital-platform.jar

# 终端 3：前端开发服务
cd frontend
npm install
npm run dev
```

打开 `http://localhost:3780`。部署到服务器时可先运行 `npm --prefix frontend run build`，再运行 `mvn -f backend/pom.xml package`；构建后的前端静态文件会随 Spring Boot 包一起提供。

也可运行 `scripts/start-spring-vue.bat` 启动三个服务。

## 已实施功能

- 实验记录以“每次实验”为单位，保存成员快照、试件编号、设备编号、断口照片、设备原始数据及归档快照。
- 断口可先采集，原设备数据可稍后补齐；归档时才检查完整性。
- 教师可在手机或平板上记录课堂扣分、填写依据、撤销纠错、锁定课堂记录，并独立保存最终分和评语。
- 学生上传 Word/PDF 后保留原件、SHA-256 校验值、服务端提交时间和版本回执。扫描件或解析不完整的报告会提示人工查看，不会直接按内容缺失扣分。
- 教师可配置扣分清单、报告必需章节、组内相似度阈值和补做时间窗口；重做生成独立实验记录，教师决定采用哪次成绩。
- 断口分析使用拍摄质量和宏观轮廓线索，结果必须由教师复核。报告 AI 建议和查重结果均不自动覆盖教师最终分。

首次上线前，教师应在“教学规则与学校模板”中录入最终扣分项目与分值，并上传学校正式报告模板。OCR 为可选能力：如果需要识别扫描件，请在环境变量中设置 `REPORT_OCR_COMMAND` 为本机 Tesseract 可执行文件路径。

## 账号（演示）

| 身份 | 账号 | 密码 |
|------|------|------|
| 教师（固定） | `teacher` | `123456` |
| 学生 | 学号（上传名单后生成） | `123456` |

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/meta` | 实验目录、教师、小组 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/session` | 当前会话快照 |
| GET/POST | `/api/tasks` | 任务列表与下发 |
| GET/POST | `/api/workflow/*` | 实验记录、原始数据、照片、课堂扣分、报告、复核与重做 |
| GET | `/api/assistant/status` | AI 评阅服务状态 |

旧版 Node/Express 原型仍在仓库中供对照；现场应使用上面的 Spring Boot + Vue 启动方式。

## 数据库配置

默认连接配置写在 `.env`：

```text
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASSWORD=your_password
DB_NAME=lab_digital_platform
```

后端首次启动会补齐新表，不会清空旧有数据。旧的组报告和个人报告会在首次进入新的实验记录时保留为升级前快照；升级前曲线不会被标注为本次从设备导入的数据。
