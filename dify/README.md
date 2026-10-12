# 力学实验个人报告 AI 评阅

本目录的 [`lab-report-grading.yml`](./lab-report-grading.yml) 是文件输入、多节点分项评分的 Dify Workflow DSL。系统只评阅个人报告，不再区分个人报告和组报告。

## 导入与发布

1. 在 Dify 工作室选择“导入 DSL”，导入 `lab-report-grading.yml`。
2. 为四个 LLM 节点选择可用模型。
3. 发布工作流并复制 Workflow API Key。
4. 在项目根目录 `.env` 中配置：

```env
DIFY_ENABLED=true
DIFY_API_URL=http://你的Dify地址/v1
DIFY_API_KEY=app-工作流密钥
```

也可以使用 `DIFY_GRADING_API_KEY` 单独配置批阅工作流；设置后它的优先级高于 `DIFY_API_KEY`。修改配置后需要重启 Spring Boot 后端。

## 工作流结构

```text
开始
  ↓
实验报告正文提取
  ↓
报告内容预检
  ├─ 实验原理与过程（20分） ─────┐
  ├─ 数据处理与实验结果（35分） ─┤
  ├─ 分析、误差与结论（30分） ──┼→ 汇总评分与评语 → 结束
  └─ 规范性与个人反思（15分） ──┘
```

报告内容预检只统计有效内容和证据线索，不截断报告，也不设置 8000 字符一类的输入限制。它会防止空报告或内容很少的报告被模型误判为高分。

## 输入和输出

开始节点输入：

| 变量 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `student_name` | text | 是 | 学生姓名 |
| `student_sid` | text | 是 | 学号 |
| `group_name` | text | 否 | 学生所在实验小组，仅作背景信息 |
| `exp_name` | text | 否 | 实验名称 |
| `report_file` | file/document | 是 | 学生正式提交的 PDF、DOC 或 DOCX 报告文件 |

结束节点输出：

| 变量 | 含义 |
|---|---|
| `score` | 0–100 的分项汇总分 |
| `comment` | 包含四个评分维度的中文评阅意见 |

## 平台调用顺序

Spring Boot 后端按以下顺序调用 Dify：

1. 从平台数据库读取学生正式提交的报告原文件。
2. 以 `multipart/form-data` 调用 `POST /v1/files/upload`。
3. 取得返回的文件 `id`。
4. 调用 `POST /v1/workflows/run`，把文件作为 `report_file` 传入：

```json
{
  "report_file": {
    "type": "document",
    "transfer_method": "local_file",
    "upload_file_id": "Dify返回的文件ID"
  }
}
```

平台不再发送 `report_type`、`report_text`、`lab_data` 或 `content_chars`。

## 使用说明

教师在“报告批阅与查重”中打开学生报告并点击“开始 AI 批阅”。AI 分数和评语是教师复核依据，教师确认后才形成最终报告成绩。

文档提取节点主要读取报告中的文本和表格文字。嵌入 PDF/Word 的曲线图片或断口照片如果没有可提取文字，工作流会标记为无法核验，不会直接判定学生未提交图片。
