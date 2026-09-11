-- Managed File Transfer: job, per-stage and checkpoint state.
--
-- Times are epoch milliseconds in BIGINT, matching the cluster and task tables rather than each
-- dialect's own date type, so a value means the same thing everywhere it is read.
--
-- There is deliberately **no heartbeat column**. Liveness comes from cluster membership, and a run
-- whose node died is recognised by that node itself at startup, matching on OWNER_NODE -- see
-- ADR-0028. UPDATED_AT records progress and must never be read as a liveness signal.
--
-- MFT_CHECKPOINT is the resume path. Its row is rewritten once per checkpoint unit, which is the
-- hottest write in the system and the fsync ceiling that maxReprocessed exists to manage, so every
-- column that decides WHERE to resume is fixed-width: POSITION_VALUE, ARTIFACT_LENGTH, POSITION_UNIT.
--
-- PAYLOAD is the one exception, and an earlier version of this comment argued against having it. The
-- argument was that opaque state should arrive as a new fixed-width column plus a FORMAT_VERSION bump.
-- That does not survive the case it has to serve: a single integer cannot express a STATE MACHINE. An
-- external merge sort killed during its split phase must resume reading input; killed during its merge
-- phase it must not read the input at all -- and both present as the same position with a zero
-- artifact length. Inferring which from the files on disk is a chain of assumptions that breaks as
-- soon as chunks are sized by memory rather than row count, and guessing "merge" when it was still
-- splitting silently drops every row not yet written.
--
-- So it is operator-owned, and bounded rather than unbounded: capped at 4096 bytes of UTF-8, enforced
-- in Java, and an over-long payload is REFUSED rather than trimmed. A trimmed payload is not a smaller
-- payload, it is usually invalid JSON that reads back as a corrupt checkpoint far from here -- which is
-- Spring Batch's SHORT_CONTEXT defect exactly. Bytes and not characters, because a character cap is
-- meaningless for multi-byte text, which is the same trap from the other end.
--
-- A NULL payload costs nothing, which is why the expensive column and the hot path do not overlap: the
-- operators that checkpoint per record store NULL here, and the ones that store a payload checkpoint
-- every few thousand units.
--
-- Keep it primitive. Developer-owned format should mean a small map or a short string, never an object
-- graph whose class shape has to survive a connector upgrade.
--
-- The job's ERROR_MESSAGE is long text for the opposite reason: it is written once, at the end of a
-- run, and a size-constrained column that truncates silently loses the diagnosis.

IF NOT EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[MFT_JOB]') AND TYPE IN (N'U'))
CREATE TABLE MFT_JOB (
  RUN_ID VARCHAR(255) NOT NULL,
  PIPELINE_NAME VARCHAR(255) NOT NULL,
  INVOKER_TYPE VARCHAR(16),
  INVOKER_NAME VARCHAR(255),
  STATUS VARCHAR(16) NOT NULL,
  OWNER_NODE VARCHAR(255),
  RESUMABLE SMALLINT,
  ATTEMPT INT NOT NULL,
  WORKSPACE_PATH VARCHAR(1024),
  SOURCE_ID VARCHAR(1024),
  SOURCE_SIZE BIGINT,
  SOURCE_LAST_MODIFIED BIGINT,
  CURRENT_STAGE VARCHAR(255),
  BYTES_DONE BIGINT,
  RECORDS_DONE BIGINT,
  ERROR_CODE VARCHAR(64),
  ERROR_MESSAGE VARCHAR(MAX),
  FAILED_STAGE VARCHAR(255),
  RETRYABLE SMALLINT,
  CREATED_AT BIGINT NOT NULL,
  STARTED_AT BIGINT,
  ENDED_AT BIGINT,
  UPDATED_AT BIGINT,
  PRIMARY KEY (RUN_ID)
);

IF NOT EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[MFT_JOB_STAGE]') AND TYPE IN (N'U'))
CREATE TABLE MFT_JOB_STAGE (
  RUN_ID VARCHAR(255) NOT NULL,
  STAGE_NAME VARCHAR(255) NOT NULL,
  STAGE_INDEX INT NOT NULL,
  STATUS VARCHAR(16) NOT NULL,
  BYTES_IN BIGINT,
  BYTES_OUT BIGINT,
  SELF_NANOS BIGINT,
  UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (RUN_ID, STAGE_NAME)
);

IF NOT EXISTS (SELECT * FROM SYS.OBJECTS WHERE OBJECT_ID = OBJECT_ID(N'[DBO].[MFT_CHECKPOINT]') AND TYPE IN (N'U'))
CREATE TABLE MFT_CHECKPOINT (
  RUN_ID VARCHAR(255) NOT NULL,
  STAGE_NAME VARCHAR(255) NOT NULL,
  POSITION_VALUE BIGINT NOT NULL,
  POSITION_UNIT VARCHAR(16) NOT NULL,
  ARTIFACT_LENGTH BIGINT NOT NULL,
  FORMAT_VERSION VARCHAR(64) NOT NULL,
  PAYLOAD VARCHAR(MAX),
  UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (RUN_ID, STAGE_NAME)
);

-- One live checkpoint per stage, so writing one is an overwriting update rather than an append. A
-- history would be needed only if the artifact could be shorter than the recorded length, which the
-- mandatory per-record flush rules out.
--
-- No secondary indexes are created here, deliberately: CREATE INDEX is not idempotent on MySQL,
-- Oracle or DB2, and re-running this script is meant to be safe. Two queries will want one once row
-- counts justify it, and both are a deployment-time tuning decision rather than something to bake in:
--
--   recovery  SELECT ... FROM MFT_JOB WHERE STATUS = 'RUNNING' AND OWNER_NODE = ?
--   listing   SELECT ... FROM MFT_JOB WHERE PIPELINE_NAME = ? ORDER BY CREATED_AT DESC
--   by source SELECT ... FROM MFT_JOB WHERE INVOKER_TYPE = ? AND INVOKER_NAME = ?
