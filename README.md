# 个人知识库

一个面向大学生期末复习的 RAG 知识库。

学生可以录入课程讲义、学习笔记和参考网页，用自然语言查找知识，并通过回答来源核对原文。资料中没有足够依据时，系统会明确拒答。

## 功能

- 解析 TXT、Markdown、PDF 和 DOCX
- 录入学习笔记和公开参考网页
- 文本切分、向量化和 pgvector 存储
- 资料查询、修改、替换和删除
- 向量与关键词检索、RRF 排名融合、模型重排、Agent 按需二次检索、无依据拒答和来源追踪
- 问答会话保存与刷新恢复
- 流式显示回答、检索阶段提示和停止生成
- 资料管理、知识问答和知识自测页面
- 基于知识库生成练习题，记录错题并支持重新练习

## 核心流程

```text
上传资料 → 创建持久化入库任务 → 解析与验重 → 文本切分 → 建立索引 → 资料变为 READY

用户提问 → 向量与关键词检索 → RRF 融合并限制候选 → 模型重排 → Agent 判断证据
        → 必要时改写问题并再次检索 → 回答或拒答 → 保存会话

选择复习主题 → 基于知识库出题 → 作答与评分 → 错题重练 → 通过后移出错题
```

检索的两路结果用 RRF（Reciprocal Rank Fusion）融合：每路命中的片段贡献 `1 / (60 + 排名)`，排名从 1 开始，同一片段命中两路时累加贡献。按 `documentId + chunkIndex` 去重，同一路重复片段只计一次，并按去重后的排名计分；同分时按资料 ID、分块序号排序。它使用排名，不直接相加向量相似度与关键词相似度。[算法参考](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)。

每路最多取 `top-k × 2` 个候选，融合后也只保留 `top-k × 2` 个，再送给原有模型重排序。默认 `top-k=5`，因此重排序最多接收 10 个片段，而不是原来的两路合并后最多 20 个。RRF 分数只用于候选筛选；最终 `SearchResult.score` 仍是模型重排序的相关性分数。融合不增加模型调用，不改变问答接口、引用来源和上下文长度限制；是否改善真实回答质量仍需另外评测。

入库任务会保存解析、索引阶段和数据库租约。独立心跳在处理期间续租；服务异常退出后，过期任务会被重新领取并从最近的安全阶段继续执行。解析和向量计算在事务外完成，正文或索引的最终提交先锁定任务行并检查有效租约，再与资料/任务状态一起提交或回滚；已被接手或取消的旧 Worker 不能写入迟到结果。

所有资料处理都经过有界线程池。删除、编辑、替换和重试先锁定并失效旧任务，提交后才唤醒 Worker。索引按资料 ID 在事务中替换，并使用稳定分块 ID，恢复执行不会留下重复片段。模型调用可能重做，不保证只调用一次。

默认租约为 15 分钟，心跳间隔为 30 秒，向量请求每批最多 32 个分块；配置位于 `app.ingestion`。心跳间隔必须小于租约时长，异常退出后的接手需要等待剩余租期。详细流程和测试说明见 [入库并发安全设计](docs/ingestion-safety.md)。

问答页使用 `POST /api/chat/stream` 接收 SSE：`status` 表示实际处理阶段（检索、判断证据、二次检索、生成），`delta` 是模型实时返回的文本，`done` 包含已保存的完整回答和来源，`error` 表示生成失败。阶段提示不是模型内部思维链。普通 `POST /api/chat` 仍保持原有行为。

流式问答在同一后端进程中一次只处理 1 个请求，不支持多请求同时生成或等待排队；忙时直接返回 503，提示稍后重试。前端仍然一次只发送一个问题。连接超时为 5 分钟。停止、断线或超时被服务端观察到后会取消任务并关闭模型流订阅；生成过程中失败不会保存半截回答，页面会标明“未完成”。断线只能在容器通知或下一次写入时被发现，且取消不保证模型供应商立即停止计费。完整生成才保存会话，但保存成功后若连接断开，客户端仍可能收不到 `done`；目前不做自动重试、断点续传或事件回放。

