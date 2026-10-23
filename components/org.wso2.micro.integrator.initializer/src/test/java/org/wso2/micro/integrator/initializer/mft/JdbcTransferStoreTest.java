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

import org.apache.synapse.stream.StreamException;
import org.apache.synapse.stream.transfer.Submission;
import org.apache.synapse.stream.transfer.Transfer;
import org.apache.synapse.stream.transfer.TransferStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

/**
 * The transfer store against a real database.
 *
 * <p>Run over H2 with the <b>shipped</b> {@code h2_mft.sql}, not a schema written for the test — so the
 * DDL and the statements are checked against each other, which is the half of this that a mock cannot
 * do. Every claim here is about SQL: uniqueness, an atomic claim under two threads, what collapses and
 * what does not.
 */
public class JdbcTransferStoreTest {

    private static final String STORE = "mft-queue";

    /** The artifact a submission came through; the first segment of the run's path. */
    private static final String SUBMITTER = "mft-jobs";

    private JdbcTransferStore transfers;

    /** Kept so a test can assert on the rows and files the store is supposed to have removed. */
    private JdbcDataSource dataSource;

    /** Mutable so a test can make this node a different node, which is what a sweep turns on. */
    private final java.util.concurrent.atomic.AtomicReference<String> node =
            new java.util.concurrent.atomic.AtomicReference<>("node-1");

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mft-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        Path script = Path.of("..", "..", "distribution", "src", "dbscripts", "h2", "h2_mft.sql")
                .toAbsolutePath().normalize();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(Files.readString(script));
        }
        transfers = new JdbcTransferStore(dataSource, node::get,
                new JdbcJobStore(dataSource, node::get));
    }

    @Test
    public void aSubmissionGetsAReadableIdAndComesBackWhole() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("connection", "conn1");
        values.put("source", "in/orders.csv");

        Submission submission = transfers.submit(STORE, values, null, SUBMITTER);

        assertTrue("the first id is a number a person can quote", submission.jobId() > 0L);
        assertTrue(submission.created());

        Transfer stored = transfers.find(submission.jobId());
        assertEquals(STORE, stored.storeName());
        assertEquals(TransferStatus.QUEUED.name(), stored.status());
        assertEquals("conn1", stored.parameter("connection"));
        assertEquals("in/orders.csv", stored.parameter("source"));
        assertEquals("mft-job-" + submission.jobId(), stored.runName());
        assertEquals(0, stored.attempt());
        assertNull(stored.runId());
    }

    /** The accident this catches: a double-click, or a client that retried a request in flight. */
    @Test
    public void identicalValuesCollapseWhileTheFirstIsStillWaiting() throws Exception {
        Map<String, String> values = Map.of("source", "in/orders.csv");

        Submission first = transfers.submit(STORE, values, null, SUBMITTER);
        Submission second = transfers.submit(STORE, values, null, SUBMITTER);

        assertEquals(first.jobId(), second.jobId());
        assertFalse("the second call created nothing", second.created());
        assertEquals(1, transfers.queued(STORE));
    }

    /** And the thing that must stay possible without a feature: sending the same file again. */
    @Test
    public void identicalValuesDoNotCollapseOnceTheFirstHasFinished() throws Exception {
        Map<String, String> values = Map.of("source", "in/orders.csv");

        Submission first = transfers.submit(STORE, values, null, SUBMITTER);
        transfers.claim(STORE);
        transfers.finish(first.jobId(), TransferStatus.DONE, null, null);

        Submission second = transfers.submit(STORE, values, null, SUBMITTER);

        assertTrue("a second transfer, not the first one again",
                first.jobId() != second.jobId());
        assertTrue(second.created());
    }

    /**
     * A key outlives the window the fingerprint watches: the same request arriving a week later, or
     * after the first one finished, is still the same request.
     */
    @Test
    public void anIdempotencyKeyCollapsesEvenAfterTheFirstFinished() throws Exception {
        Map<String, String> values = Map.of("source", "in/orders.csv");

        Submission first = transfers.submit(STORE, values, "nightly-2026-09-15", SUBMITTER);
        transfers.claim(STORE);
        transfers.finish(first.jobId(), TransferStatus.DONE, null, null);

        Submission again = transfers.submit(STORE, values, "nightly-2026-09-15", SUBMITTER);

        assertEquals(first.jobId(), again.jobId());
        assertFalse(again.created());
    }

    /**
     * The other direction, and the reason a key is not merely a longer-lived fingerprint: a caller who
     * says "this is a different request" must be believed, even when every value matches one already
     * running. Without this, the heuristic would overrule an explicit statement of intent.
     */
    @Test
    public void differentKeysMeanDifferentTransfersEvenWithIdenticalValues() throws Exception {
        Map<String, String> values = Map.of("source", "in/orders.csv");

        Submission first = transfers.submit(STORE, values, "run-a", SUBMITTER);
        Submission second = transfers.submit(STORE, values, "run-b", SUBMITTER);

        assertTrue("a second transfer, not the first one again",
                first.jobId() != second.jobId());
        assertTrue(second.created());
        assertEquals(2, transfers.queued(STORE));
    }

    /**
     * A key reused for different values is a contradiction, and neither answer would be visible to the
     * caller: returning the first transfer discards what they just submitted, queuing a second makes
     * the key mean nothing. So it is refused, and not retryably — repeating the request cannot resolve
     * a contradiction inside it.
     */
    @Test
    public void aKeyReusedForDifferentValuesIsRefused() throws Exception {
        Submission first = transfers.submit(STORE, Map.of("source", "in/orders.csv"), "nightly", SUBMITTER);

        try {
            transfers.submit(STORE, Map.of("source", "in/something-else.csv"), "nightly", SUBMITTER);
            fail("the same key for a different transfer must be refused");
        } catch (StreamException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("different values"));
            assertTrue("it names the transfer the key already belongs to",
                    expected.getMessage().contains(String.valueOf(first.jobId())));
            assertFalse("repeating it cannot help", expected.isRetryable());
        }
        assertEquals("and nothing was queued", 1, transfers.queued(STORE));
    }

    /** Order is not something a caller controls, so it must not decide whether two submissions match. */
    @Test
    public void theFingerprintIsIndependentOfParameterOrder() throws Exception {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("source", "in/a.csv");
        one.put("target", "out/a.csv");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("target", "out/a.csv");
        other.put("source", "in/a.csv");

        assertEquals(transfers.submit(STORE, one, null, SUBMITTER).jobId(),
                transfers.submit(STORE, other, null, SUBMITTER).jobId());
    }

    /** Two stores are two queues, even with the same values in both. */
    @Test
    public void twoStoresDoNotShareTransfers() throws Exception {
        Map<String, String> values = Map.of("source", "in/orders.csv");

        Submission bulk = transfers.submit("bulk", values, null, SUBMITTER);
        Submission interactive = transfers.submit("interactive", values, null, SUBMITTER);

        assertTrue(bulk.jobId() != interactive.jobId());
        assertEquals(1, transfers.queued("bulk"));
        assertNull("a claim is scoped to its own store", transfers.claim("other"));
    }

    /** The claim is the affected-row count, which is what makes two nodes safe on one table. */
    @Test
    public void onlyOneOfTwoClaimsWins() throws Exception {
        transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER);

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Transfer> a = new AtomicReference<>();
        AtomicReference<Transfer> b = new AtomicReference<>();
        Thread one = claimer(go, a);
        Thread two = claimer(go, b);
        one.start();
        two.start();
        go.countDown();
        one.join(5000L);
        two.join(5000L);

        assertTrue("exactly one claimer must win",
                (a.get() == null) != (b.get() == null));
        assertEquals(0, transfers.queued(STORE));
    }

    @Test
    public void aClaimCountsTheAttemptAndRecordsItsRun() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER).jobId();

        Transfer claimed = transfers.claim(STORE);
        assertEquals(1, claimed.attempt());
        assertEquals(TransferStatus.RUNNING.name(), claimed.status());

        transfers.linkRun(jobId, claimed.runName());
        assertEquals("mft-job-" + jobId, transfers.find(jobId).runId());
    }

    /**
     * A retry waits. Without this a transfer that failed against a server that is down would be
     * claimable again immediately, by this node or any other, and the queue would spin.
     */
    @Test
    public void aRequeuedTransferIsHeldBackUntilItsDeadline() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);

        transfers.requeue(jobId, System.currentTimeMillis() + 60_000L);

        assertEquals(TransferStatus.QUEUED.name(), transfers.find(jobId).status());
        assertNull("queued, but not yet claimable", transfers.claim(STORE));
    }

    /**
     * And once the deadline has passed it is the same transfer again, one attempt further on — which is
     * what makes the next run resume rather than start over.
     *
     * <p>Note the requeue is conditional on the transfer <i>not</i> already being queued, so it is one
     * shot per claim: a second requeue without an intervening claim does nothing rather than resetting
     * the deadline. That is deliberate — it stops a confused caller pushing a transfer's place in the
     * queue back indefinitely — and it is why this test claims before each requeue.
     */
    @Test
    public void aRequeuedTransferIsClaimableOnceItsDeadlineHasPassed() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);

        transfers.requeue(jobId, System.currentTimeMillis() - 1L);

        Transfer again = transfers.claim(STORE);
        assertEquals(jobId, again.jobId());
        assertEquals(2, again.attempt());
    }

    /** A transfer that has started belongs to whoever is running it. */
    @Test
    public void onlyAWaitingTransferCanBeCancelled() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER).jobId();

        assertTrue(transfers.cancel(jobId));
        assertEquals(TransferStatus.CANCELLED.name(), transfers.find(jobId).status());

        long second = transfers.submit(STORE, Map.of("source", "in/other.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);
        assertFalse("it is already moving bytes", transfers.cancel(second));
    }

    @Test
    public void listingIsNewestFirstAndFiltersByStatus() throws Exception {
        long first = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER).jobId();
        long second = transfers.submit(STORE, Map.of("source", "in/b.csv"), null, SUBMITTER).jobId();
        transfers.finish(first, TransferStatus.DONE, null, null);

        List<Long> queued = ids(transfers.list(STORE, TransferStatus.QUEUED.name(), 10));
        assertEquals(List.of(second), queued);
        assertEquals(List.of(first), ids(transfers.list(STORE, TransferStatus.DONE.name(), 10)));
        assertEquals(List.of(second, first), ids(transfers.list(STORE, null, 10)));
    }

    @Test
    public void aFailureIsRecordedWithItsReason() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);

        transfers.finish(jobId, TransferStatus.FAILED, "STREAM_SEQUENCE_FAILED", "column 3 is not a number");

        Transfer failed = transfers.find(jobId);
        assertEquals(TransferStatus.FAILED.name(), failed.status());
        assertEquals("STREAM_SEQUENCE_FAILED", failed.errorCode());
        assertEquals("column 3 is not a number", failed.errorMessage());
        assertTrue(failed.endedAt() > 0L);
    }

    /**
     * The submitter cannot be worked out later -- the API that submitted has answered its caller and is
     * on no thread the run will use -- so if it is not stored here it is gone.
     */
    @Test
    public void theSubmittingArtifactIsStoredAndComesBack() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, "orders-api").jobId();

        assertEquals("orders-api", transfers.find(jobId).submitter());
        assertEquals("orders-api", transfers.claim(STORE).submitter());
    }

    /** Nothing identified the caller -- a sequence, a test -- and a missing name is not an error. */
    @Test
    public void aSubmissionWithNoIdentifiableCallerStillWorks() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, null).jobId();

        assertNull(transfers.find(jobId).submitter());
    }

    @Test
    public void anUnknownJobIdIsNullRatherThanAnError() throws Exception {
        assertNull(transfers.find(9999L));
    }

    /**
     * The case the sweep exists for: a hard kill leaves a claimed transfer RUNNING for ever, because no
     * shutdown hook ran to put it back. Nothing but the owning node, on its way up, can know it is dead.
     */
    @Test
    public void aTransferStrandedByAKilledNodeIsPutBackOnStartup() throws Exception {
        long jobId = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER).jobId();
        Transfer claimed = transfers.claim(STORE);
        assertEquals(TransferStatus.RUNNING.name(), transfers.find(jobId).status());
        assertEquals("the claim counted an attempt", 1, claimed.attempt());

        List<Transfer> reclaimed = transfers.reclaimOwned("node-1");

        assertEquals(List.of(jobId), ids(reclaimed));
        Transfer back = transfers.find(jobId);
        assertEquals(TransferStatus.QUEUED.name(), back.status());
        assertEquals("claimable at once: a restart is not the destination failing",
                1, transfers.queued(STORE));
        assertEquals("the attempt the dead claim counted is kept, so the retry bound still bounds",
                1, back.attempt());
        assertEquals("and it is claimable again", jobId, transfers.claim(STORE).jobId());
    }

    /**
     * The hazard that makes the sweep owner-keyed rather than "everything RUNNING": in a cluster the
     * other node is alive and still writing. Requeuing its transfer would run one job id twice, into one
     * workspace.
     */
    @Test
    public void aSweepNeverTouchesAnotherNodesRunningTransfer() throws Exception {
        long mine = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);
        node.set("node-2");
        long theirs = transfers.submit(STORE, Map.of("source", "in/b.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);
        node.set("node-1");

        List<Transfer> reclaimed = transfers.reclaimOwned("node-1");

        assertEquals(List.of(mine), ids(reclaimed));
        assertEquals(TransferStatus.QUEUED.name(), transfers.find(mine).status());
        assertEquals("node-2 is alive and still writing it",
                TransferStatus.RUNNING.name(), transfers.find(theirs).status());
    }

    /** A finished transfer is not stranded, and a sweep that requeued one would re-send a file. */
    @Test
    public void aSweepLeavesFinishedAndWaitingTransfersAlone() throws Exception {
        long done = transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER).jobId();
        transfers.claim(STORE);
        transfers.finish(done, TransferStatus.DONE, null, null);
        long waiting = transfers.submit(STORE, Map.of("source", "in/b.csv"), null, SUBMITTER).jobId();

        assertTrue(transfers.reclaimOwned("node-1").isEmpty());

        assertEquals(TransferStatus.DONE.name(), transfers.find(done).status());
        assertEquals(TransferStatus.QUEUED.name(), transfers.find(waiting).status());
    }

    /**
     * A node with no id writes no owner, and the count is what the startup message reports — "three
     * transfers are stuck" being actionable where "set node_id" alone reads as boilerplate.
     */
    @Test
    public void transfersClaimedWithoutANodeIdAreCountedAsUnrecoverable() throws Exception {
        node.set(null);
        transfers.submit(STORE, Map.of("source", "in/a.csv"), null, SUBMITTER);
        transfers.claim(STORE);

        assertEquals(1, transfers.unownedRunning());
        try {
            transfers.reclaimOwned(null);
            fail("a sweep with no owner would match every other node's unowned rows");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("startup sweep"));
        }
    }

    private Thread claimer(CountDownLatch go, AtomicReference<Transfer> into) {
        return new Thread(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                into.set(transfers.claim(STORE));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static List<Long> ids(List<Transfer> found) {
        List<Long> ids = new ArrayList<>();
        for (Transfer transfer : found) {
            ids.add(transfer.jobId());
        }
        return ids;
    }

    // ---------------------------------------------------------------- pause, start and stop

    /**
     * Puts a transfer into the state a paused run leaves behind, with the two things a stop has to
     * reclaim: checkpoint rows, and a workspace directory holding a file.
     *
     * @return the paused transfer's job id
     */
    private long pausedTransferWithState(Path workspace) throws Exception {
        Submission submission = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null,
                SUBMITTER);
        long jobId = submission.jobId();
        Transfer claimed = transfers.claim(STORE);
        transfers.linkRun(jobId, claimed.runName());
        String runId = claimed.runName();

        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("stage-0.part"), "partial output");

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO MFT_JOB (RUN_ID, SEQUENCE_NAME, STATUS, RESUMABLE,"
                    + " ATTEMPT, WORKSPACE_PATH, CREATED_AT) VALUES ('" + runId + "', 'queued-copy',"
                    + " 'PAUSED', 1, 1, '" + workspace.toString().replace("\\", "/") + "', 1)");
            for (String stage : new String[] {"project", "summarise"}) {
                st.executeUpdate("INSERT INTO MFT_CHECKPOINT (RUN_ID, STAGE_NAME, POSITION_VALUE,"
                        + " POSITION_UNIT, ARTIFACT_LENGTH, FORMAT_VERSION, UPDATED_AT) VALUES ('"
                        + runId + "', '" + stage + "', 42, 'RECORDS', 4096, 'v1', 1)");
            }
        }

        assertEquals(TransferStatus.PAUSING, transfers.requestPause(jobId));
        assertEquals("the run settles the resting state itself",
                TransferStatus.PAUSED, transfers.stopped(jobId));
        return jobId;
    }

    /** A running transfer cannot be stopped outright, because cancellation is cooperative. */
    @Test
    public void pausingARunningTransferWindsItDownRatherThanStoppingItAtOnce() throws Exception {
        transfers.submit(STORE, Map.of("source", "in/orders.csv"), null, SUBMITTER);
        Transfer claimed = transfers.claim(STORE);

        assertEquals(TransferStatus.PAUSING, transfers.requestPause(claimed.jobId()));
        assertEquals(TransferStatus.PAUSING.name(), transfers.find(claimed.jobId()).status());
        assertNull("PAUSING is not claimable, which is what removes it from the queue",
                transfers.claim(STORE));
    }

    /**
     * The bug this locks down: a paused transfer could not be stopped at all.
     *
     * <p>{@code requestStop} tried {@code RUNNING} and then {@code QUEUED}, so a {@code PAUSED} row
     * matched neither statement, nothing was updated, and the API answered "not in a state that can
     * stop" about a transfer sitting there holding a workspace.
     */
    @Test
    public void aPausedTransferCanBeStopped() throws Exception {
        Path workspace = Files.createTempDirectory("mft-stop-paused");
        long jobId = pausedTransferWithState(workspace);

        assertEquals("a paused transfer has nothing to wind down, so it stops at once",
                TransferStatus.CANCELLED, transfers.requestStop(jobId));
        assertEquals(TransferStatus.CANCELLED.name(), transfers.find(jobId).status());
    }

    /** Stop's whole purpose: unlike pause, nothing is left to resume from. */
    @Test
    public void stoppingAPausedTransferDiscardsItsCheckpointsAndWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("mft-stop-paused");
        long jobId = pausedTransferWithState(workspace);

        transfers.requestStop(jobId);

        assertFalse("the workspace is what a resumed attempt would read", Files.exists(workspace));
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM MFT_CHECKPOINT")) {
                rs.next();
                assertEquals("checkpoints outlive the directory unless they are cleared too",
                        0, rs.getInt(1));
            }
            // Both rows must agree, or a reader cannot tell which of them lied (ADR-0052).
            try (java.sql.ResultSet rs = st.executeQuery("SELECT STATUS FROM MFT_JOB")) {
                rs.next();
                assertEquals(TransferStatus.CANCELLED.name(), rs.getString(1));
            }
        }
    }

    /** Because its state is gone: starting it would resume from checkpoints that no longer exist. */
    @Test
    public void aTransferStoppedWhilePausedCannotBeStarted() throws Exception {
        Path workspace = Files.createTempDirectory("mft-stop-paused");
        long jobId = pausedTransferWithState(workspace);
        transfers.requestStop(jobId);

        assertFalse(transfers.start(jobId));
        assertNull("and nothing may claim it either", transfers.claim(STORE));
    }

    /** Pause is the reversible one: start puts it back, claimable, with the attempt count reset. */
    @Test
    public void startingAPausedTransferMakesItClaimableAgainAndKeepsItsState() throws Exception {
        Path workspace = Files.createTempDirectory("mft-stop-paused");
        long jobId = pausedTransferWithState(workspace);

        assertTrue(transfers.start(jobId));
        assertEquals(TransferStatus.QUEUED.name(), transfers.find(jobId).status());
        assertEquals("an operator asking for this has overridden the retry bound",
                0, transfers.find(jobId).attempt());
        assertTrue("start resumes into the workspace, so it must still be there",
                Files.exists(workspace.resolve("stage-0.part")));

        Transfer again = transfers.claim(STORE);
        assertEquals(jobId, again.jobId());
    }

    /** A transfer that already finished is not a transfer anyone can stop. */
    @Test
    public void stoppingAFinishedTransferChangesNothing() throws Exception {
        Submission submission = transfers.submit(STORE, Map.of("source", "in/orders.csv"), null,
                SUBMITTER);
        transfers.claim(STORE);
        transfers.finish(submission.jobId(), TransferStatus.DONE, null, null);

        assertNull(transfers.requestStop(submission.jobId()));
        assertEquals(TransferStatus.DONE.name(), transfers.find(submission.jobId()).status());
    }
}
