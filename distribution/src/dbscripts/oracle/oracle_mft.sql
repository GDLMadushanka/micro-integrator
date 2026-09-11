-- Managed File Transfer: job, per-stage and checkpoint state.
--
-- Times are epoch milliseconds in NUMBER(19), matching the cluster and task tables rather than each
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

CREATE TABLE MFT_JOB (
  RUN_ID VARCHAR2(255) NOT NULL,
  PIPELINE_NAME VARCHAR2(255) NOT NULL,
  INVOKER_TYPE VARCHAR2(16),
  INVOKER_NAME VARCHAR2(255),
  STATUS VARCHAR2(16) NOT NULL,
  OWNER_NODE VARCHAR2(255),
  RESUMABLE NUMBER(1),
  ATTEMPT NUMBER(10) NOT NULL,
  WORKSPACE_PATH VARCHAR2(1024),
  SOURCE_ID VARCHAR2(1024),
  SOURCE_SIZE NUMBER(19),
  SOURCE_LAST_MODIFIED NUMBER(19),
  CURRENT_STAGE VARCHAR2(255),
  BYTES_DONE NUMBER(19),
  RECORDS_DONE NUMBER(19),
  ERROR_CODE VARCHAR2(64),
  ERROR_MESSAGE CLOB,
  FAILED_STAGE VARCHAR2(255),
  RETRYABLE NUMBER(1),
  CREATED_AT NUMBER(19) NOT NULL,
  STARTED_AT NUMBER(19),
  ENDED_AT NUMBER(19),
  UPDATED_AT NUMBER(19),
  PRIMARY KEY (RUN_ID)
);

CREATE TABLE MFT_JOB_STAGE (
  RUN_ID VARCHAR2(255) NOT NULL,
  STAGE_NAME VARCHAR2(255) NOT NULL,
  STAGE_INDEX NUMBER(10) NOT NULL,
  STATUS VARCHAR2(16) NOT NULL,
  BYTES_IN NUMBER(19),
  BYTES_OUT NUMBER(19),
  SELF_NANOS NUMBER(19),
  UPDATED_AT NUMBER(19) NOT NULL,
  PRIMARY KEY (RUN_ID, STAGE_NAME)
);

CREATE TABLE MFT_CHECKPOINT (
  RUN_ID VARCHAR2(255) NOT NULL,
  STAGE_NAME VARCHAR2(255) NOT NULL,
  POSITION_VALUE NUMBER(19) NOT NULL,
  POSITION_UNIT VARCHAR2(16) NOT NULL,
  ARTIFACT_LENGTH NUMBER(19) NOT NULL,
  FORMAT_VERSION VARCHAR2(64) NOT NULL,
  PAYLOAD CLOB,
  UPDATED_AT NUMBER(19) NOT NULL,
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
