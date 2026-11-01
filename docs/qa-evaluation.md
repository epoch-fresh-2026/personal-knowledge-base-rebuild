# 第一轮问答评测

20 个用例覆盖定义、比较、步骤、综合、追问、历史干扰、部分依据、错误前提、冲突和无依据。

使用固定原文调用真实模型，检查问题规划、证据判断和回答；不连接业务数据库，不评测检索或分块。普通 `mvn test` 会跳过它，不消费模型 Token。

在运行环境设置 `RUN_LIVE_QA_EVAL=true` 和 `SILICONFLOW_API_KEY`，可选设置 `CHAT_MODEL`、`SILICONFLOW_BASE_URL`，执行：

```powershell
mvn test "-Dtest=LiveQaEvaluationTest"
```

少量试跑或重复检查稳定性：

```powershell
mvn test "-Dtest=LiveQaEvaluationTest" "-Dqa.eval.limit=5" "-Dqa.eval.repeat=2"
```

结果在 `target/qa-evaluation.json`。测试自动检查是否应该回答；按 `expectedPoints` 人工核对事实、引用及缺口说明，不能把判答通过率当作答案准确率。每次改动用同一批用例比较。

这是测试代码，不启动 Spring，所以不会自动读取 `.env`；密钥不要写进用例或报告。

2026-10-13 本轮固定证据评测：20 题中 18 题判答符合预期，复习建议和租约过期原因仍被误拒答。部分生成答案仍有引用遗漏或原文之外的扩展；这不是整体准确率，后续继续使用这些用例对照。
