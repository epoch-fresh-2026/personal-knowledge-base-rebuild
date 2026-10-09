package com.ithwx.personalknowledgebase.library.application;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/** 由集成测试启动的独立 JVM，使用生产 Worker；只连接测试容器。 */
public final class IngestionRecoveryProcess {
    public static void main(String[] args) throws Exception {
        boolean crash = "crash".equals(args[3]);
        try (var context = IngestionTestApplication.open(args[0], args[1], args[2], crash)) {
            ProcessDocument worker = context.getBean(ProcessDocument.class);
            if (crash) {
                worker.processNext(); // 在模型替身处阻塞，父进程强制终止，不执行 shutdown hook。
                throw new IllegalStateException("故障进程未到达阻塞点");
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            while (System.nanoTime() < deadline) {
                worker.processNext();
                if (jdbc.queryForObject("SELECT count(*) FROM ingestion_job WHERE status = 'COMPLETED'", Integer.class) == 1) {
                    System.out.println("RECOVERY_COMPLETE");
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("未能在租约到期后恢复任务");
        }
    }
}
