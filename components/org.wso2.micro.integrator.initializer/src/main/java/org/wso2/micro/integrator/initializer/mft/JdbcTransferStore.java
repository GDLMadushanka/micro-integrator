/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.micro.integrator.initializer.mft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.stream.StreamException;
import org.apache.synapse.stream.Submission;
import org.apache.synapse.stream.Transfer;
import org.apache.synapse.stream.TransferStatus;
import org.apache.synapse.stream.TransferStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Submitted transfers, in MFT_TRANSFER.
 *
 * <h2>Why this is not the job table</h2>
 * A submission and a run are different things. A submission may never become a run — cancelled, or
 * still waiting — and most runs have no submission at all, because a synchronous transfer never passed
 * through a queue. Sharing one table meant four columns that were null on the majority of rows.
 * {@code RUN_ID} is the join, written by {@link #linkRun} when the run starts, and both tables live in
 * the same datasource so the two writes a claim causes are one commit away from each other.
 *
 * <h2>The claim is the affected-row count</h2>
 * {@code UPDATE … WHERE JOB_ID = ? AND STATUS = 'QUEUED'} returns 1 to exactly one caller, whatever else
 * is polling. No {@code SELECT … FOR UPDATE}, and deliberately not the shape
 * {@code JDBCMessageStore.peek()} uses — that selects {@code min(indexId)} with no locking at all, which
 * is why its consumers avoid concurrency rather than surviving it.
 *
 * <p>The select that finds candidates is therefore only a hint: between reading one and claiming it,
 * another node may take it. Losing that race is ordinary, so this tries the next candidate rather than
 * failing the poll.
 */
final class JdbcTransferStore implements TransferStore {

    private static final Log LOG = LogFactory.getLog(JdbcTransferStore.class);

    /** How many candidates one {@link #claim} will try before giving the poll up as contended. */
    private static final int CLAIM_ATTEMPTS = 5;

    private static final String COLUMNS =
            "JOB_ID, STORE_NAME, PARAMETERS, STATUS, ATTEMPT, NEXT_ATTEMPT_AT, RUN_ID, SUBMITTER,"
                    + " ERROR_CODE, ERROR_MESSAGE, CREATED_AT, UPDATED_AT, ENDED_AT";

    private static final String INSERT =
            "INSERT INTO MFT_TRANSFER (STORE_NAME, IDEMPOTENCY_KEY, PARAMS_FINGERPRINT, PARAMETERS,"
                    + " STATUS, ATTEMPT, NEXT_ATTEMPT_AT, SUBMITTER, CREATED_AT, UPDATED_AT)"
                    + " VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)";

    /**
     * The fingerprint comes back with the id, so a key that was reused for <i>different</i> values can
     * be told apart from one that was genuinely resubmitted.
     */
    private static final String SELECT_BY_IDEMPOTENCY_KEY =
            "SELECT JOB_ID, PARAMS_FINGERPRINT FROM MFT_TRANSFER"
                    + " WHERE STORE_NAME = ? AND IDEMPOTENCY_KEY = ?";

    /**
     * Only an in-flight twin collapses; a finished one must not stop the same transfer being re-sent.
     *
     * <p>The list is {@code TransferStatus.isInFlight()} written out, and the paused and winding-down
     * states belong in it: such a transfer still exists and still owns a workspace under its run id,
     * so a second submission of the same values must join it rather than start a rival transfer that
     * would resume into the same directory.
     */
    private static final String SELECT_IN_FLIGHT_TWIN =
            "SELECT " + COLUMNS + " FROM MFT_TRANSFER WHERE STORE_NAME = ? AND PARAMS_FINGERPRINT = ?"
                    + " AND STATUS IN ('QUEUED', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING')"
                    + " ORDER BY JOB_ID";

    private static final String SELECT_CANDIDATES =
            "SELECT " + COLUMNS + " FROM MFT_TRANSFER WHERE STORE_NAME = ? AND STATUS = ?"
                    + " AND (NEXT_ATTEMPT_AT IS NULL OR NEXT_ATTEMPT_AT <= ?) ORDER BY JOB_ID";

    private static final String CLAIM_ONE =
            "UPDATE MFT_TRANSFER SET STATUS = ?, ATTEMPT = ATTEMPT + 1, OWNER_NODE = ?, UPDATED_AT = ?"
                    + " WHERE JOB_ID = ? AND STATUS = ?";

    private static final String LINK_RUN =
            "UPDATE MFT_TRANSFER SET RUN_ID = ?, UPDATED_AT = ? WHERE JOB_ID = ?";

    private static final String REQUEUE =
            "UPDATE MFT_TRANSFER SET STATUS = ?, OWNER_NODE = NULL, NEXT_ATTEMPT_AT = ?,"
                    + " UPDATED_AT = ? WHERE JOB_ID = ? AND STATUS <> ?";

    private static final String FINISH =
            "UPDATE MFT_TRANSFER SET STATUS = ?, ERROR_CODE = ?, ERROR_MESSAGE = ?, OWNER_NODE = NULL,"
                    + " ENDED_AT = ?, UPDATED_AT = ? WHERE JOB_ID = ?";

    /**
     * The pause and stop requests, and the two resting states a run writes when it has wound down.
     *
     * <p>Each is conditional on the status it expects, so a request that races the transfer's own
     * progress loses rather than overwriting it: asking a transfer to pause at the moment it finishes
     * must not resurrect it as PAUSED, and the affected-row count is what says which happened.
     *
     * <p>A running transfer goes to the winding-down state; one that has not started goes straight to
     * the resting one, because there is nothing to wind down and the request is already satisfied.
     */
    private static final String REQUEST_WHILE_RUNNING =
            "UPDATE MFT_TRANSFER SET STATUS = ?, UPDATED_AT = ? WHERE JOB_ID = ? AND STATUS = ?";

    private static final String REQUEST_WHILE_QUEUED =
            "UPDATE MFT_TRANSFER SET STATUS = ?, OWNER_NODE = NULL, ENDED_AT = ?, UPDATED_AT = ?"
                    + " WHERE JOB_ID = ? AND STATUS = ?";

    /**
     * What a stop of a transfer at rest has to find: the run it belongs to, and the directory that run
     * left behind.
     *
     * <p>A {@code LEFT JOIN} because a transfer can be stopped before any run row exists for it, and
     * the absence of one is not a failure -- it means there is nothing on disk to reclaim.
     */
    private static final String SELECT_STATE_TO_DISCARD =
            "SELECT t.RUN_ID, j.WORKSPACE_PATH FROM MFT_TRANSFER t"
                    + " LEFT JOIN MFT_JOB j ON j.RUN_ID = t.RUN_ID WHERE t.JOB_ID = ?";

    /** Run-scoped, unlike {@code JdbcCheckpointStore}'s, which clears one stage at a time. */
    private static final String DELETE_CHECKPOINTS =
            "DELETE FROM MFT_CHECKPOINT WHERE RUN_ID = ?";

    /** Written by the run itself, once it has actually stopped. */
    private static final String SETTLE =
            "UPDATE MFT_TRANSFER SET STATUS = ?, OWNER_NODE = NULL, ENDED_AT = ?, UPDATED_AT = ?"
                    + " WHERE JOB_ID = ? AND STATUS = ?";

    /**
     * Back on the queue, claimable at once, attempt count reset.
     *
     * <p>{@code STATUS IN (?, ?)} rather than two statements: paused and failed are the two states
     * with kept state to resume from, and both answer the same request.
     */
    private static final String START =
            "UPDATE MFT_TRANSFER SET STATUS = ?, ATTEMPT = 0, OWNER_NODE = NULL, NEXT_ATTEMPT_AT = ?,"
                    + " ERROR_CODE = NULL, ERROR_MESSAGE = NULL, ENDED_AT = NULL, UPDATED_AT = ?"
                    + " WHERE JOB_ID = ? AND STATUS IN (?, ?)";

    private static final String CANCEL =
            "UPDATE MFT_TRANSFER SET STATUS = ?, ENDED_AT = ?, UPDATED_AT = ?"
                    + " WHERE JOB_ID = ? AND STATUS = ?";

    private static final String SELECT_ONE =
            "SELECT " + COLUMNS + " FROM MFT_TRANSFER WHERE JOB_ID = ?";

    private static final String COUNT_QUEUED =
            "SELECT COUNT(*) FROM MFT_TRANSFER WHERE STORE_NAME = ? AND STATUS = ?";

    /**
     * What this node was running when it died. Read before the requeue so it can be reported.
     *
     * <p>{@code PAUSING} and {@code STOPPING} are included: a node that died while winding down left
     * a row that nothing else will ever settle, and leaving it there is the same stranded-forever bug
     * the sweep exists to fix. They come back as ordinary requeues — a paused transfer that never
     * finished pausing is simply one that has not been paused yet.
     */
    private static final String SELECT_OWNED_RUNNING =
            "SELECT " + COLUMNS + " FROM MFT_TRANSFER"
                    + " WHERE STATUS IN ('RUNNING', 'PAUSING', 'STOPPING') AND OWNER_NODE = ?"
                    + " ORDER BY JOB_ID";

    /**
     * Puts them back. Claimable at once rather than after a back-off: a restart is not a failure of the
     * destination, and the retry bound still applies because the claim that stranded the transfer
     * already counted an attempt.
     */
    private static final String REQUEUE_OWNED =
            "UPDATE MFT_TRANSFER SET STATUS = ?, OWNER_NODE = NULL, NEXT_ATTEMPT_AT = ?,"
                    + " UPDATED_AT = ? WHERE STATUS IN ('RUNNING', 'PAUSING', 'STOPPING')"
                    + " AND OWNER_NODE = ?";

    /** Rows nothing can recover, counted so the operator is told rather than left to find out. */
    private static final String COUNT_UNOWNED_RUNNING =
            "SELECT COUNT(*) FROM MFT_TRANSFER WHERE STATUS = ? AND OWNER_NODE IS NULL";

    private final DataSource dataSource;

    /**
     * Resolved per claim rather than held, because the coordination component may publish this node's
     * id after MFT registered. Same reasoning as {@code JdbcJobStore}; see {@link MftNodeId}.
     */
    private final Supplier<String> ownerNode;

    /**
     * The run side of the same database, for the one case this store settles a run itself.
     *
     * <p>Normally a run writes its own ending -- it is the only party that knows it has stopped. A
     * transfer stopped while paused has no run to do it, so this store finishes the job the vanished
     * thread would have.
     */
    private final JdbcJobStore runs;

    JdbcTransferStore(DataSource dataSource, Supplier<String> ownerNode, JdbcJobStore runs) {
        this.dataSource = dataSource;
        this.ownerNode = ownerNode;
        this.runs = runs;
    }

    @Override
    public Submission submit(String storeName, Map<String, String> parameters, String idempotencyKey,
                             String submitter) throws StreamException {

        long now = System.currentTimeMillis();
        Map<String, String> values = parameters == null ? Map.of() : parameters;
        String fingerprint = fingerprint(storeName, values);
        // A submission without a key gets one unique to itself rather than NULL: what a unique
        // constraint does with NULLs differs across the seven dialects this ships for more than
        // anything else in the schema, and a column that is never null has no such argument.
        boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();
        String key = keyed ? idempotencyKey.trim() : "auto-" + UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            // A key decides in BOTH directions, which is the whole of what it is for. The same key is
            // the same transfer, for ever and whatever became of it. A different key is a different
            // transfer even when every value matches -- so a caller who means "send that again, now,
            // alongside the one still running" can say so, and the fingerprint heuristic below does not
            // overrule them.
            if (keyed) {
                Submission existing = forKey(c, storeName, key, fingerprint);
                if (existing != null) {
                    return existing;
                }
            } else {
                // No key, so the caller has said nothing about sameness and the store guesses -- but
                // only within the window where a duplicate is almost certainly an accident. Once the
                // first is finished the same values queue a new transfer, because "send it again" must
                // stay possible without a feature.
                Transfer twin = one(c, SELECT_IN_FLIGHT_TWIN, storeName, fingerprint);
                if (twin != null) {
                    LOG.debug("transfer " + twin.jobId() + " is already in flight with the same values;"
                            + " the submission joined it");
                    return new Submission(twin.jobId(), false);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, storeName);
                ps.setString(2, key);
                ps.setString(3, fingerprint);
                ps.setString(4, encode(values));
                ps.setString(5, TransferStatus.QUEUED.name());
                ps.setLong(6, now);                 // claimable at once
                ps.setString(7, submitter);
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new StreamException("the transfer was stored but the database did not"
                                + " report its id, so there is nothing to hand back to the caller",
                                true);
                    }
                    long jobId = keys.getLong(1);
                    LOG.info("queued transfer " + jobId + " in message store '" + storeName + "'");
                    return new Submission(jobId, true);
                }
            }
        } catch (SQLException e) {
            // The key's guarantee is the unique constraint, not the SELECT above it: two simultaneous
            // submissions of one key both miss that read and both insert, and exactly one loses. Losing
            // is the guarantee working, so the loser reads the winner's id and reports it as a
            // resubmission -- which is what the caller asked for by supplying a key at all.
            if (keyed && isConstraintViolation(e)) {
                try (Connection c = dataSource.getConnection()) {
                    Submission existing = forKey(c, storeName, key, fingerprint);
                    if (existing != null) {
                        return existing;
                    }
                } catch (SQLException reread) {
                    e.addSuppressed(reread);
                }
            }
            // Thrown, never swallowed. A submission that was not stored must not look accepted: the
            // caller is about to be handed an id for a transfer nothing will ever run.
            throw new StreamException("could not submit a transfer to '" + storeName + "'", e, true);
        }
    }

    /**
     * Whether a failed write was a uniqueness violation rather than a broken database.
     *
     * <p>The same walk {@code JdbcJobStore} does, for the same reason: treating every {@code
     * SQLException} as a collision would swallow a missing table, a rejected column or an expired
     * credential, and then report a transfer as accepted that was never stored.
     */
    private static boolean isConstraintViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Transfer claim(String storeName) throws StreamException {
        long now = System.currentTimeMillis();
        // May be null on a node with no id. The transfer still runs -- refusing to claim would be a
        // worse answer than running something that cannot be recovered after a crash -- but the row is
        // then unrecoverable, and MftRecovery says so at startup rather than leaving it to be noticed.
        String owner = ownerNode.get();
        try (Connection c = dataSource.getConnection()) {
            for (Transfer candidate : read(c, SELECT_CANDIDATES, CLAIM_ATTEMPTS,
                    storeName, TransferStatus.QUEUED.name(), now)) {
                try (PreparedStatement ps = c.prepareStatement(CLAIM_ONE)) {
                    ps.setString(1, TransferStatus.RUNNING.name());
                    ps.setString(2, owner);
                    ps.setLong(3, now);
                    ps.setLong(4, candidate.jobId());
                    ps.setString(5, TransferStatus.QUEUED.name());
                    if (ps.executeUpdate() == 1) {
                        // ATTEMPT was incremented by the claim, so the transfer handed back says how
                        // many attempts this one is -- which is what the retry bound counts.
                        return withAttempt(candidate, candidate.attempt() + 1);
                    }
                }
                LOG.debug("transfer " + candidate.jobId() + " was claimed by another consumer");
            }
            return null;
        } catch (SQLException e) {
            throw new StreamException("could not claim a transfer from '" + storeName + "'", e, true);
        }
    }

    @Override
    public void linkRun(long jobId, String runId) throws StreamException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(LINK_RUN)) {
            ps.setString(1, runId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StreamException("could not record that transfer " + jobId + " is running as '"
                    + runId + "'", e, true);
        }
    }

    @Override
    public boolean requeue(long jobId, long notBefore) throws StreamException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(REQUEUE)) {
            ps.setString(1, TransferStatus.QUEUED.name());
            ps.setLong(2, notBefore);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, jobId);
            // Already queued means someone else requeued it; doing so again would only reset its place.
            ps.setString(5, TransferStatus.QUEUED.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StreamException("could not requeue transfer " + jobId, e, true);
        }
    }

    @Override
    public void finish(long jobId, TransferStatus status, String errorCode, String errorMessage)
            throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(FINISH)) {
            ps.setString(1, status.name());
            ps.setString(2, errorCode);
            ps.setString(3, errorMessage);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setLong(6, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StreamException("could not record transfer " + jobId + " as " + status, e, true);
        }
    }

    @Override
    public boolean cancel(long jobId) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(CANCEL)) {
            ps.setString(1, TransferStatus.CANCELLED.name());
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setLong(4, jobId);
            // Only a waiting transfer can be cancelled. One already claimed belongs to whoever is
            // running it, and marking it cancelled underneath them would describe a transfer that is
            // still moving bytes.
            ps.setString(5, TransferStatus.QUEUED.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StreamException("could not cancel transfer " + jobId, e, true);
        }
    }

    @Override
    public TransferStatus requestPause(long jobId) throws StreamException {
        return request(jobId, TransferStatus.PAUSING, TransferStatus.PAUSED, "pause");
    }

    @Override
    public TransferStatus requestStop(long jobId) throws StreamException {
        TransferStatus settled =
                request(jobId, TransferStatus.STOPPING, TransferStatus.CANCELLED, "stop");
        // Neither running nor queued. A PAUSED transfer is the third at-rest case, and it is the only
        // one with state to reclaim, so it cannot share the path above.
        return settled != null ? settled : stopWhilePaused(jobId);
    }

    /**
     * Stops a transfer that is already paused, and discards what it was keeping.
     *
     * <h2>Why this thread deletes the files</h2>
     * A running transfer's own thread discards its workspace on the way out, because only that thread
     * knows it has stopped writing (ADR-0028). A paused transfer has no thread: {@code PAUSED} is
     * written by the run itself <b>after</b> it unwound, so the row's existence proves the writer is
     * gone. That is the same kind of proof {@link MftRecovery} relies on at startup.
     *
     * <h2>Terminal first, then delete</h2>
     * The row goes straight to {@code CANCELLED} in one statement before anything is removed. Two
     * properties follow, and both are needed:
     *
     * <ul>
     *   <li>{@code CANCELLED} is unclaimable and {@code start} refuses it, so no new thread can appear
     *       to write into the workspace while it is being deleted.</li>
     *   <li>A crash midway leaves a correctly-stopped transfer and a leaked directory, which is the
     *       retention problem already on the roadmap. The other order would leave a transfer with no
     *       state that the startup sweep requeues as claimable -- and it would then re-transfer the
     *       file from the beginning, which is the one outcome a stop must not produce.</li>
     * </ul>
     *
     * <p>No {@code STOPPING} step, for the reason a queued transfer has none: there is nothing to wind
     * down, so the request is met the moment the row changes.
     *
     * @return {@code CANCELLED} if this call stopped it, or {@code null} if it was not paused
     */
    private TransferStatus stopWhilePaused(long jobId) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            // REQUEST_WHILE_QUEUED by shape as well as by name: at-rest state to terminal state, with
            // the owner released and an end time stamped. Only the status it tests differs.
            try (PreparedStatement ps = c.prepareStatement(REQUEST_WHILE_QUEUED)) {
                ps.setString(1, TransferStatus.CANCELLED.name());
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setLong(4, jobId);
                ps.setString(5, TransferStatus.PAUSED.name());
                if (ps.executeUpdate() != 1) {
                    // Not paused either: done, cancelled, already stopping, or no such row.
                    return null;
                }
            }

            String runId = null;
            String workspacePath = null;
            try (PreparedStatement ps = c.prepareStatement(SELECT_STATE_TO_DISCARD)) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        runId = rs.getString(1);
                        workspacePath = rs.getString(2);
                    }
                }
            }
            if (runId == null || runId.isBlank()) {
                LOG.info("transfer " + jobId + " was paused and is now stopped; it had no run, so"
                        + " there was nothing to discard");
                return TransferStatus.CANCELLED;
            }

            int checkpoints;
            try (PreparedStatement ps = c.prepareStatement(DELETE_CHECKPOINTS)) {
                ps.setString(1, runId);
                checkpoints = ps.executeUpdate();
            }
            boolean workspaceGone = MftWorkspaces.discard(workspacePath, runId, "stopped while paused");
            // The run row last, and through the job store so a stopped run is recorded the one way
            // (ADR-0052): both rows must agree, or a reader cannot tell which of them lied.
            runs.stopped(runId, true);

            LOG.info("transfer " + jobId + " was paused and is now stopped; run '" + runId + "' had "
                    + checkpoints + " checkpoint(s) cleared and its workspace "
                    + (workspaceGone ? "discarded" : "was already gone")
                    + ", so nothing can resume from it");
            return TransferStatus.CANCELLED;
        } catch (SQLException e) {
            throw new StreamException("could not stop paused transfer " + jobId, e, true);
        }
    }

    /**
     * @param windingDown what a running transfer becomes: it has to stop before the request is met
     * @param atRest      what a transfer that never started becomes: already met
     */
    private TransferStatus request(long jobId, TransferStatus windingDown, TransferStatus atRest,
                                   String what) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(REQUEST_WHILE_RUNNING)) {
                ps.setString(1, windingDown.name());
                ps.setLong(2, now);
                ps.setLong(3, jobId);
                ps.setString(4, TransferStatus.RUNNING.name());
                if (ps.executeUpdate() == 1) {
                    LOG.info("transfer " + jobId + " was asked to " + what + "; it is running, so it"
                            + " stops at its next unit");
                    return windingDown;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(REQUEST_WHILE_QUEUED)) {
                ps.setString(1, atRest.name());
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setLong(4, jobId);
                ps.setString(5, TransferStatus.QUEUED.name());
                if (ps.executeUpdate() == 1) {
                    LOG.info("transfer " + jobId + " was asked to " + what + " before it started, so"
                            + " it is " + atRest.name().toLowerCase(java.util.Locale.ENGLISH));
                    return atRest;
                }
            }
            // Neither: finished, already stopping, or no such row. Not an error -- the caller lost a
            // race with the transfer, and find() tells them what it actually is.
            return null;
        } catch (SQLException e) {
            throw new StreamException("could not ask transfer " + jobId + " to " + what, e, true);
        }
    }

    @Override
    public TransferStatus stopped(long jobId) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            for (TransferStatus[] pair : new TransferStatus[][] {
                    {TransferStatus.PAUSING, TransferStatus.PAUSED},
                    {TransferStatus.STOPPING, TransferStatus.CANCELLED}}) {
                try (PreparedStatement ps = c.prepareStatement(SETTLE)) {
                    ps.setString(1, pair[1].name());
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.setLong(4, jobId);
                    ps.setString(5, pair[0].name());
                    if (ps.executeUpdate() == 1) {
                        return pair[1];
                    }
                }
            }
            return null;        // nobody asked; the caller treats it as an ordinary failure
        } catch (SQLException e) {
            throw new StreamException("could not record that transfer " + jobId + " stopped", e, true);
        }
    }

    @Override
    public boolean start(long jobId) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(START)) {
            ps.setString(1, TransferStatus.QUEUED.name());
            ps.setLong(2, now);                 // claimable at once
            ps.setLong(3, now);
            ps.setLong(4, jobId);
            ps.setString(5, TransferStatus.PAUSED.name());
            ps.setString(6, TransferStatus.FAILED.name());
            boolean started = ps.executeUpdate() == 1;
            if (started) {
                LOG.info("transfer " + jobId + " is back on the queue and resumes from its last"
                        + " checkpoint when a processor claims it");
            }
            return started;
        } catch (SQLException e) {
            throw new StreamException("could not start transfer " + jobId, e, true);
        }
    }

    @Override
    public Transfer find(long jobId) throws StreamException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ONE)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? transferOf(rs) : null;
            }
        } catch (SQLException e) {
            throw new StreamException("could not read transfer " + jobId, e, true);
        }
    }

    @Override
    public List<Transfer> list(String storeName, String status, int limit) throws StreamException {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM MFT_TRANSFER");
        List<Object> arguments = new ArrayList<>();
        if (storeName != null) {
            sql.append(arguments.isEmpty() ? " WHERE" : " AND").append(" STORE_NAME = ?");
            arguments.add(storeName);
        }
        if (status != null) {
            sql.append(arguments.isEmpty() ? " WHERE" : " AND").append(" STATUS = ?");
            arguments.add(status);
        }
        sql.append(" ORDER BY JOB_ID DESC");

        try (Connection c = dataSource.getConnection()) {
            return read(c, sql.toString(), limit, arguments.toArray());
        } catch (SQLException e) {
            throw new StreamException("could not list transfers", e, true);
        }
    }

    @Override
    public int queued(String storeName) throws StreamException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(COUNT_QUEUED)) {
            ps.setString(1, storeName);
            ps.setString(2, TransferStatus.QUEUED.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StreamException("could not count the transfers waiting in '" + storeName + "'",
                    e, true);
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * The transfer already carrying this key, if there is one.
     *
     * <p>A key is the caller's assertion that two submissions are the same request. The same key with
     * <b>different values</b> contradicts that, and this refuses rather than choosing a side: returning
     * the first transfer would silently discard the values just submitted, and queuing a second would
     * make the key mean nothing. Neither is something the caller can see, and both are the kind of
     * quiet wrong answer that surfaces as "we never received that file".
     *
     * @return the existing transfer as a resubmission, or {@code null} if the key is new
     * @throws StreamException if the key was used for a different transfer. Not retryable: repeating
     *                         the request cannot fix a contradiction in it
     */
    /**
     * Puts back every transfer this node was running when it stopped existing.
     *
     * <p>A node that is starting cannot have a transfer in flight, so a {@code RUNNING} row it owns is
     * by construction from a process that no longer exists. That is certainty, not inference, which is
     * why this needs no timeout and no heartbeat (ADR-0028).
     *
     * <p>It requeues rather than marking a terminal state, because a submission's recovery <i>is</i>
     * being claimable again: the processor re-claims it, the run id is derived from the job id, and the
     * attempt therefore lands in the workspace the dead one left and resumes from its checkpoint. The
     * run row is a different thing and is marked, not requeued — see {@code MftRecovery}.
     *
     * @param owner this node's id, never null — a sweep with no owner would match rows belonging to
     *              every other node that also has none
     * @return the transfers put back, in id order; empty when the node died holding nothing
     * @throws StreamException if the store could not be reached
     */
    List<Transfer> reclaimOwned(String owner) throws StreamException {
        Objects.requireNonNull(owner, "a startup sweep must name the node whose rows it is reclaiming");
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            List<Transfer> stranded = read(c, SELECT_OWNED_RUNNING, Integer.MAX_VALUE, owner);
            if (stranded.isEmpty()) {
                return List.of();
            }
            try (PreparedStatement ps = c.prepareStatement(REQUEUE_OWNED)) {
                ps.setString(1, TransferStatus.QUEUED.name());
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, owner);
                ps.executeUpdate();
            }
            return stranded;
        } catch (SQLException e) {
            throw new StreamException("could not reclaim the transfers left running by node '" + owner
                    + "'", e, true);
        }
    }

    /**
     * @return how many {@code RUNNING} transfers carry no owner, and so can never be reclaimed by
     *         anything
     * @throws StreamException if the store could not be reached
     */
    int unownedRunning() throws StreamException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(COUNT_UNOWNED_RUNNING)) {
            ps.setString(1, TransferStatus.RUNNING.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StreamException("could not count the transfers left running without an owner", e,
                    true);
        }
    }

    private Submission forKey(Connection c, String storeName, String key, String fingerprint)
            throws SQLException, StreamException {

        try (PreparedStatement ps = c.prepareStatement(SELECT_BY_IDEMPOTENCY_KEY)) {
            ps.setString(1, storeName);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long jobId = rs.getLong(1);
                String stored = rs.getString(2);
                if (!fingerprint.equals(stored)) {
                    throw new StreamException("idempotency key '" + key + "' already belongs to"
                            + " transfer " + jobId + ", which was submitted with different values."
                            + " Use a new key for a different transfer, or resubmit the original"
                            + " values to join that one", false);
                }
                LOG.debug("transfer " + jobId + " already carries idempotency key '" + key
                        + "'; the resubmission joined it");
                return new Submission(jobId, false);
            }
        }
    }

    private Transfer one(Connection c, String sql, Object... arguments) throws SQLException {
        List<Transfer> found = read(c, sql, 1, arguments);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Bounded with {@code setMaxRows} rather than a {@code LIMIT} clause, because the seven dialects
     * this ships for spell that four different ways and the JDBC driver already knows which.
     */
    private List<Transfer> read(Connection c, String sql, int limit, Object... arguments)
            throws SQLException {
        List<Transfer> transfers = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                ps.setObject(i + 1, arguments[i]);
            }
            if (limit > 0 && limit < Integer.MAX_VALUE) {
                ps.setMaxRows(limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    transfers.add(transferOf(rs));
                }
            }
        }
        return transfers;
    }

    private Transfer transferOf(ResultSet rs) throws SQLException {
        long jobId = rs.getLong(1);
        return new Transfer(jobId, rs.getString(2), decode(jobId, rs.getString(3)), rs.getString(4),
                rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getLong(13));
    }

    private static Transfer withAttempt(Transfer transfer, int attempt) {
        return new Transfer(transfer.jobId(), transfer.storeName(), transfer.parameters(),
                TransferStatus.RUNNING.name(), attempt, transfer.nextAttemptAt(), transfer.runId(),
                transfer.submitter(), transfer.errorCode(), transfer.errorMessage(),
                transfer.createdAt(), transfer.updatedAt(), transfer.endedAt());
    }

    /**
     * What makes two submissions indistinguishable.
     *
     * <p>Keys sorted, so the same values submitted in a different order fingerprint the same — an API
     * resource builds this map from a request, and the order of a JSON object is not something a caller
     * controls or should have to think about.
     */
    private static String fingerprint(String storeName, Map<String, String> parameters) {
        StringBuilder material = new StringBuilder(storeName).append('\0');
        for (Map.Entry<String, String> entry : new TreeMap<>(parameters).entrySet()) {
            material.append(entry.getKey()).append('=').append(entry.getValue()).append('\0');
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE; if it is missing, something is wrong that a fallback
            // hash would only hide.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A flat string map, which is all a submission's values are and all this needs to survive. */
    private static String encode(Map<String, String> parameters) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json.toString();
    }

    /**
     * @return the values, or an empty map if the column could not be read — logged and left alone
     *         rather than failed, because a later version may understand what this one cannot
     */
    private static Map<String, String> decode(long jobId, String parameters) {
        Map<String, String> values = new LinkedHashMap<>();
        if (parameters == null || parameters.isBlank()) {
            return values;
        }
        try {
            JsonElement parsed = JsonParser.parseString(parameters);
            if (!parsed.isJsonObject()) {
                throw new JsonSyntaxException("a transfer's parameters must be a JSON object");
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                values.put(entry.getKey(), value.isJsonNull() ? null : value.getAsString());
            }
        } catch (JsonSyntaxException | UnsupportedOperationException | IllegalStateException e) {
            LOG.error("transfer " + jobId + " has parameters this version cannot read; it will be"
                    + " claimed with none, and the run will report what is missing: " + parameters, e);
        }
        return values;
    }
}
