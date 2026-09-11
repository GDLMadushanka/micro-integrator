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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.stream.JobRecord;
import org.apache.synapse.stream.JobRun;
import org.apache.synapse.stream.JobStore;
import org.apache.synapse.stream.StreamException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * A pipeline's runs, in MFT_JOB and MFT_JOB_STAGE.
 *
 * <h2>Which failures are fatal, and which are not</h2>
 * Only {@link #start} throws. A run that could not be recorded cannot be recovered after a crash and
 * cannot be reported on, so starting it anyway would produce exactly the invisible in-flight transfer
 * the job table exists to prevent. Everything after that point is progress and outcome: valuable, but
 * never worth failing a transfer that is otherwise succeeding. Those methods log and continue.
 *
 * <p>The asymmetry is deliberate and is the opposite of {@link JdbcCheckpointStore}, where a failed
 * write <i>must</i> propagate — a checkpoint that silently did not persist leaves the recorded position
 * behind the work. A progress row that did not persist costs only accuracy of display.
 *
 * <h2>UPDATE then INSERT</h2>
 * The same plain-statement approach as the checkpoint store, for the same reason: {@code MERGE} and
 * {@code ON CONFLICT} differ across the seven dialects this runs on. A losing race on the insert is
 * caught and retried as an update rather than prevented, which is cheaper than a lock and correct
 * because the two writers are attempts at the same run.
 *
 * <h2>The owning node</h2>
 * Written here rather than passed in from Synapse, which has no notion of cluster membership and must
 * not acquire one. It is the key crash recovery matches on: a node's own restart is the only signal
 * that its {@code RUNNING} rows belong to a process that no longer exists.
 */
final class JdbcJobStore implements JobStore {

    private static final Log LOG = LogFactory.getLog(JdbcJobStore.class);

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_DONE = "DONE";

    /**
     * A stage a resumed run did not execute, because a previous attempt had already finished it.
     *
     * <p>Recorded rather than omitted so a resumed run's per-stage rows are self-describing: without
     * it the table is simply missing rows for the skipped segments, with nothing saying why.
     */
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final String STATUS_FAILED = "FAILED";

    private static final String SELECT_JOB =
            "SELECT STATUS, ATTEMPT, SOURCE_ID, SOURCE_SIZE, SOURCE_LAST_MODIFIED FROM MFT_JOB"
                    + " WHERE RUN_ID = ?";

    private static final String INSERT_JOB =
            "INSERT INTO MFT_JOB (RUN_ID, PIPELINE_NAME, INVOKER_TYPE, INVOKER_NAME, STATUS,"
                    + " OWNER_NODE, RESUMABLE, ATTEMPT, WORKSPACE_PATH, SOURCE_ID, SOURCE_SIZE,"
                    + " SOURCE_LAST_MODIFIED, BYTES_DONE, RECORDS_DONE, CREATED_AT, STARTED_AT,"
                    + " UPDATED_AT)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 0, 0, ?, ?, ?)";

    /**
     * A retry reuses the run id on purpose. The error columns are cleared because they describe the
     * attempt that just ended, and leaving them would attribute the previous failure to this attempt.
     */
    /**
     * The invoker is COALESCEd rather than assigned, so a real value always replaces the stored one —
     * which is how a queued run, whose row the submit mediator creates with no invoker, gains
     * {@code PROCESSOR} once a processor actually runs it.
     *
     * <p><b>The guard is dormant today.</b> Every caller of {@code start()} is a run that is executing,
     * and the framework always resolves a type for one, falling back to {@code DIRECT}. So nothing
     * currently passes null here. It is written this way for the flow-1 row that does not exist yet,
     * and because overwriting a known invoker with an unknown one is never the right answer.
     */
    private static final String RESTART_JOB =
            "UPDATE MFT_JOB SET STATUS = ?, OWNER_NODE = ?, RESUMABLE = ?, ATTEMPT = ATTEMPT + 1,"
                    + " INVOKER_TYPE = COALESCE(?, INVOKER_TYPE),"
                    + " INVOKER_NAME = COALESCE(?, INVOKER_NAME),"
                    + " WORKSPACE_PATH = ?, SOURCE_ID = ?, SOURCE_SIZE = ?, SOURCE_LAST_MODIFIED = ?,"
                    + " ERROR_CODE = NULL, ERROR_MESSAGE = NULL, FAILED_STAGE = NULL,"
                    + " RETRYABLE = NULL, ENDED_AT = NULL, STARTED_AT = ?, UPDATED_AT = ?"
                    + " WHERE RUN_ID = ?";

    private static final String UPDATE_PROGRESS =
            "UPDATE MFT_JOB SET CURRENT_STAGE = ?, BYTES_DONE = ?, RECORDS_DONE = ?, UPDATED_AT = ?"
                    + " WHERE RUN_ID = ?";

    private static final String UPDATE_SUCCEEDED =
            "UPDATE MFT_JOB SET STATUS = ?, BYTES_DONE = ?, RECORDS_DONE = ?, ENDED_AT = ?,"
                    + " UPDATED_AT = ? WHERE RUN_ID = ?";

    private static final String UPDATE_FAILED =
            "UPDATE MFT_JOB SET STATUS = ?, FAILED_STAGE = ?, ERROR_CODE = ?, ERROR_MESSAGE = ?,"
                    + " RETRYABLE = ?, ENDED_AT = ?, UPDATED_AT = ? WHERE RUN_ID = ?";

    private static final String UPDATE_STAGE =
            "UPDATE MFT_JOB_STAGE SET STAGE_INDEX = ?, STATUS = ?, BYTES_IN = ?, BYTES_OUT = ?,"
                    + " SELF_NANOS = ?, UPDATED_AT = ? WHERE RUN_ID = ? AND STAGE_NAME = ?";

    private static final String INSERT_STAGE =
            "INSERT INTO MFT_JOB_STAGE (RUN_ID, STAGE_NAME, STAGE_INDEX, STATUS, BYTES_IN, BYTES_OUT,"
                    + " SELF_NANOS, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    /**
     * Resolved per run rather than held, because the coordination component may publish this node's id
     * after MFT registered. See {@link MftNodeId}.
     */
    private final Supplier<String> ownerNode;

    JdbcJobStore(DataSource dataSource, Supplier<String> ownerNode) {
        this.dataSource = dataSource;
        this.ownerNode = ownerNode;
    }

    @Override
    public JobRecord start(JobRun run) throws StreamException {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            JobRecord prior = read(c, run.runId());
            if (prior == null) {
                try {
                    insert(c, run, now);
                    return null;
                } catch (SQLException e) {
                    // Only a key collision can mean "someone else inserted this run". Treating every
                    // SQLException as that would swallow a missing table, a rejected column, an expired
                    // credential or a dropped connection, and then run the transfer unrecorded.
                    if (!isConstraintViolation(e)) {
                        throw e;
                    }
                    // A collision is necessary but not sufficient: it also has to be *this* row. A
                    // NOT NULL or check violation reports the same SQLState class on some drivers, and
                    // there would be no row to find.
                    prior = read(c, run.runId());
                    if (prior == null) {
                        throw e;
                    }
                    LOG.debug("run '" + run.runId() + "' was inserted concurrently; recording this as"
                            + " a further attempt on it instead", e);
                }
            }
            if (restart(c, run, now) == 0) {
                // The row we just read is gone, or was never there. Either way this run is now
                // unrecorded, and continuing would produce exactly the invisible in-flight transfer
                // the job table exists to prevent.
                throw new StreamException("run '" + run.runId() + "' of stream pipeline '"
                        + run.pipelineName() + "' could not be recorded: the job row disappeared"
                        + " between reading it and claiming it", true);
            }
            return prior;
        } catch (SQLException e) {
            throw new StreamException("cannot record the start of run '" + run.runId() + "' of stream"
                    + " pipeline '" + run.pipelineName() + "'; refusing to run a transfer that could"
                    + " not be recovered or reported on", e, true);
        }
    }

    @Override
    public void progress(String runId, String stage, long bytes, long records) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_PROGRESS)) {
            ps.setString(1, stage);
            ps.setLong(2, bytes);
            ps.setLong(3, records);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            quietly("progress", runId, e);
        }
    }

    /**
     * A stage that did no work because a previous attempt had already finished it.
     *
     * <p>Written with zero byte counts and {@code STATUS = 'SKIPPED'}, so a resumed run's per-stage
     * rows say what happened rather than simply being absent. Uses the same update-then-insert shape
     * as {@link #stageFinished}, and never throws: this is telemetry on a live transfer.
     */
    @Override
    public void stageSkipped(String runId, String stage, int index) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement update = c.prepareStatement(UPDATE_STAGE)) {
                update.setInt(1, index);
                update.setString(2, STATUS_SKIPPED);
                update.setLong(3, 0L);
                update.setLong(4, 0L);
                update.setLong(5, 0L);
                update.setLong(6, now);
                update.setString(7, runId);
                update.setString(8, stage);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = c.prepareStatement(INSERT_STAGE)) {
                insert.setString(1, runId);
                insert.setString(2, stage);
                insert.setInt(3, index);
                insert.setString(4, STATUS_SKIPPED);
                insert.setLong(5, 0L);
                insert.setLong(6, 0L);
                insert.setLong(7, 0L);
                insert.setLong(8, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            quietly("stageSkipped", runId, e);
        }
    }

    @Override
    public void stageFinished(String runId, String stage, int index, long bytesIn, long bytesOut,
                              long selfNanos) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement update = c.prepareStatement(UPDATE_STAGE)) {
                update.setInt(1, index);
                update.setString(2, STATUS_DONE);
                update.setLong(3, bytesIn);
                update.setLong(4, bytesOut);
                update.setLong(5, selfNanos);
                update.setLong(6, now);
                update.setString(7, runId);
                update.setString(8, stage);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = c.prepareStatement(INSERT_STAGE)) {
                insert.setString(1, runId);
                insert.setString(2, stage);
                insert.setInt(3, index);
                insert.setString(4, STATUS_DONE);
                insert.setLong(5, bytesIn);
                insert.setLong(6, bytesOut);
                insert.setLong(7, selfNanos);
                insert.setLong(8, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            quietly("completion of stage '" + stage + "'", runId, e);
        }
    }

    @Override
    public void succeeded(String runId, long bytes, long records) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_SUCCEEDED)) {
            ps.setString(1, STATUS_DONE);
            ps.setLong(2, bytes);
            ps.setLong(3, records);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Costly but not fatal: the row stays RUNNING and this node's next startup sweep will mark
            // it interrupted. Wrong, and better than failing a transfer that moved every byte.
            quietly("successful completion", runId, e);
        }
    }

    @Override
    public void failed(String runId, String stage, String code, String message, boolean retryable) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_FAILED)) {
            ps.setString(1, STATUS_FAILED);
            ps.setString(2, stage);
            ps.setString(3, code);
            ps.setString(4, message);
            ps.setInt(5, retryable ? 1 : 0);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setString(8, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            quietly("failure", runId, e);
        }
    }

    // ---------------------------------------------------------------- internals

    private JobRecord read(Connection c, String runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_JOB)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new JobRecord(runId, rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5));
            }
        }
    }

    private void insert(Connection c, JobRun run, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_JOB)) {
            ps.setString(1, run.runId());
            ps.setString(2, run.pipelineName());
            setNullable(ps, 3, run.invokerType());
            setNullable(ps, 4, run.invokerName());
            ps.setString(5, STATUS_RUNNING);
            setNullable(ps, 6, ownerNode.get());
            ps.setInt(7, run.resumable() ? 1 : 0);
            setNullable(ps, 8, run.workspacePath());
            setNullable(ps, 9, run.sourceId());
            ps.setLong(10, run.sourceSize());
            ps.setLong(11, run.sourceLastModified());
            ps.setLong(12, now);
            ps.setLong(13, now);
            ps.setLong(14, now);
            ps.executeUpdate();
        }
    }

    /**
     * Claims an existing row for a further attempt.
     *
     * @return rows updated, which the caller must check — zero means the run is not recorded
     */
    private int restart(Connection c, JobRun run, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RESTART_JOB)) {
            ps.setString(1, STATUS_RUNNING);
            setNullable(ps, 2, ownerNode.get());
            ps.setInt(3, run.resumable() ? 1 : 0);
            setNullable(ps, 4, run.invokerType());
            setNullable(ps, 5, run.invokerName());
            setNullable(ps, 6, run.workspacePath());
            setNullable(ps, 7, run.sourceId());
            ps.setLong(8, run.sourceSize());
            ps.setLong(9, run.sourceLastModified());
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.setString(12, run.runId());
            return ps.executeUpdate();
        }
    }

    /**
     * Whether this failure could be a key collision, and so a concurrent attempt at the same run.
     *
     * <p>Two mechanisms, because neither alone is reliable across seven drivers.
     * {@link SQLIntegrityConstraintViolationException} is the JDBC 4 answer and the better one, but not
     * every driver maps to it faithfully. SQLState class {@code 23} is the portable fallback, and is
     * looser than we want — it also covers NOT NULL and foreign-key violations. The caller closes that
     * gap by requiring the row to actually be there afterwards.
     *
     * <p>Walks the cause chain because pooling and proxying layers wrap the driver's exception.
     */
    static boolean isConstraintViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void setNullable(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void quietly(String what, String runId, SQLException e) {
        LOG.warn("could not record " + what + " for MFT run '" + runId + "'; the transfer is"
                + " unaffected but its job record is now incomplete", e);
    }
}
