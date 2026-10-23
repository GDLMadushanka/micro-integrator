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

-- MFT_JOB.STATUS is one of RUNNING, DONE, FAILED, INTERRUPTED, PAUSED or CANCELLED, and the last three
-- are deliberately distinct from FAILED. INTERRUPTED means the process died under the run; PAUSED and
-- CANCELLED mean an operator asked it to stop, keeping or discarding its state respectively. All three
-- reach the framework's unwind as an exception, because that is how a run aborts instead of committing
-- -- so recording them as FAILED would be easy and would make this row contradict MFT_TRANSFER.
--
-- INVOKER_NAME is an invocation PATH, not a name, and is wide enough for one: every artifact a run came
-- through, joined by ' -> '. A synchronous run has one segment (the inbound endpoint, API or proxy); a
-- queued one has three -- the submitting artifact, the store it waited in, and the processor that ran
-- it -- and none of the three on its own answers "where did this come from".
--
-- One column rather than three, because the number of artifacts in front of a run is not fixed and a
-- schema that fixed it at three would be wrong for the first topology with two or four. Filtering is
-- therefore a substring pre-filter in SQL refined to segment equality in Java: LIKE '%api1%' alone
-- would answer for 'api10'.

CREATE TABLE IF NOT EXISTS MFT_JOB (
  RUN_ID VARCHAR(255) NOT NULL,
  SEQUENCE_NAME VARCHAR(255) NOT NULL,
  INVOKER_TYPE VARCHAR(16),
  INVOKER_NAME VARCHAR(1024),
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
  ERROR_MESSAGE CLOB,
  FAILED_STAGE VARCHAR(255),
  RETRYABLE SMALLINT,
  CREATED_AT BIGINT NOT NULL,
  STARTED_AT BIGINT,
  FIRST_STARTED_AT BIGINT,
  WORKING_MILLIS BIGINT,
  ENDED_AT BIGINT,
  UPDATED_AT BIGINT,
  PRIMARY KEY (RUN_ID)
);

CREATE TABLE IF NOT EXISTS MFT_JOB_STAGE (
  RUN_ID VARCHAR(255) NOT NULL,
  STAGE_NAME VARCHAR(255) NOT NULL,
  STAGE_INDEX INT NOT NULL,
  STATUS VARCHAR(16) NOT NULL,
  BYTES_IN BIGINT,
  BYTES_OUT BIGINT,
  SELF_NANOS BIGINT,
  ACTIVITY VARCHAR(1024),
  MEDIATION_NANOS BIGINT,
  UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (RUN_ID, STAGE_NAME)
);

CREATE TABLE IF NOT EXISTS MFT_CHECKPOINT (
  RUN_ID VARCHAR(255) NOT NULL,
  STAGE_NAME VARCHAR(255) NOT NULL,
  POSITION_VALUE BIGINT NOT NULL,
  POSITION_UNIT VARCHAR(16) NOT NULL,
  ARTIFACT_LENGTH BIGINT NOT NULL,
  FORMAT_VERSION VARCHAR(64) NOT NULL,
  PAYLOAD CLOB,
  UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (RUN_ID, STAGE_NAME)
);

-- MFT_JOB_STAGE is the per-stage accounting a run leaves behind: bytes through each stage and the time
-- spent in it, read by GET /management/mft/runs/{runId}/stages. Every row is written at the END of the
-- run, all stages together -- a pull pipeline has no moment where one stage finishes and the next
-- starts, because everything upstream executes inside the sink's consume(). So this table is empty
-- while a transfer runs and complete afterwards, and it is an accounting record rather than a progress
-- feed. SELF_NANOS is wall clock, not CPU: a GC pause or a throttled service lands in the stage's time.
--
-- MEDIATION_NANOS splits SELF_NANOS in two. streaming.forEach is the only operator that hands control
-- to a deployer-written sequence, so it is the only stage whose time is partly someone else's: this
-- column is the part spent inside that sequence, and SELF_NANOS minus it is the framing, buffering and
-- checkpointing the operator did itself. NULL for every other stage, which is different from 0 -- zero
-- would claim a sequence ran instantly rather than that there was no sequence.
--
-- One live checkpoint per stage, so writing one is an overwriting update rather than an append. A
-- history would be needed only if the artifact could be shorter than the recorded length, which the
-- mandatory per-record flush rules out.
--
-- No secondary indexes are created here, deliberately: CREATE INDEX is not idempotent on MySQL,
-- Oracle or DB2, and re-running this script is meant to be safe. Two queries will want one once row
-- counts justify it, and both are a deployment-time tuning decision rather than something to bake in:
--
--   recovery  SELECT ... FROM MFT_JOB WHERE STATUS = 'RUNNING' AND OWNER_NODE = ?
--   listing   SELECT ... FROM MFT_JOB WHERE SEQUENCE_NAME = ? ORDER BY CREATED_AT DESC
--   by invoker SELECT ... FROM MFT_JOB WHERE INVOKER_NAME LIKE ?  -- see below; the LIKE is
--             a pre-filter only, and no index helps a leading wildcard

