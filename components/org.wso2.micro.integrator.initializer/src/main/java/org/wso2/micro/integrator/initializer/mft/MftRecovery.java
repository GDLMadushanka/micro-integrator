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
import org.apache.synapse.stream.StreamException;
import org.apache.synapse.stream.Transfer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * What this node does about the transfers it was running when it stopped existing.
 *
 * <p>A process that dies mid-transfer leaves a {@code RUNNING} row in both tables and no one to
 * correct them: the graceful path stops each run, records its position and requeues the transfer, but
 * a {@code kill -9}, an OOM kill or a power cut runs no shutdown hook at all. Without this, such a
 * transfer reads as running for ever, and nothing ever re-claims it.
 *
 * <h2>Why startup, and why this node only</h2>
 * A node that is starting cannot have a transfer in flight. Any {@code RUNNING} row it owns is
 * therefore from a process that no longer exists — certainty, established locally, needing no timeout
 * and no heartbeat. That is the whole argument of ADR-0028, and the reason a heartbeat column was
 * removed rather than added: a timeout cannot tell a dead node from one in a long GC pause, and being
 * wrong in that direction means declaring a live transfer dead and running it twice.
 *
 * <p>It is keyed on <b>this node's id and nothing else</b>. Rows owned by another node are that node's
 * to recover when it comes back, or the cluster coordinator's if it never does. A standalone node has an
 * identity too, derived from where it is installed rather than configured, so this needs no setup on the
 * deployment where {@code node_id} is never set — see {@link MftNodeId}.
 *
 * <h2>The two tables recover differently, because they mean different things</h2>
 * <ul>
 *   <li>A <b>submission</b> ({@code MFT_TRANSFER}) is put back on the queue. Being claimable again is
 *       what its recovery looks like: the processor takes it, the run id comes from the job id, so the
 *       attempt lands in the workspace the dead one left and resumes from its checkpoint.</li>
 *   <li>A <b>run</b> ({@code MFT_JOB}) is marked {@code INTERRUPTED} and left alone. Recovery marks; it
 *       does not resume. Re-invoking a pipeline from a startup thread would mean the framework running
 *       a transfer nobody asked for, with no caller, no message and no retry policy — retry belongs to
 *       the processor, and recovery's job is to leave the state such that a retry can succeed.</li>
 * </ul>
 *
 * <p>{@code INTERRUPTED} is deliberately not {@code FAILED}: <i>the transfer errored</i> and <i>the
 * server died under it</i> call for different responses, and the distinction cannot be recovered after
 * the fact.
 *
 * <h2>Files</h2>
 * Startup is also the only moment at which deleting a workspace is provably safe — "I am starting,
 * therefore I am not writing" — so a scratch run's directory is reclaimed here. A resumable one's is
 * kept: it is the resume candidate, which is the entire point.
 */
final class MftRecovery {

    private static final Log LOG = LogFactory.getLog(MftRecovery.class);

    private static final String STATUS_RUNNING = "RUNNING";

    /** Not {@code FAILED}. The transfer did not fail; the process under it went away. */
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";

    private static final String SELECT_OWNED_RUNS =
            "SELECT RUN_ID, PIPELINE_NAME, RESUMABLE, WORKSPACE_PATH FROM MFT_JOB"
                    + " WHERE STATUS = ? AND OWNER_NODE = ? ORDER BY RUN_ID";

    private static final String INTERRUPT_OWNED_RUNS =
            "UPDATE MFT_JOB SET STATUS = ?, ENDED_AT = ?, UPDATED_AT = ?"
                    + " WHERE STATUS = ? AND OWNER_NODE = ?";

    private static final String COUNT_UNOWNED_RUNS =
            "SELECT COUNT(*) FROM MFT_JOB WHERE STATUS = ? AND OWNER_NODE IS NULL";

    private MftRecovery() {
    }

