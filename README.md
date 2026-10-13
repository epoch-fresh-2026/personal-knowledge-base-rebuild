# 个人知识库

一个面向大学生期末复习的 RAG 知识库。

录入课程资料、笔记和网页，通过问答查找知识、核对原文，也可以生成自测题。资料依据不足时，系统会拒答。

## 功能

- 多格式文字解析，支持笔记和公开网页
- 资料查询、编辑、替换、删除和失败重试
- 后台入库、异常退出后恢复、自动生成并保存摘要
- 提取文档关键概念，查看具有共同概念的相关资料
- 向量与关键词检索、RRF 融合、模型重排
- 有依据的问答、来源引用、会话保存
- 复杂问题按需拆成两个子任务，由查询子智能体并行查原文
- 流式回答、处理阶段提示和停止生成
- 知识自测、错题记录和重练

## 核心流程

```text
上传资料 → 解析与验重 → 建立索引（资料 READY）→ 自动总结并保存摘要、概念
查看总结 → 读取数据库摘要，不再次调用模型
聊天总结 → 左下角“总结资料”选择已有资料 → 发送 → 读取摘要 → 保存会话

提问 → 分析查询任务 → 普通问题单路查询 / 复杂问题两个子任务并行查询
原文合并去重 → 统一重排 → 判断证据 → 回答或拒答 → 保存会话

选择主题 → 出题 → 作答评分 → 错题重练
```

RRF 按排名融合两路结果：每路贡献 `1 / (60 + 排名)`，相同片段累加。每次查询默认取最多 10 个候选，汇总后交给模型重排，最终分数来自重排模型。[算法参考](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)。

入库任务保存处理阶段，使用行锁和心跳租约保护结果提交。重启后等待租约过期再恢复；删改后的旧任务不能写回结果。见 [入库安全说明](docs/ingestion-safety.md)。

流式问答使用 SSE：`status` 为处理阶段、`delta` 为回答片段、`done` 为完整结果、`error` 为错误；不展示模型内部思维链。同一后端进程一次处理一个流式请求，忙时返回 503，连接超时为 5 分钟。失败或停止不保存半截回答，不支持断点续传；取消不保证供应商立即停止计费。

检索前根据问题和历史生成一个或两个独立查询问题，规划输出最多 256 Token。普通问题单路查；两个独立方面直接由子智能体并行查。每个子任务有自己的上下文，由模型返回 `SEARCH:查询词` 或 `DONE`，最多调用模型和检索各两次，每次输出最多 128 Token、读取最多 2000 字符的检索观察。返回完整原文，合并去重后按原问题统一重排，只判断一次证据、生成一次答案。证据不足直接拒答，不再启动额外补查。

子查询使用独立的两个工作线程，整轮默认限时 90 秒（`app.rag.subagent-timeout`）。SSE 显示各子任务的查询与完成状态；停止或超时会取消未完成的查询，子任务失败则本轮报错。日志记录查询次数、耗时和供应商返回的 Token 用量（缺失时为 `null`），便于比较成本；不保证比单路查询更快或更省 Token。

## 文档解析与总结

支持 `doc/docx`、`ppt/pptx`、`xls/xlsx`、`pdf`、`txt`、`md/markdown`、`csv/tsv`、`html/htm`、`rtf`、`odt/ods/odp`、`epub`。Apache Tika 提取文字，Markdown 转换为正文。

单文件上限 20MB，请求总大小上限 25MB，超限返回 413 和中文提示。不启用 OCR，不支持密码解密；扫描件、图片文字及复杂排版不保证解析。

- 入库后自动总结完整正文并保存到 `document` 表；点击“查看总结”只读数据库，支持 Markdown 显示，生成中每 2 秒轮询。
- 摘要状态独立于资料状态：总结失败不影响检索，重试总结不重建索引。旧资料可手动补生成，启动时不批量补生成。
- 编辑正文或替换文件会重新入库并生成新摘要；删除会取消旧任务。关闭摘要弹窗不取消任务。
- 聊天在证据充分时，读取最多三份命中文档的有效摘要作背景；摘要不作为证据、引用来源或检索打分依据，不额外调用总结模型。
- 聊天输入框左下角“总结资料”从资料库选择已上传的资料，点击发送后读取摘要并保存到会话，不重复调用总结模型。生成中会等待完成；失败或尚无摘要时需先在资料库重试或生成。移除选择、停止等待不会删除资料或取消后台入库。
- 单次总结最多 60000 个 Java 字符；短文一次调用，长文每约 6000 字符分段、最多四份摘要一组合并。总结请求使用 JSON 模式，每次输出最多 1024 Token，截断会明确提示不完整。超限仅使摘要失败，不截断原文。

自动总结不会缩短首次模型耗时，只避免重复查看时再次生成。摘要可能不准确，请核对原文。聊天总结通过选择已有资料确定文档，暂不自动识别自然语言中的文档名；知识自测暂不读取摘要。