-- MFT_TRANSFER: what was submitted, as opposed to what ran.
--
-- A submission and a run are different things with different lifetimes. A submission may never become
-- a run -- cancelled, or still waiting -- and most runs have no submission, because a synchronous
-- transfer never passed through here. RUN_ID is the join, written when the run starts.
--
-- JOB_ID is what the submitter is handed and what they quote afterwards. It is an auto-increment
-- because a number is something a person can read back over the phone; the run it names is 'mft-<id>'.
--
-- PARAMETERS is what the submitter supplied, as JSON: the values the pipeline's operators read, such
-- as a connection NAME and a path. Never a URL with a credential in it -- this column is listed over
-- the management API and printed in logs.
--
-- There is deliberately no PARAMETERS_VERSION beside it. One existed, written as a constant and read by
-- nothing, which is worse than none: it advertises a compatibility story the code does not implement.
-- Add one back together with the reader that acts on it.
--
-- Two guards against accidental duplicates, with deliberately different strengths:
--   IDEMPOTENCY_KEY is enforced. A submission without one gets a value unique to itself rather than
--   NULL, because what a unique constraint does with NULLs differs across these seven dialects more
--   than anything else in this schema.
--   PARAMS_FINGERPRINT is best effort: an identical submission joins one that is still QUEUED or
--   RUNNING. Two exactly simultaneous submissions may both be queued, which is simply the ordinary
--   rule -- one transfer per submission.
--
-- SUBMITTER is the artifact a transfer was submitted through -- an API, a proxy, an inbound endpoint.
-- Captured at submission because it cannot be recovered afterwards: whatever submitted the transfer
-- answered its own caller long before a processor claimed the row, and is on no thread the run can see.
-- Joined to the store and the processor at claim time to give MFT_JOB.INVOKER_NAME the whole path.
-- NEXT_ATTEMPT_AT holds a retry back. A requeued transfer that were claimable at once would spin
-- against whatever failed it, on every node at once.


CREATE TABLE IF NOT EXISTS MFT_TRANSFER (
  JOB_ID BIGINT AUTO_INCREMENT PRIMARY KEY,
  STORE_NAME VARCHAR(255) NOT NULL,
  IDEMPOTENCY_KEY VARCHAR(255) NOT NULL,
  PARAMS_FINGERPRINT VARCHAR(64) NOT NULL,
  PARAMETERS CLOB,
  STATUS VARCHAR(16) NOT NULL,
  ATTEMPT INT NOT NULL,
  NEXT_ATTEMPT_AT BIGINT,
  OWNER_NODE VARCHAR(255),
  RUN_ID VARCHAR(255),
  SUBMITTER VARCHAR(255),
  ERROR_CODE VARCHAR(64),
  ERROR_MESSAGE CLOB,
  CREATED_AT BIGINT NOT NULL,
  UPDATED_AT BIGINT,
  ENDED_AT BIGINT
);

CREATE INDEX IF NOT EXISTS MFT_TRANSFER_CLAIM_IDX ON MFT_TRANSFER (STORE_NAME, STATUS, NEXT_ATTEMPT_AT);
CREATE INDEX IF NOT EXISTS MFT_TRANSFER_SAME_IDX ON MFT_TRANSFER (STORE_NAME, PARAMS_FINGERPRINT, STATUS);
CREATE UNIQUE INDEX IF NOT EXISTS MFT_TRANSFER_IDEM_IDX ON MFT_TRANSFER (STORE_NAME, IDEMPOTENCY_KEY);