    /**
     * Sweeps this node's interrupted runs and submissions.
     *
     * <p>Never throws. A sweep that cannot run is a recovery that has not happened yet — the rows are
     * still there and the next startup will find them — so it must not be a reason for the server not
     * to start. It is loud, though: silence here looks exactly like having nothing to recover.
     *
     * @param dataSource the MFT datasource
     * @param transfers  the store to reclaim submissions through, so both halves use one connection
     *                   pool and one set of statements
     * @param node       this node's identity, or {@code null} for a clustered node with no configured
     *                   id — the only case that has none, and the only one that cannot be swept
     */
    static void sweep(DataSource dataSource, JdbcTransferStore transfers, String node) {

        if (node == null) {
            reportUnrecoverable(dataSource, transfers);
            return;
        }

        List<Run> stranded;
        try {
            stranded = interruptOwnedRuns(dataSource, node);
        } catch (SQLException e) {
            LOG.error("MFT could not recover the runs left behind by this node ('" + node + "'). They"
                    + " will still read as RUNNING, and a queued one will not be re-claimed until a"
                    + " later startup succeeds at this.", e);
            return;
        }

        List<Transfer> requeued;
        try {
            requeued = transfers.reclaimOwned(node);
        } catch (StreamException e) {
            LOG.error("MFT marked this node's interrupted runs but could not put their submissions back"
                    + " on the queue, so they will not be re-claimed until a later startup succeeds at"
                    + " this.", e);
            return;
        }

        if (!requeued.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Transfer transfer : requeued) {
                ids.append(ids.length() == 0 ? "" : ", ").append(transfer.jobId());
            }
            // INFO and not WARN: something did go wrong, but it went wrong last time -- this line is the
            // recovery working, and an operator reading it wants to know which transfers came back.
            LOG.info("MFT requeued " + requeued.size() + " transfer(s) this node was running when it"
                    + " stopped: " + ids + ". Each resumes from its last checkpoint when it is claimed"
                    + " again.");
        }
        if (!stranded.isEmpty()) {
            LOG.info("MFT marked " + stranded.size() + " run(s) INTERRUPTED that this node was"
                    + " executing when it stopped.");
        }
        reclaimScratchWorkspaces(stranded);
    }

    /**
     * Marks them, and reads them first so the workspace rule has something to branch on.
     *
     * <p>Runs before the submissions are requeued, deliberately. The other order leaves a window in
     * which a processor could claim a transfer whose run row still says {@code RUNNING}, and the new
     * attempt would then be recorded against a row describing the attempt that died.
     */
    private static List<Run> interruptOwnedRuns(DataSource dataSource, String node)
            throws SQLException {

        long now = System.currentTimeMillis();
        List<Run> stranded = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(SELECT_OWNED_RUNS)) {
                ps.setString(1, STATUS_RUNNING);
                ps.setString(2, node);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int resumable = rs.getInt("RESUMABLE");
                        // Captured before the constructor call rather than asked inside it: an
                        // argument list evaluates left to right, so an inline wasNull() would answer
                        // for PIPELINE_NAME. Harmless while that column is NOT NULL and a NULL
                        // RESUMABLE reads as 0 either way, which is why this was never wrong -- but
                        // it is one schema change away from being wrong, for free.
                        boolean isResumable = !rs.wasNull() && resumable != 0;
                        stranded.add(new Run(rs.getString("RUN_ID"), rs.getString("PIPELINE_NAME"),
                                isResumable, rs.getString("WORKSPACE_PATH")));
                    }
                }
            }
            if (stranded.isEmpty()) {
                return stranded;
            }
            try (PreparedStatement ps = c.prepareStatement(INTERRUPT_OWNED_RUNS)) {
                ps.setString(1, STATUS_INTERRUPTED);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, STATUS_RUNNING);
                ps.setString(5, node);
                ps.executeUpdate();
            }
        }
        return stranded;
    }

    /**
     * Deletes the workspaces of runs that could never have resumed from them.
     *
     * <p>A scratch run's directory holds structural spill and nothing else; no attempt will ever read
     * it, so keeping it is not caution but a leak. A resumable one's is kept untouched — it is what the
     * next attempt resumes from.
     */
    private static void reclaimScratchWorkspaces(List<Run> stranded) {
        for (Run run : stranded) {
            if (run.resumable() || run.workspacePath() == null || run.workspacePath().isBlank()) {
                continue;
            }
            // Not fatal, and not retried: the helper logs and leaves an undeletable directory behind,
            // and the row survives to name it.
            MftWorkspaces.discard(run.workspacePath(), run.runId(), "interrupted scratch run");
        }
    }

    /**
     * Says what cannot be recovered, and why.
     *
     * <p>Reached only by a <b>clustered</b> node with no configured {@code node_id}. A standalone node
     * always has an identity, and a clustered one whose operator set {@code node_id} has the one it was
     * given; what is left is the case where {@code RDBMSCoordinationStrategy} generates a random id per
     * start, which recognises nothing it wrote last time.
     *
     * <p>The tempting fallback — sweep every row with no owner — is the one outcome worse than leaking a
     * row: in a cluster those rows belong to the other nodes that also have none, and taking them
     * abandons live transfers.
     *
     * <p>Reported with the count, because "three transfers are stuck" is actionable and "set node_id" on
     * its own reads as boilerplate.
     */
    private static void reportUnrecoverable(DataSource dataSource, JdbcTransferStore transfers) {
        int runs = 0;
        int submissions = 0;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(COUNT_UNOWNED_RUNS)) {
            ps.setString(1, STATUS_RUNNING);
            try (ResultSet rs = ps.executeQuery()) {
                runs = rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.debug("MFT could not count the unrecoverable runs", e);
        }
        try {
            submissions = transfers.unownedRunning();
        } catch (StreamException e) {
            LOG.debug("MFT could not count the unrecoverable submissions", e);
        }

        if (runs == 0 && submissions == 0) {
            LOG.warn("MFT cannot recover transfers interrupted by a restart: this node is clustered but"
                    + " has no node_id, so the id it runs under is generated afresh on every start and"
                    + " recognises nothing it wrote before. Set [cluster_config] node_id in"
                    + " deployment.toml. Nothing is stuck right now.");
            return;
        }
        LOG.error("MFT has " + submissions + " submission(s) and " + runs + " run(s) still marked"
                + " RUNNING with no owner, left by a process that is gone. They cannot be recovered"
                + " automatically: this node is clustered but has no node_id, so it runs under an id"
                + " generated afresh on every start. Set [cluster_config] node_id in deployment.toml so"
                + " a restart can sweep them, and put the stuck ones back by hand:"
                + " UPDATE MFT_TRANSFER SET STATUS = 'QUEUED', OWNER_NODE = NULL WHERE STATUS ="
                + " 'RUNNING';");
    }

    /** One interrupted run, and the two things the workspace rule needs to know about it. */
    private record Run(String runId, String pipelineName, boolean resumable, String workspacePath) {
    }
}
