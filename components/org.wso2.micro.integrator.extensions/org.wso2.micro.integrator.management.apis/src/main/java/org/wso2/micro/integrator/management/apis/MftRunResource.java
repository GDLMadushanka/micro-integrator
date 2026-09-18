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

package org.wso2.micro.integrator.management.apis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.stream.Run;
import org.apache.synapse.stream.RunStore;
import org.apache.synapse.stream.RunStores;
import org.apache.synapse.stream.Stage;
import org.apache.synapse.stream.StreamException;
import org.apache.synapse.stream.StreamSeed;
import org.wso2.carbon.inbound.endpoint.internal.http.api.APIResource;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * What has actually run, and what it came through.
 *
 * <pre>
 *   GET /management/mft/runs                          every run, newest first
 *   GET /management/mft/runs?invoker=mft-jobs         everything that came through that API
 *   GET /management/mft/runs?invoker=mft-runner       everything that processor ran
 *   GET /management/mft/runs?pipeline=queued-rows&amp;status=FAILED
 *   GET /management/mft/runs?type=INBOUND             synchronous, from a file inbound endpoint
 *   GET /management/mft/runs/mft-job-1423             one run
 *   GET /management/mft/runs/mft-job-1423/stages      what each stage moved, and how long it took
 * </pre>
 *
 * <h2>Why this exists beside {@code /mft/jobs}</h2>
 * They answer different questions, because a submission and a run are different things (ADR-0044).
 * {@code /mft/jobs} lists <b>submissions</b> — what was asked for, including what is still queued and
 * what was cancelled before it ever ran. This lists <b>executions</b>, which is the only place a
 * synchronous transfer appears at all: an inbound endpoint or an API that invokes a pipeline inline
 * never passes through a queue, so it has no submission to list.
 *
 * <h2>invoker is the filter this was built for</h2>
 * A run records the whole chain it came through, not just the nearest artifact — for a queued transfer
 * {@code mft-jobs -> mft-queue -> mft-runner}. {@code invoker} matches <b>any segment</b> of that, so
 * the same parameter answers "what did this API ask for", "what is in this store's history" and "what
 * has this processor run", without three different parameters that would each be wrong for the next
 * topology. Matching is whole-segment, so {@code api1} does not answer for {@code api10}.
 */
public class MftRunResource extends APIResource {

    private static final Log LOG = LogFactory.getLog(MftRunResource.class);

    private static final String RUN_ID = "runId";
    private static final String PIPELINE = "pipeline";
    private static final String STATUS = "status";
    private static final String TYPE = "type";
    private static final String INVOKER = "invoker";
    private static final String LIMIT = "limit";
    private static final int DEFAULT_LIMIT = 50;

    private final Set<String> methods;

    /**
     * Which of the two shapes this instance serves, decided by the template it was registered with
     * rather than by inspecting the request. The registration is a fact; a path property is a guess,
     * and the first version of this sniffed {@code REST_SUB_REQUEST_PATH} and silently served the run
     * object from the stages URL.
     */
    private final boolean servesStages;

    public MftRunResource(String urlTemplate) {
        super(urlTemplate);
        servesStages = urlTemplate != null && urlTemplate.endsWith("/stages");
        methods = new HashSet<>();
        methods.add(Constants.HTTP_GET);
    }

    @Override
    public Set<String> getMethods() {
        return methods;
    }

