# 力学实验报告 AI 评阅（Dify Workflow）

把本目录下的 DSL 导入 Dify，把生成的 **Workflow API Key** 配进平台 `.env`，教师端评阅即可一键 AI 打分。

## 1. 在 Dify 创建工作流

1. 打开 Dify → **工作室应用** → **工作流**
2. 右上角 **导入 DSL** → 选择 [`lab-report-grading.yml`](./lab-report-grading.yml)
3. 确认结构：

   ```
   开始 → IF/ELSE
            ├─ IF（true）  → 个人报告评阅 ─┐
            └─ ELSE（false）→ 组报告评阅   ─┴→ 变量聚合 → 解析 → 结束
   ```

4. **IF/ELSE 条件只配这一条（不要再加 ELIF）：**
   - 变量：`开始` → `report_type`
   - 运算符：**是**（is）
   - 值：`personal`（英文小写，不要空格）
   - IF 出口连「个人报告评阅」
   - ELSE 出口连「组报告评阅」（ELSE 不用写条件）

5. 两个 LLM 都选好模型 → **发布**
6. 复制 API Base / API Key 配进平台 `.env`

> `report_type` 已改为**文本**（不是下拉），避免条件分支类型报错。平台仍传 `personal` / `group`。

若导入后分支仍红：删掉条件节点重拖一个 IF/ELSE，按上面第 4 步手配即可，其它节点可保留。

若「变量聚合」报 `advanced_settings.groups Field required`：点开该节点，打开「聚合分组」再关掉一次（或删掉该节点重拖），确保不要只留半截 advanced_settings。


## 2. 平台环境变量

在项目根目录 `.env` 中配置：

```env
DIFY_ENABLED=true
DIFY_API_URL=https://api.dify.ai/v1
DIFY_GRADING_API_KEY=app-你的工作流密钥
```

说明：
- `DIFY_GRADING_API_KEY`：专门用于「报告评阅」工作流（推荐）
- 若未设，会回退使用 `DIFY_API_KEY`
- 改完后重启 `npm start`
- **空报告也会调用本工作流**，由对应分支的提示词给低分与诚实评语（平台不再本地拦掉）

## 3. 工作流输入 / 输出约定

平台调用 `POST /v1/workflows/run`，传入：

| 变量 | 类型 | 含义 |
|------|------|------|
| `report_type` | text | `personal` 或 `group`（决定走哪条分支） |
| `student_name` | text | 学生姓名 |
| `student_sid` | text | 学号 |
| `group_name` | text | 小组名 |
| `exp_name` | text | 实验名称 |
| `report_text` | paragraph | 报告正文（已去 HTML；空则为明确空文提示） |
| `lab_data` | paragraph | 现场数据摘要 JSON 文本 |
| `content_chars` | text | 平台估算的有效字数（提示用） |

工作流结束节点需输出：

| 变量 | 含义 |
|------|------|
| `score` | 0–100 |
| `comment` | 中文评语 |

## 4. 两条分支分别评什么

| 分支 | 条件 | 评阅对象 |
|------|------|----------|
| 个人报告 | `report_type = personal` | 学生自填的步骤 / 分析与误差 / 总结与反思 |
| 组报告 | 其它（`group`） | 平台汇总的曲线、断口、关键数据与结论 |

个人报告空文硬规则写在「个人报告评阅」LLM 提示词里：空/仅标题 → 约 5–15 分，禁止「有深度」「质量较高」等空话。

## 5. 教师端怎么用

1. 登录教师账号 → **报告评阅打分**
2. 打开某位已提交报告的学生
3. 点 **AI 评阅组报告** / **AI 评阅个人报告**
4. 分数与评语自动填入，可直接改，再点 **保存评分**

## 6. 手动搭建（导入失败时）

### Start 节点变量

与上表输入变量一致（含 `content_chars`）。

### 条件分支（关键）

只配 **IF**，不要配第二条 ELIF：

| 项 | 值 |
|----|----|
| 变量 | `开始.report_type` |
| 比较 | **是** / is |
| 值 | `personal` |

- **IF** → 「个人报告评阅」LLM  
- **ELSE** → 「组报告评阅」LLM（ELSE 无需条件）

两路 LLM 的 `text` 用 **变量聚合器**（字符串）合成一路，再进 Code → End。


### 个人报告 LLM（系统）

```text
你是北方工业大学力学实验课程助教，专门评阅「个人报告」。
个人报告由学生本人填写（步骤/分析与误差/总结与反思）。
必须只根据正文实际写出的内容给分，禁止臆造。
只输出一行 JSON：{"score":12,"comment":"……"}
```

空报告规则：三部分几乎为空或不足约 40 字 → score 5–15，评语写明建议退回；禁止褒义空话。

### 组报告 LLM（系统）

```text
你是北方工业大学力学实验课程助教，专门评阅「组报告」。
组报告含曲线、断口与关键数据；按结构完整、数据一致、分析合理给分。
只输出一行 JSON：{"score":80,"comment":"……"}
```

### Code 节点

输入接变量聚合的 `output`：

```python
import json, re

def main(llm_text: str) -> dict:
    text = (llm_text or "").strip()
    m = re.search(r"\{[\s\S]*\}", text)
    raw = m.group(0) if m else text
    try:
        data = json.loads(raw)
    except Exception:
        data = {"score": 40, "comment": text[:500] or "评阅生成失败，请人工评分。"}
    score = data.get("score", 40)
    try:
        score = float(score)
    except Exception:
        score = 40.0
    score = max(0.0, min(100.0, score))
    comment = str(data.get("comment") or "").strip() or "请教师补充评语。"
    return {"score": score, "comment": comment}
```

### End 节点

输出 `score`、`comment`。

## 7. 自测工作流

**有内容的个人报告：**

- report_type: `personal`
- report_text: 含具体步骤、Fmax、误差与反思的正文
- 期望：中高分 + 能点出正文依据的评语

**空个人报告：**

- report_type: `personal`
- report_text: 仅「一、实验步骤描述」等标题或留空
- content_chars: `0`
- 期望：走个人分支，score 约 5–15，评语写明内容为空

**组报告：**

- report_type: `group`
- report_text: 含曲线/断口/数据的组报告正文
- 期望：走组报告分支，按数据完整性与分析给分