## 技术栈

- Java 17、Spring Boot、Maven
- PostgreSQL、pgvector、Spring Data JPA
- Spring AI
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

完整验证需要 Docker Desktop（或可用的 Docker Engine）。`mvn verify` 会自动启动并清理隔离的 PostgreSQL/pgvector 测试容器，不读取 `.env`，不使用业务数据库或真实模型 API；Docker 不可用时集成测试会失败，不会静默跳过。

```powershell
cd frontend
npm ci
npm test
npm run build
cd ..
mvn verify
```

只运行单元测试、不启动数据库容器：

```powershell
mvn test
```

真实数据库测试覆盖并发领取、跳过已锁任务、心跳续租、失效任务保护、索引与状态事务回滚、并发删除、编辑和文件替换失败。恢复测试会强制终止一个独立 JVM，再启动新 JVM，从持久化的 `INDEXING` 阶段继续并验证没有重复索引。这验证的是应用进程异常退出，不包含数据库磁盘损坏或原文件丢失。

流式测试覆盖模型流逐段转发、订阅取消、SSE 超时、单请求执行与不排队、完整回答保存，以及前端汉字分块解码、提前显示、异常和停止生成。使用测试模型，不消耗真实 API Token。

检索融合测试覆盖两路排名累计、跨路与单路去重、单路或空结果、同分排序、候选上限、实际检索接线，以及最终分数仍来自模型重排序。

前端开发时也可以在 `frontend` 目录运行 `npm run dev`，然后访问 <http://localhost:5173>；Vite 会把 `/api` 请求转发到 8080 端口的 Spring Boot。GitHub Actions 会在创建 PR 和更新 `main` 时自动执行前端测试、构建前端并执行后端测试。

## 主要接口

| 方法 | 地址 | 作用 |
| --- | --- | --- |
| `GET` | `/api/health` | 健康检查 |
| `POST` | `/api/documents` | 上传文件 |
| `POST` | `/api/documents/notes` | 创建笔记 |
| `POST` | `/api/documents/links` | 收藏网页 |
| `GET` | `/api/documents` | 查询资料列表 |
| `GET` | `/api/documents/{id}` | 查询资料详情 |
| `PUT` | `/api/documents/{id}` | 更新资料或替换文件 |
| `POST` | `/api/documents/{id}/retry` | 重新处理失败资料 |
| `DELETE` | `/api/documents/{id}` | 删除资料 |
| `POST` | `/api/chat` | 知识库问答 |
| `POST` | `/api/chat/stream` | 流式问答与处理阶段事件 |
| `GET` | `/api/chat/{conversationId}` | 查询会话历史 |
| `POST` | `/api/practices` | 生成自测题 |
| `POST` | `/api/practices/{id}/answer` | 提交答案并评分 |
| `GET` | `/api/practices/mistakes` | 查询错题 |
| `POST` | `/api/practices/{id}/retry` | 重新练习错题 |

## 设计文档

- [产品需求文档（Issue #1）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/1)
- [产品架构设计（Issue #2）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/2)
- [资料库模块（Issue #55）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/55)
- [知识索引模块（Issue #60）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/60)
- [知识问答模块（Issue #67）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/67)
- [Vue 前端（Issue #74）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/74)
- [知识自测（Issue #89）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/89)
- [错题重练（Issue #91）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/91)
- [可恢复入库（Issue #95）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/95)
- [入库并发安全（Issue #97）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/97)
- [流式问答与阶段提示（Issue #100）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/100)
- [检索结果 RRF 融合（Issue #102）](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/102)
- [工程支持（Issue #24）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/24)

## 密钥安全

`.env` 已被 Git 忽略，不要把真实数据库密码或 API Key 写入代码、README 或 `.env.example`。

## License

本项目使用仓库中声明的开源许可证，详见 [LICENSE](LICENSE)。
