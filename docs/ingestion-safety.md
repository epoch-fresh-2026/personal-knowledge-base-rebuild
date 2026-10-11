# 入库安全说明

关联任务：#95、#97、#104；关键概念与相关资料：[#106](https://github.com/epoch-fresh-2026/personal-knowledge-base-rebuild/issues/106)。均属于资料库模块 #55。

## 处理流程

```text
EXTRACTING → INDEXING → SUMMARIZING → COMPLETED
解析正文     建立索引    生成并保存摘要    任务完成
```

索引提交后资料即为 READY，可参与检索，不必等摘要生成。总结同时提取关键概念，与摘要一起通过租约检查后保存。

## 并发与提交

- 任务保存在数据库，用 `FOR UPDATE SKIP LOCKED` 领取，避免同时领取同一任务。
- 解析、向量计算和总结在事务外执行；`IngestionCommitter` 先锁任务、检查负责人及租约，再在短事务中提交结果和状态。
- 心跳负责续租；过期、被接手或取消的 Worker 不能写回结果。
- 索引按资料 ID 替换，分块 ID 稳定；索引和资料/任务状态一起提交或回滚。
- 删除、编辑和替换先锁任务并使旧任务失效，阻止迟到结果写回。
- 文档概念随资料删改清除，关联按当前共同概念查询，不保存过期的关联边。
- 总结失败保留资料 READY 和原索引；重试摘要不重建索引，重复请求不抢走已有总结任务的租约。

## 配置与恢复

配置位于 `app.ingestion`：

| 配置 | 默认值 |
| --- | --- |
| `lease-duration` | 15 分钟 |
| `heartbeat-interval` | 30 秒，须小于租期 |
| `poll-interval` | 1 秒 |
| `worker.core-pool-size / max-pool-size` | 2 / 4 |
| `worker.queue-capacity` | 20 |
| `embedding-batch-size` | 32 |

事务提交后才唤醒工作线程。线程池繁忙时，任务保留在数据库，由后续轮询领取。

重启后等待剩余租约过期，再从已保存阶段恢复：解析阶段可能重新解析，索引阶段重算向量，总结阶段只重做总结。失败任务需要手动重试；模型调用可能重复，也不能保证已发出的请求立即停止计费。

恢复要求数据库和原文件仍可用，多实例需同步时钟和时区。启动时 `schema.sql` 更新任务阶段约束，`ddl-auto=update` 增加摘要字段；旧 READY 资料不自动补生成摘要。

## 验证

`mvn test` 运行单元测试；`mvn verify` 额外运行隔离 PostgreSQL/pgvector 集成测试，需要 Docker，使用模型替身。

测试覆盖任务领取、心跳、事务回滚、删改后的迟到结果保护，以及强制终止 JVM 后的索引/总结恢复。不包含数据库损坏或原文件丢失。
