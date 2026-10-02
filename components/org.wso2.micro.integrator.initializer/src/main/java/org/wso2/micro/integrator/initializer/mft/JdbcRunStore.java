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

import org.apache.synapse.stream.Invoker;
import org.apache.synapse.stream.Run;
import org.apache.synapse.stream.RunStore;
import org.apache.synapse.stream.Stage;
import org.apache.synapse.stream.StreamException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Reads {@code MFT_JOB} back.
 *
 * <h2>Filtering by invoker is two steps, and has to be</h2>
 * {@code INVOKER_NAME} holds an invocation <i>path</i> — every artifact a run came through, joined by
 * {@code " -> "} — so "which runs came through mft-runner" is a question about one segment of a
 * composite string.
 *
 * <p>SQL can only pre-filter that with {@code LIKE '%value%'}, which is not the question: it answers
 * {@code api1} with {@code api10}'s rows, and {@code queue} with every row whose store is called
 * anything-queue. So the {@code LIKE} narrows, and {@link Invoker#passedThrough} decides — segment
 * equality, in Java, on the rows that survived. The refinement is what makes the answer correct; the
 * {@code LIKE} is what stops it reading the whole table.
 *
 * <p>Because the refinement removes rows, the {@code LIMIT} cannot be pushed into that query: a limit of
 * 50 applied before the refinement can return 3. The pre-filtered set is read up to a bounded multiple
 * of the requested limit instead, and the count is honest about being a page rather than a total.
 */
final class JdbcRunStore implements RunStore {

    /**
     * How much more than the requested limit to read when a segment refinement will follow. Bounded
     * rather than unbounded: a filter that matches almost nothing should not read the table, and one
     * that matches almost everything is served by the first page anyway.
     */
    private static final int OVERSCAN = 8;

    private static final int MAX_SCAN = 5000;

    private static final String COLUMNS =
            "RUN_ID, PIPELINE_NAME, INVOKER_TYPE, INVOKER_NAME, STATUS, ATTEMPT, RESUMABLE,"
                    + " OWNER_NODE, WORKSPACE_PATH, SOURCE_ID, SOURCE_SIZE, CURRENT_STAGE, BYTES_DONE,"
                    + " RECORDS_DONE, FAILED_STAGE, ERROR_CODE, ERROR_MESSAGE, RETRYABLE, CREATED_AT,"
                    + " STARTED_AT, FIRST_STARTED_AT, WORKING_MILLIS, ENDED_AT, UPDATED_AT";

    private static final String SELECT_ONE =
            "SELECT " + COLUMNS + " FROM MFT_JOB WHERE RUN_ID = ?";

    /** Ordered by index rather than by name, because the order is the pipeline's shape. */
    private static final String SELECT_STAGES =
            "SELECT STAGE_NAME, STAGE_INDEX, STATUS, BYTES_IN, BYTES_OUT, SELF_NANOS,"
                    + " MEDIATION_NANOS, UPDATED_AT"
                    + " FROM MFT_JOB_STAGE WHERE RUN_ID = ? ORDER BY STAGE_INDEX";

    private final DataSource dataSource;

    JdbcRunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Run find(String runId) throws StreamException {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ONE)) {
            ps.setString(1, runId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? runOf(rs) : null;
            }
        } catch (SQLException e) {
            throw new StreamException("could not read run '" + runId + "'", e, true);
        }
    }

    @Override
    public List<Stage> stages(String runId) throws StreamException {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        List<Stage> stages = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_STAGES)) {
            ps.setString(1, runId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long mediation = rs.getLong(7);
                    // Asked here and not inside the constructor call: wasNull() reports on the column
                    // read LAST, and an argument list evaluates left to right, so in there it answers
                    // for SELF_NANOS -- which is NOT NULL, so it answered false every time and a NULL
                    // mediation arrived as getLong's 0. That made every sink and source claim it had
                    // run a sequence for no measurable time, instead of having none to run.
                    boolean absent = rs.wasNull();
                    stages.add(new Stage(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getLong(6), absent ? -1L : mediation,
                            rs.getLong(8)));
                }
            }
        } catch (SQLException e) {
            throw new StreamException("could not read the stages of run '" + runId + "'", e, true);
        }
        return stages;
    }

    @Override
    public List<Run> list(RunFilter filter) throws StreamException {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM MFT_JOB");
        List<Object> arguments = new ArrayList<>();
        String separator = " WHERE ";

        if (notBlank(filter.pipeline())) {
            sql.append(separator).append("PIPELINE_NAME = ?");
            arguments.add(filter.pipeline().trim());
            separator = " AND ";
        }
        if (notBlank(filter.status())) {
            sql.append(separator).append("STATUS = ?");
            arguments.add(filter.status().trim().toUpperCase());
            separator = " AND ";
        }
        if (notBlank(filter.invokerType())) {
            sql.append(separator).append("INVOKER_TYPE = ?");
            arguments.add(filter.invokerType().trim().toUpperCase());
            separator = " AND ";
        }
        boolean refine = notBlank(filter.invoker());
        if (refine) {
            // The pre-filter. Deliberately loose -- see the class javadoc; passedThrough decides.
            sql.append(separator).append("INVOKER_NAME LIKE ?");
            arguments.add("%" + filter.invoker().trim() + "%");
        }
        sql.append(" ORDER BY CREATED_AT DESC, RUN_ID DESC");

        int scan = refine ? Math.min(MAX_SCAN, filter.limit() * OVERSCAN) : filter.limit();
        List<Run> runs = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < arguments.size(); i++) {
                ps.setObject(i + 1, arguments.get(i));
            }
            // Bounded by the driver rather than by the dialect's own LIMIT/ROWNUM/FETCH FIRST, which
            // are spelled four different ways across the databases this ships for.
            ps.setMaxRows(scan);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && runs.size() < filter.limit()) {
                    Run run = runOf(rs);
                    if (!refine || run.passedThrough(filter.invoker())) {
                        runs.add(run);
                    }
                }
            }
        } catch (SQLException e) {
            throw new StreamException("could not list runs", e, true);
        }
        return runs;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Run runOf(ResultSet rs) throws SQLException {
        int resumable = rs.getInt(7);
        boolean isResumable = !rs.wasNull() && resumable != 0;
        int retryableValue = rs.getInt(18);
        Boolean retryable = rs.wasNull() ? null : retryableValue != 0;
        return new Run(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), isResumable, rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getLong(11), rs.getString(12), rs.getLong(13), rs.getLong(14),
                rs.getString(15), rs.getString(16), rs.getString(17), retryable, rs.getLong(19),
                rs.getLong(20), rs.getLong(21), rs.getLong(22), rs.getLong(23),
                rs.getLong(24));
    }
}
