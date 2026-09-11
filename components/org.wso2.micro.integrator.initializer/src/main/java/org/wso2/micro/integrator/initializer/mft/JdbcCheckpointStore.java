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

import org.apache.synapse.stream.Checkpoint;
import org.apache.synapse.stream.CheckpointStore;
import org.apache.synapse.stream.CheckpointUnit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.Locale;

/**
 * A stage's checkpoint, in MFT_CHECKPOINT.
 *
 * <p>Scoped to one {@code (runId, stageName)} — the store handed to a stage can only ever read or write
 * that stage's position, so two stages of a pipeline cannot see each other's and two runs cannot see
 * each other's at all.
 *
 * <h2>One row, overwritten</h2>
 * There is a single live checkpoint per stage. A history would only be needed if the artifact could be
 * shorter than the recorded length after a crash, and the mandatory per-record durable flush rules that
 * out: the bytes are on disk before the position moves.
 *
 * <p>The write is an UPDATE, then an INSERT if nothing was updated. Deliberately not {@code MERGE} or
 * {@code ON CONFLICT}: those differ across the seven dialects this has to run on, and the coordination
 * component's dialect switch already misclassifies H2. Plain statements avoid needing to know.
 *
 * <h2>Never invent a checkpoint</h2>
 * A missing row means "no checkpoint", which means start from the beginning. An unreadable one means
 * something is wrong, and it throws. Camel's default offset serializer returns null for anything it does
 * not recognise, which is indistinguishable from "none" and quietly duplicates everything — the failure
 * this refuses to reproduce.
 */
final class JdbcCheckpointStore implements CheckpointStore {

    private static final String SELECT =
            "SELECT POSITION_VALUE, POSITION_UNIT, ARTIFACT_LENGTH, FORMAT_VERSION, PAYLOAD"
                    + " FROM MFT_CHECKPOINT WHERE RUN_ID = ? AND STAGE_NAME = ?";

    private static final String UPDATE =
            "UPDATE MFT_CHECKPOINT SET POSITION_VALUE = ?, POSITION_UNIT = ?, ARTIFACT_LENGTH = ?,"
                    + " FORMAT_VERSION = ?, PAYLOAD = ?, UPDATED_AT = ? WHERE RUN_ID = ?"
                    + " AND STAGE_NAME = ?";

    private static final String DELETE =
            "DELETE FROM MFT_CHECKPOINT WHERE RUN_ID = ? AND STAGE_NAME = ?";

    private static final String INSERT =
            "INSERT INTO MFT_CHECKPOINT (RUN_ID, STAGE_NAME, POSITION_VALUE, POSITION_UNIT,"
                    + " ARTIFACT_LENGTH, FORMAT_VERSION, PAYLOAD, UPDATED_AT)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;
    private final String runId;
    private final String stageName;

    JdbcCheckpointStore(DataSource dataSource, String runId, String stageName) {
        this.dataSource = dataSource;
        this.runId = runId;
        this.stageName = stageName;
    }

    @Override
    public Checkpoint lastComplete() throws IOException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setString(1, runId);
            ps.setString(2, stageName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;                    // no checkpoint: start from the beginning
                }
                long position = rs.getLong(1);
                String unit = rs.getString(2);
                long artifactLength = rs.getLong(3);
                String formatVersion = rs.getString(4);
                // getString on a CLOB works on every dialect this ships for, and the payload is
                // capped at 4 KB, so there is no streaming case to handle. Null stays null: it means
                // "no payload", which is how an operator tells its phases apart.
                String payload = rs.getString(5);

                CheckpointUnit parsed;
                try {
                    parsed = CheckpointUnit.valueOf(unit.toUpperCase(Locale.ENGLISH));
                } catch (IllegalArgumentException | NullPointerException e) {
                    // Refuse rather than guess. A number whose unit we cannot read resumes to a
                    // different place depending on how it was meant, which is silent corruption.
                    throw new IOException("checkpoint for stage '" + stageName + "' of run '" + runId
                            + "' records the unit '" + unit + "', which this version does not"
                            + " understand; refusing to resume", e);
                }
                return new Checkpoint(position, artifactLength, parsed, formatVersion, payload);
            }
        } catch (SQLException e) {
            throw new IOException("cannot read the checkpoint for stage '" + stageName + "' of run '"
                    + runId + "'", e);
        }
    }

    @Override
    public void append(Checkpoint checkpoint) throws IOException {
        if (checkpoint == null) {
            throw new IOException("refusing to record a null checkpoint for stage '" + stageName + "'");
        }
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement update = c.prepareStatement(UPDATE)) {
                update.setLong(1, checkpoint.position());
                update.setString(2, checkpoint.unit().name());
                update.setLong(3, checkpoint.outputLength());
                update.setString(4, checkpoint.formatVersion());
                update.setString(5, checkpoint.payload());
                update.setLong(6, now);
                update.setString(7, runId);
                update.setString(8, stageName);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = c.prepareStatement(INSERT)) {
                insert.setString(1, runId);
                insert.setString(2, stageName);
                insert.setLong(3, checkpoint.position());
                insert.setString(4, checkpoint.unit().name());
                insert.setLong(5, checkpoint.outputLength());
                insert.setString(6, checkpoint.formatVersion());
                insert.setString(7, checkpoint.payload());
                insert.setLong(8, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            // Must propagate. A checkpoint that silently failed to persist leaves the position behind
            // the work, and the next attempt repeats everything since the last one that did land.
            throw new IOException("cannot record the checkpoint for stage '" + stageName + "' of run '"
                    + runId + "'", e);
        }
    }

    @Override
    public void clear() throws IOException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(DELETE)) {
            ps.setString(1, runId);
            ps.setString(2, stageName);
            // Deleting nothing is success: the contract requires this to be idempotent, and a stage
            // that never checkpointed has nothing to forget.
            ps.executeUpdate();
        } catch (SQLException e) {
            // Must propagate, for the same reason append() must: a position that silently survived a
            // clear would resume a stage into a source it is no longer reading.
            throw new IOException("cannot clear the checkpoint for stage '" + stageName + "' of run '"
                    + runId + "'", e);
        }
    }
}
