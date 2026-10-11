-- ddl-auto=update 会增加摘要字段，但旧数据库的枚举 CHECK 需要允许新阶段。
-- 同一个事务持有表锁，避免多个服务同时启动时交错修改约束。
BEGIN;
ALTER TABLE IF EXISTS ingestion_job DROP CONSTRAINT IF EXISTS ingestion_job_stage_check;
ALTER TABLE IF EXISTS ingestion_job ADD CONSTRAINT ingestion_job_stage_check
    CHECK (stage IN ('EXTRACTING', 'INDEXING', 'SUMMARIZING', 'COMPLETED'));
COMMIT;