    @Override
    public boolean invoke(MessageContext messageContext) {
        buildMessage(messageContext);
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        RunStore runs = RunStores.store();
        if (runs == null) {
            // Not an empty list. "No runs" and "I cannot see the table" must not read the same, because
            // the first looks like news and the second is a broken server.
            Utils.setJsonPayLoad(axis2MessageContext, Utils.createJsonErrorObject(
                    "MFT is not configured on this node: no datasource, so no runs are recorded"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE,
                    Constants.INTERNAL_SERVER_ERROR);
            return true;
        }

        try {
            if (!messageContext.isDoingGET()) {
                Utils.setJsonPayLoad(axis2MessageContext,
                        Utils.createJsonErrorObject("Unsupported HTTP method"));
            } else {
                String runId = Utils.getPathParameter(messageContext, RUN_ID);
                if (Objects.isNull(runId) || runId.trim().isEmpty()) {
                    many(messageContext, axis2MessageContext, runs);
                } else if (servesStages) {
                    stages(axis2MessageContext, runs, runId.trim());
                } else {
                    one(axis2MessageContext, runs, runId.trim());
                }
            }
        } catch (StreamException e) {
            LOG.error("could not read MFT runs", e);
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("Could not read MFT runs: " + e.getMessage()));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE,
                    Constants.INTERNAL_SERVER_ERROR);
        }
        axis2MessageContext.removeProperty(Constants.NO_ENTITY_BODY);
        return true;
    }

    /**
     * The pipeline, stage by stage: what each one moved and how long it took.
     *
     * <p>A separate path rather than a fatter run object, because it is a second query and most callers
     * listing runs do not want it. Empty until the run ends — every stage's row is written together in
     * the run's final accounting — so {@code complete} says which of "nothing to show yet" and "nothing
     * happened" a caller is looking at.
     */
    private void stages(org.apache.axis2.context.MessageContext axis2MessageContext, RunStore runs,
                        String runId) throws StreamException {
        Run run = runs.find(runId);
        if (run == null) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("No run with id '" + runId + "'"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.NOT_FOUND);
            return;
        }

        List<Stage> found = runs.stages(runId);
        long totalSelfNanos = 0L;
        for (Stage stage : found) {
            totalSelfNanos += stage.selfNanos();
        }

        JsonArray list = new JsonArray();
        for (Stage stage : found) {
            JsonObject body = new JsonObject();
            body.addProperty("stage", stage.stageName());
            body.addProperty("index", stage.index());
            body.addProperty(STATUS, stage.status());
            body.addProperty("executed", stage.executed());
            body.addProperty("bytesIn", stage.bytesIn());
            body.addProperty("bytesOut", stage.bytesOut());
            body.addProperty("selfNanos", stage.selfNanos());
            body.addProperty("selfMillis", stage.selfMillis());
            if (stage.hasMediation()) {
                // Only where there is a sequence. Emitting zeros for every other stage would invite a
                // chart to draw a "mediation" band for stages that cannot have one.
                body.addProperty("mediationMillis", stage.mediationMillis());
                body.addProperty("loopingMillis", stage.loopingMillis());
                body.addProperty("mediationShare", stage.selfNanos() == 0L ? 0d
                        : Math.round(stage.mediationNanos() * 10000d / stage.selfNanos()) / 10000d);
            }
            // Precomputed because it is the whole point of the endpoint -- a caller drawing a bar per
            // stage should not have to sum the list first, and a sum done here cannot disagree with the
            // total reported beside it.
            body.addProperty("selfShare", totalSelfNanos == 0L ? 0d
                    : Math.round(stage.selfNanos() * 10000d / totalSelfNanos) / 10000d);
            body.addProperty("updatedAt", stage.updatedAt());
            list.add(body);
        }

        JsonObject body = new JsonObject();
        body.addProperty(RUN_ID, run.runId());
        body.addProperty(PIPELINE, run.pipelineName());
        body.addProperty(STATUS, run.status());
        body.addProperty("count", found.size());
        body.addProperty("totalSelfNanos", totalSelfNanos);
        body.addProperty("totalSelfMillis", totalSelfNanos / 1_000_000L);
        // The headline the operator actually wants: of everything this transfer spent, how much was
        // the sequences they wrote. Summed here so a client cannot compute it differently.
        long mediation = 0L;
        for (Stage stage : found) {
            if (stage.hasMediation()) {
                mediation += stage.mediationNanos();
            }
        }
        body.addProperty("totalMediationMillis", mediation / 1_000_000L);
        body.addProperty("totalMediationShare", totalSelfNanos == 0L ? 0d
                : Math.round(mediation * 10000d / totalSelfNanos) / 10000d);
        // Stage rows land only when the run finishes. Without this a caller cannot tell a run still
        // moving bytes from one that somehow recorded no stages at all.
        body.addProperty("complete", !found.isEmpty());
        body.add("stages", list);
        Utils.setJsonPayLoad(axis2MessageContext, body);
    }

    private void one(org.apache.axis2.context.MessageContext axis2MessageContext, RunStore runs,
                     String runId) throws StreamException {
        Run run = runs.find(runId);
        if (run == null) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("No run with id '" + runId + "'"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.NOT_FOUND);
            return;
        }
        Utils.setJsonPayLoad(axis2MessageContext, describe(run));
    }

    private void many(MessageContext messageContext,
                      org.apache.axis2.context.MessageContext axis2MessageContext, RunStore runs)
            throws StreamException {

        int limit = DEFAULT_LIMIT;
        String configured = Utils.getQueryParameter(messageContext, LIMIT);
        if (Objects.nonNull(configured)) {
            try {
                limit = Math.max(1, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException e) {
                LOG.debug("ignoring a limit of '" + configured + "', which is not a number");
            }
        }

        String invoker = Utils.getQueryParameter(messageContext, INVOKER);
        List<Run> found = runs.list(new RunStore.RunFilter(
                Utils.getQueryParameter(messageContext, PIPELINE),
                Utils.getQueryParameter(messageContext, STATUS),
                Utils.getQueryParameter(messageContext, TYPE),
                invoker,
                limit));

        JsonArray list = new JsonArray();
        for (Run run : found) {
            list.add(describe(run));
        }
        JsonObject body = new JsonObject();
        body.addProperty("count", found.size());
        if (Objects.nonNull(invoker) && !invoker.trim().isEmpty()) {
            // Echoed so a caller can see what the filter was taken to mean, which matters here because
            // the match is whole-segment and a near miss otherwise looks like "no runs".
            body.addProperty("invoker", invoker.trim());
        }
        body.add("list", list);
        Utils.setJsonPayLoad(axis2MessageContext, body);
    }

    /** One run as JSON. Nulls are omitted rather than serialised, so absent reads as absent. */
    private JsonObject describe(Run run) {
        JsonObject body = new JsonObject();
        body.addProperty(RUN_ID, run.runId());
        body.addProperty(PIPELINE, run.pipelineName());
        body.addProperty(STATUS, run.status());
        body.addProperty("attempt", run.attempt());
        body.addProperty("resumable", run.resumable());

        JsonObject invoker = new JsonObject();
        if (run.invokerType() != null) {
            invoker.addProperty("type", run.invokerType());
        }
        if (run.invokerName() != null) {
            invoker.addProperty("path", run.invokerName());
            JsonArray through = new JsonArray();
            // Also as an array, because the path is a list that happens to be stored as a string and a
            // client filtering or displaying it should not have to re-split on a separator.
            run.invocationPath().forEach(through::add);
            invoker.add("through", through);
        }
        body.add("invoker", invoker);

        if (run.ownerNode() != null) {
            body.addProperty("ownerNode", run.ownerNode());
        }
        if (run.currentStage() != null) {
            body.addProperty("stage", run.currentStage());
        }
        body.addProperty("bytesDone", run.bytesDone());
        body.addProperty("recordsDone", run.recordsDone());

        if (run.sourceId() != null) {
            JsonObject source = new JsonObject();
            source.addProperty("identity", run.sourceId());
            if (run.sourceSize() != StreamSeed.UNKNOWN) {
                source.addProperty("size", run.sourceSize());
            }
            body.add("source", source);
        }
        if (run.errorCode() != null || run.errorMessage() != null) {
            JsonObject error = new JsonObject();
            error.addProperty("code", run.errorCode());
            error.addProperty("message", run.errorMessage());
            if (run.failedStage() != null) {
                error.addProperty("stage", run.failedStage());
            }
            if (run.retryable() != null) {
                error.addProperty("retryable", run.retryable());
            }
            body.add("error", error);
        }

        body.addProperty("createdAt", run.createdAt());
        if (run.startedAt() > 0L) {
            body.addProperty("startedAt", run.startedAt());
        }
        if (run.updatedAt() > 0L) {
            body.addProperty("updatedAt", run.updatedAt());
        }
        if (run.endedAt() > 0L) {
            body.addProperty("endedAt", run.endedAt());
        }
        return body;
    }
}
