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

import org.apache.synapse.stream.JobRun;
import org.apache.synapse.stream.Run;
import org.apache.synapse.stream.RunStore;
import org.apache.synapse.stream.Stage;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reading runs back, and filtering them by the artifacts they came through.
 *
 * <p>Run over H2 with the <b>shipped</b> {@code h2_mft.sql}, so the column widths and the statements are
 * checked against each other — an invocation path is the longest thing either table stores, and a path
 * that does not fit is a defect the DDL and the code have to agree about.
 */
public class JdbcRunStoreTest {

    private JdbcRunStore runs;
    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        Path script = Path.of("..", "..", "distribution", "src", "dbscripts", "h2", "h2_mft.sql")
                .toAbsolutePath().normalize();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(Files.readString(script));
        }
        runs = new JdbcRunStore(dataSource);
    }

    @Test
    public void aRunComesBackWithItsInvocationPathSplitUp() throws Exception {
        record_("mft-1", "queued-rows", "QUEUED", "mft-jobs -> mft-queue -> mft-runner", "DONE");

        Run run = runs.find("mft-1");

        assertEquals("queued-rows", run.pipelineName());
        assertEquals("QUEUED", run.invokerType());
        assertEquals("mft-jobs -> mft-queue -> mft-runner", run.invokerName());
        assertEquals(List.of("mft-jobs", "mft-queue", "mft-runner"), run.invocationPath());
    }

    /**
     * The whole point of one column holding a path: the same parameter answers "what did this API ask
     * for", "what is in this store's history" and "what has this processor run".
     */
    @Test
    public void anyArtifactInThePathFindsTheRun() throws Exception {
        record_("mft-1", "queued-rows", "QUEUED", "mft-jobs -> mft-queue -> mft-runner", "DONE");
        record_("mft-2", "resume-demo", "INBOUND", "mft-file-resume", "DONE");

        assertEquals(List.of("mft-1"), ids(list("mft-jobs")));
        assertEquals("the middle segment too", List.of("mft-1"), ids(list("mft-queue")));
        assertEquals("and the last", List.of("mft-1"), ids(list("mft-runner")));
        assertEquals("a synchronous run has one segment and is found by it",
                List.of("mft-2"), ids(list("mft-file-resume")));
    }

    /**
     * The defect a LIKE '%value%' would have on its own, and the reason the SQL is only a pre-filter.
     * Two artifacts whose names are prefixes of one another are ordinary — api, api1, api10.
     */
    @Test
    public void aFilterMatchesWholeSegmentsAndNotSubstrings() throws Exception {
        record_("mft-1", "p", "QUEUED", "api1 -> queue -> runner", "DONE");
        record_("mft-2", "p", "QUEUED", "api10 -> queue -> runner", "DONE");

        assertEquals("api1 must not answer for api10", List.of("mft-1"), ids(list("api1")));
        assertEquals(List.of("mft-2"), ids(list("api10")));
        assertEquals("both came through the same queue", 2, list("queue").size());
    }

    @Test
    public void aRunWithNoIdentifiedInvokerIsSimplyNotMatched() throws Exception {
        record_("mft-1", "p", "DIRECT", null, "DONE");
        record_("mft-2", "p", "QUEUED", "api1 -> queue -> runner", "DONE");

        assertEquals(List.of("mft-2"), ids(list("api1")));
        assertEquals("but it is still listed when nothing is filtered on", 2, list(null).size());
    }

    @Test
    public void listingIsNewestFirstAndBounded() throws Exception {
        for (int i = 1; i <= 5; i++) {
            record_("mft-" + i, "p", "QUEUED", "api -> queue -> runner", "DONE", i * 1000L);
        }
        List<Run> found = runs.list(new RunStore.RunFilter(null, null, null, null, 3));

        assertEquals(3, found.size());
        assertEquals(List.of("mft-5", "mft-4", "mft-3"), ids(found));
    }

    /**
     * The limit is applied after the segment refinement, so a page of 2 must be 2 matching runs and not
     * "the first 2 rows the LIKE found, of which some matched".
     */
    @Test
    public void theLimitCountsMatchesNotCandidates() throws Exception {
        for (int i = 1; i <= 4; i++) {
            record_("near-" + i, "p", "QUEUED", "api10 -> queue -> runner", "DONE", i * 10L);
        }
        for (int i = 1; i <= 3; i++) {
            record_("hit-" + i, "p", "QUEUED", "api1 -> queue -> runner", "DONE", 1000L + i);
        }
        List<Run> found = runs.list(new RunStore.RunFilter(null, null, null, "api1", 2));

        assertEquals(2, found.size());
        for (Run run : found) {
            assertTrue(run.runId().startsWith("hit-"));
        }
    }

    @Test
    public void filtersCombine() throws Exception {
        record_("mft-1", "queued-rows", "QUEUED", "api1 -> queue -> runner", "DONE");
        record_("mft-2", "queued-rows", "QUEUED", "api1 -> queue -> runner", "FAILED");
        record_("mft-3", "other", "QUEUED", "api1 -> queue -> runner", "FAILED");

        assertEquals(List.of("mft-2"), ids(runs.list(
                new RunStore.RunFilter("queued-rows", "FAILED", "QUEUED", "api1", 50))));
    }

    /**
     * The stage breakdown the dashboard draws. Ordered by index rather than by name, because the order
     * is the pipeline's shape.
     */
    @Test
    public void stagesComeBackInPipelineOrder() throws Exception {
        record_("mft-job-1", "p", "QUEUED", "api -> q -> r", "DONE");
        stage("mft-job-1", "file.streamWrite", 2, "DONE", 5789998L, 5789998L, 4_000_000_000L);
        stage("mft-job-1", "file.streamRead", 0, "DONE", 0L, 2678912L, 1_000_000_000L);
        stage("mft-job-1", "rows", 1, "DONE", 2678912L, 5789998L, 9_000_000_000L);

        List<Stage> stages = runs.stages("mft-job-1");

        assertEquals(List.of("file.streamRead", "rows", "file.streamWrite"),
                stages.stream().map(Stage::stageName).toList());
        assertEquals(2678912L, stages.get(0).bytesOut());
        assertEquals(9000L, stages.get(1).selfMillis());
        assertTrue(stages.get(1).executed());
    }

    /** A resumed run inherits finished segments; they are recorded, not silently absent. */
    @Test
    public void aSkippedStageIsReportedAsNotExecuted() throws Exception {
        record_("mft-job-2", "p", "QUEUED", "api -> q -> r", "DONE");
        stage("mft-job-2", "file.streamRead", 0, "SKIPPED", 0L, 0L, 0L);

        Stage skipped = runs.stages("mft-job-2").get(0);

        assertEquals("SKIPPED", skipped.status());
        assertFalse("a skipped segment did not run in this attempt", skipped.executed());
    }

    /**
     * Stage rows appear only when the run ends, so "still going" and "no stages" must be
     * distinguishable by the caller rather than by guessing.
     */
    @Test
    public void aRunningRunHasNoStagesYet() throws Exception {
        record_("mft-job-3", "p", "QUEUED", "api -> q -> r", "RUNNING");

        assertTrue(runs.stages("mft-job-3").isEmpty());
    }

    @Test
    public void stagesOfAnUnknownRunAreEmptyRatherThanAnError() throws Exception {
        assertTrue(runs.stages("mft-job-9999").isEmpty());
        assertTrue(runs.stages(null).isEmpty());
    }

    @Test
    public void anUnknownRunIdIsNullRatherThanAnError() throws Exception {
        assertNull(runs.find("mft-9999"));
        assertNull("and so is nothing at all", runs.find(""));
    }

    /**
     * The two durations exist because a resumed run makes them disagree, so the test that matters is
     * the one with a deliberate gap in the middle. Written against the real {@link JdbcJobStore}
     * rather than inserted rows: the accumulation happens in SQL, inside statements this test is here
     * to keep honest, and a hand-written row would prove only that the mapper reads two columns.
     */
    @Test
    public void aResumedRunSeparatesTimeElapsedFromTimeSpentWorking() throws Exception {
        JdbcJobStore jobs = new JdbcJobStore(dataSource, () -> "node-1");
        JobRun run = new JobRun("mft-resumed", "copy", "QUEUED", "q -> runner",
                true, "/ws/mft-resumed", "src", 1024L, 0L);

        jobs.start(run);
        Thread.sleep(50);
        jobs.failed("mft-resumed", "sink", "IO", "link dropped", true);
        Run afterFirst = runs.find("mft-resumed");
        long firstStart = afterFirst.firstStartedAt();
        long workedOnce = afterFirst.workingMillis();

        Thread.sleep(150);                       // the dead time: a retry wait, a server restart
        jobs.start(run);
        Thread.sleep(50);
        jobs.succeeded("mft-resumed", 1024L, 8L);

        Run finished = runs.find("mft-resumed");
        long now = System.currentTimeMillis();

        assertEquals("two attempts", 2, finished.attempt());
        assertEquals("the whole-run clock does not move when a transfer is re-claimed",
                firstStart, finished.firstStartedAt());
        assertTrue("the second attempt starts later than the first",
                finished.startedAt() > finished.firstStartedAt());
        assertTrue("working time accumulates across attempts rather than being replaced",
                finished.workingMillis() > workedOnce);
        assertTrue("elapsed spans the gap", finished.elapsedMillis(now) >= 250);
        assertTrue("processing excludes it",
                finished.elapsedMillis(now) - finished.processingMillis(now) >= 100);
    }

    /**
     * A clean run is the case where the two must agree, and it is worth asserting separately: an
     * accumulator that double-counted, or a first-start that tracked the latest attempt, would both
     * still pass the resumed test above while making every ordinary run report nonsense.
     */
    @Test
    public void aCleanRunReportsTheSameTimeTwice() throws Exception {
        JdbcJobStore jobs = new JdbcJobStore(dataSource, () -> "node-1");
        jobs.start(new JobRun("mft-clean", "copy", "API", "copy-api", false, null, "src", 10L, 0L));
        Thread.sleep(60);
        jobs.succeeded("mft-clean", 10L, 1L);

        Run finished = runs.find("mft-clean");
        long now = System.currentTimeMillis();

        assertEquals(1, finished.attempt());
        assertEquals("nothing was skipped, so there is no dead time to subtract",
                finished.elapsedMillis(now), finished.processingMillis(now), 5.0);
    }

    /**
     * An unfinished run has no ENDED_AT and nothing credited to WORKING_MILLIS yet, so both durations
     * would read zero if they were taken from the columns alone -- a running transfer appearing to
     * have done no work, which is the opposite of what a live view is for.
     */
    @Test
    public void aRunStillInFlightReportsTimeAgainstTheCallersClock() throws Exception {
        JdbcJobStore jobs = new JdbcJobStore(dataSource, () -> "node-1");
        jobs.start(new JobRun("mft-live", "copy", "API", "copy-api", false, null, "src", 10L, 0L));
        Thread.sleep(60);

        Run running = runs.find("mft-live");
        long now = System.currentTimeMillis();

        assertEquals(0L, running.endedAt());
        assertEquals("the column is still empty", 0L, running.workingMillis());
        assertTrue("but the run has been going for a while", running.elapsedMillis(now) >= 50);
        assertTrue("and working for it", running.processingMillis(now) >= 50);
    }

    // ------------------------------------------------------------------ fixtures

    private List<Run> list(String invoker) throws Exception {
        return runs.list(new RunStore.RunFilter(null, null, null, invoker, 50));
    }

    private static List<String> ids(List<Run> found) {
        List<String> ids = new ArrayList<>();
        for (Run run : found) {
            ids.add(run.runId());
        }
        return ids;
    }

    private void stage(String runId, String name, int index, String status, long bytesIn,
                       long bytesOut, long selfNanos) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO MFT_JOB_STAGE (RUN_ID, STAGE_NAME, STAGE_INDEX, STATUS, BYTES_IN,"
                             + " BYTES_OUT, SELF_NANOS, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, name);
            ps.setInt(3, index);
            ps.setString(4, status);
            ps.setLong(5, bytesIn);
            ps.setLong(6, bytesOut);
            ps.setLong(7, selfNanos);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private void record_(String runId, String pipeline, String invokerType, String invokerName,
                         String status) throws Exception {
        record_(runId, pipeline, invokerType, invokerName, status, System.currentTimeMillis());
    }

    private void record_(String runId, String pipeline, String invokerType, String invokerName,
                         String status, long createdAt) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO MFT_JOB (RUN_ID, PIPELINE_NAME, INVOKER_TYPE, INVOKER_NAME, STATUS,"
                             + " ATTEMPT, CREATED_AT) VALUES (?, ?, ?, ?, ?, 1, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, pipeline);
            ps.setString(3, invokerType);
            ps.setString(4, invokerName);
            ps.setString(5, status);
            ps.setLong(6, createdAt);
            ps.executeUpdate();
        }
    }
}