## 文档关联

总结时同一次模型调用提取最多 6 个关键概念，保存到 `document_concept` 表。“相关资料”按共同概念数量排序，最多显示 5 份，不额外调用模型。

关联只表示主题相关，目前不参与问答检索。概念按名称精确匹配，统一英文大小写和首尾空格，不自动合并同义词。删改资料会清除旧概念；旧资料可点击“重新生成摘要与概念”补生成，不在启动时批量调用模型。

## 技术栈

- Java 17、Spring Boot、Maven
- PostgreSQL、pgvector、Spring Data JPA
- Spring AI
- Apache Tika、CommonMark（文档文字解析）
- Vue 3、Vite、HTML、CSS、JavaScript
- Docker Compose、GitHub Actions

## Docker 运行

需要先安装并启动 Docker Desktop。

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

Linux、macOS 或 WSL：

```bash
cp .env.example .env
```

编辑 `.env`，填写数据库密码和 `SILICONFLOW_API_KEY`，然后运行：

```powershell
docker compose up --build
```

打开 <http://localhost:8080>。数据库保存在 `postgres-data`，上传的原文件保存在 `document-files`；执行 `docker compose down` 后数据不会丢失。

## 本地运行

需要 Java 17、Maven、Node.js 24 和 PostgreSQL。先创建数据库：

```sql
CREATE DATABASE personal_knowledge_base_rebuild;
```

然后连接到 `personal_knowledge_base_rebuild`，启用向量扩展：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

复制环境变量文件。Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

Linux、macOS 或 WSL：

```bash
cp .env.example .env
```

填写 `.env` 后，构建前端并启动后端：

```powershell
cd frontend
npm ci
npm run build
cd ..
mvn spring-boot:run
```

打开 <http://localhost:8080>，健康检查地址为 <http://localhost:8080/api/health>。

## 测试和打包

`mvn verify` 包含隔离 PostgreSQL/pgvector 集成测试，需要 Docker；不使用业务数据库或真实模型 API。

```powershell
cd frontend
npm ci
npm test
npm run build
cd ..
mvn verify
```

只运行后端单元测试用 `mvn test`，打包用 `mvn package -DskipTests`（先构建前端）。

测试覆盖入库恢复与并发安全、多格式解析、摘要保存与失败重试、检索融合、流式输出和前端交互。恢复测试针对应用进程异常退出，不包含数据库损坏或原文件丢失。

前端开发：在 `frontend` 运行 `npm run dev`，访问 <http://localhost:5173>。GitHub Actions 会在 PR 和 `main` 更新时运行测试及构建。

## 主要接口

| 方法 | 地址 | 作用 |
| --- | --- | --- |
| `GET` | `/api/health` | 健康检查 |
| `POST` | `/api/documents` | 上传文件 |
| `POST` | `/api/documents/notes` | 创建笔记 |
| `POST` | `/api/documents/links` | 收藏网页 |
| `GET` | `/api/documents` | 查询资料列表 |
| `GET` | `/api/documents/{id}` | 查询资料详情 |
| `GET` | `/api/documents/{id}/related` | 查询具有共同概念的相关资料 |
| `GET` | `/api/documents/{id}/summary` | 读取已保存摘要、生成状态与失败原因 |
| `POST` | `/api/documents/{id}/summary` | 后台补生成或重试摘要，返回 202，不重建索引 |
| `PUT` | `/api/documents/{id}` | 更新资料或替换文件 |
| `POST` | `/api/documents/{id}/retry` | 重新处理失败资料 |
| `DELETE` | `/api/documents/{id}` | 删除资料 |
| `POST` | `/api/chat/stream` | 流式问答与处理阶段事件 |
| `GET` | `/api/chat/{conversationId}` | 查询会话历史 |
| `POST` | `/api/practices` | 生成自测题 |
| `POST` | `/api/practices/{id}/answer` | 提交答案并评分 |
| `GET` | `/api/practices/mistakes` | 查询错题 |
| `POST` | `/api/practices/{id}/retry` | 重新练习错题 |

流式聊天请求可选传 `summaryDocumentId`：有值时读取指定文档摘要，无值时检索资料并回答。

## 设计文档

- [产品需求文档（Issue #1）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/1)
- [产品架构设计（Issue #2）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/2)
- [资料库模块（Issue #55）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/55)
- [知识索引模块（Issue #60）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/60)
- [知识问答模块（Issue #67）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/67)
- [Vue 前端（Issue #74）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/74)
- [知识自测（Issue #89）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/89)
- [工程支持（Issue #24）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/24)

## 密钥安全

`.env` 已被 Git 忽略，不要把真实数据库密码或 API Key 写入代码、README 或 `.env.example`。

## License

本项目使用仓库中声明的开源许可证，详见 [LICENSE](LICENSE)。
