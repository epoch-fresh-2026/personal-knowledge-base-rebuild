# 个人知识库

一个面向大学生期末复习的 RAG 知识库。

学生可以录入课程讲义、学习笔记和参考网页，用自然语言查找知识，并通过回答来源核对原文。资料中没有足够依据时，系统会明确拒答。

## 功能

- 解析 TXT、Markdown、PDF 和 DOCX
- 录入学习笔记和公开参考网页
- 文本切分、向量化和 pgvector 存储
- 资料查询、修改、替换和删除
- 混合检索、模型重排、Agent 按需二次检索、无依据拒答和来源追踪
- 问答会话保存与刷新恢复
- 资料管理、知识问答和知识自测页面
- 基于知识库生成练习题，记录错题并支持重新练习

## 核心流程

```text
上传资料 → 创建持久化入库任务 → 解析与验重 → 文本切分 → 建立索引 → 资料变为 READY

用户提问 → 混合检索 → 模型重排 → Agent 判断证据
        → 必要时改写问题并再次检索 → 回答或拒答 → 保存会话

选择复习主题 → 基于知识库出题 → 作答与评分 → 错题重练 → 通过后移出错题
```

入库任务会保存解析、索引阶段和数据库租约。服务异常退出后，过期任务会被重新领取并从最近的安全阶段继续执行；索引按资料 ID 替换，重复执行不会留下重复片段。

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

```powershell
cd frontend
npm ci
npm run build
cd ..
mvn verify
```

前端开发时也可以在 `frontend` 目录运行 `npm run dev`，然后访问 <http://localhost:5173>；Vite 会把 `/api` 请求转发到 8080 端口的 Spring Boot。GitHub Actions 会在创建 PR 和更新 `main` 时自动构建前端并执行后端测试。

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
- [工程支持（Issue #24）](https://github.com/haiwangxing6666-a11y/personal-knowledge-base-rebuild/issues/24)

## 密钥安全

`.env` 已被 Git 忽略，不要把真实数据库密码或 API Key 写入代码、README 或 `.env.example`。

## License

本项目使用仓库中声明的开源许可证，详见 [LICENSE](LICENSE)。
