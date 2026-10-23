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
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.stream.StreamException;
import org.apache.synapse.stream.transfer.Transfer;
import org.apache.synapse.stream.transfer.TransferStatus;
import org.apache.synapse.stream.transfer.TransferStore;
import org.apache.synapse.stream.transfer.TransferStores;
import org.wso2.carbon.inbound.endpoint.internal.http.api.APIResource;
import org.wso2.micro.core.util.AuditLogger;
import org.wso2.micro.integrator.management.apis.security.handler.SecurityUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What became of a submitted transfer.
 *
 * <pre>
 *   GET  /management/mft/jobs/{jobId}
 *   GET  /management/mft/jobs?store=mft-queue&amp;status=QUEUED&amp;limit=50
 *   POST /management/mft/jobs        {"jobId": 1423, "status": "CANCELLED"}
 * </pre>
 *
 * <p>The id is the number a submitter was handed by {@code <mftSubmit/>}, which is the whole reason it
 * is a number: a person reads it back over the phone, quotes it in a ticket and types it here.
 *
 * <h2>It reads through the store, not its own datasource</h2>
 * {@code CoordinatedTaskStatusResource} opens a Carbon datasource directly, and it is the closest
 * structural precedent — but MFT already has one place that owns its connection, and a second would be
 * a second thing to configure and a second thing to get wrong. This asks {@link TransferStores}, which
 * is registered by the bundle that owns the datasource.
 */
public class MftJobResource extends APIResource {

    private static final Log LOG = LogFactory.getLog(MftJobResource.class);

    private static final String JOB_ID = "jobId";
    private static final String STORE = "store";
    private static final String STATUS = "status";
    private static final String LIMIT = "limit";
    private static final String ACTION = "action";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_CANCEL = "cancel";
    private static final int DEFAULT_LIMIT = 50;

    private final Set<String> methods;

    public MftJobResource(String urlTemplate) {
        super(urlTemplate);
        methods = new HashSet<>();
        methods.add(Constants.HTTP_GET);
        methods.add(Constants.HTTP_POST);
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

        TransferStore transfers = TransferStores.store();
        if (transfers == null) {
            // Not a degraded 200. A status endpoint that answers "no jobs" when it simply cannot see
            // the table is worse than one that says it is broken, because the answer looks like news.
            Utils.setJsonPayLoad(axis2MessageContext, Utils.createJsonErrorObject(
                    "MFT is not configured on this node: no datasource, so no transfers are recorded"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE,
                    Constants.INTERNAL_SERVER_ERROR);
            return true;
        }

        try {
            if (messageContext.isDoingGET()) {
                String id = Utils.getPathParameter(messageContext, JOB_ID);
                if (Objects.nonNull(id) && !id.trim().isEmpty()) {
                    one(axis2MessageContext, transfers, id.trim());
                } else {
                    many(messageContext, axis2MessageContext, transfers);
                }
            } else if (Utils.isDoingPOST(axis2MessageContext)) {
                cancel(messageContext, axis2MessageContext, transfers);
            } else {
                Utils.setJsonPayLoad(axis2MessageContext,
                        Utils.createJsonErrorObject("Unsupported HTTP method"));
            }
        } catch (StreamException e) {
            LOG.error("could not read MFT transfers", e);
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("Could not read MFT transfers: " + e.getMessage()));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE,
                    Constants.INTERNAL_SERVER_ERROR);
        }
        axis2MessageContext.removeProperty(Constants.NO_ENTITY_BODY);
        return true;
    }

    private void one(org.apache.axis2.context.MessageContext axis2MessageContext,
                     TransferStore transfers, String id) throws StreamException {
        long jobId;
        try {
            jobId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            Utils.setJsonPayLoad(axis2MessageContext, Utils.createJsonErrorObject(
                    "'" + id + "' is not a job id; they are the numbers returned when a transfer is"
                            + " submitted"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            return;
        }
        Transfer transfer = transfers.find(jobId);
        if (transfer == null) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("No transfer with job id " + jobId));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.NOT_FOUND);
            return;
        }
        Utils.setJsonPayLoad(axis2MessageContext, describe(transfer));
    }

    private void many(MessageContext messageContext,
                      org.apache.axis2.context.MessageContext axis2MessageContext,
                      TransferStore transfers) throws StreamException {

        String store = Utils.getQueryParameter(messageContext, STORE);
        String status = Utils.getQueryParameter(messageContext, STATUS);
        int limit = DEFAULT_LIMIT;
        String configured = Utils.getQueryParameter(messageContext, LIMIT);
        if (Objects.nonNull(configured)) {
            try {
                limit = Math.max(1, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException e) {
                LOG.debug("ignoring a limit of '" + configured + "', which is not a number");
            }
        }

        List<Transfer> found = transfers.list(store, status == null ? null : status.toUpperCase(),
                limit);
        JsonArray list = new JsonArray();
        for (Transfer transfer : found) {
            list.add(describe(transfer));
        }
        JsonObject body = new JsonObject();
        body.addProperty("count", found.size());
        body.add("list", list);
        Utils.setJsonPayLoad(axis2MessageContext, body);
    }

    private void cancel(MessageContext messageContext,
                        org.apache.axis2.context.MessageContext axis2MessageContext,
                        TransferStore transfers) throws StreamException {

        String userName = (String) messageContext.getProperty(Constants.USERNAME_PROPERTY);
        try {
            if (!SecurityUtils.canUserEdit(messageContext, userName)) {
                Utils.sendForbiddenFaultResponse(axis2MessageContext);
                return;
            }
        } catch (Exception e) {
            LOG.error("could not determine whether the user may cancel a transfer", e);
            Utils.sendForbiddenFaultResponse(axis2MessageContext);
            return;
        }
        if (!JsonUtil.hasAJsonPayload(axis2MessageContext)) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("A payload naming the job id is required"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            return;
        }

        JsonObject payload;
        try {
            payload = Utils.getJsonPayload(axis2MessageContext);
        } catch (Exception e) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("Could not read the payload as JSON"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            return;
        }
        if (!payload.has(JOB_ID)) {
            Utils.setJsonPayLoad(axis2MessageContext,
                    Utils.createJsonErrorObject("The payload must name a jobId"));
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            return;
        }

        long jobId = payload.get(JOB_ID).getAsLong();
        String action = payload.has(ACTION)
                ? payload.get(ACTION).getAsString().trim().toLowerCase(java.util.Locale.ENGLISH)
                : ACTION_CANCEL;

        JsonObject body = new JsonObject();
        body.addProperty(JOB_ID, jobId);
        body.addProperty(ACTION, action);

        switch (action) {
            case ACTION_PAUSE -> {
                // Stops at the next unit and KEEPS the workspace and checkpoints, so start resumes.
                TransferStatus now = transfers.requestPause(jobId);
                describeRequest(body, now, "pause",
                        "it had not started, so the request is already met");
            }
            case ACTION_STOP -> {
                // Stops at the next unit and DISCARDS them. Irreversible, which is why a UI should
                // confirm before calling this.
                TransferStatus now = transfers.requestStop(jobId);
                describeRequest(body, now, "stop",
                        "it was not running, so it stopped at once; any workspace and checkpoints it"
                                + " had are discarded");
            }
            case ACTION_START -> {
                boolean started = transfers.start(jobId);
                body.addProperty("accepted", started);
                body.addProperty(STATUS, started ? TransferStatus.QUEUED.name() : null);
                body.addProperty("reason", started
                        // Not a promise of execution: a processor claims it on its next poll, behind
                        // whatever else is waiting and bounded by its concurrency.
                        ? "queued; it resumes from its last checkpoint when a processor claims it"
                        : "only a paused or failed transfer can be started");
            }
            case ACTION_CANCEL -> {
                boolean cancelled = transfers.cancel(jobId);
                body.addProperty("accepted", cancelled);
                if (!cancelled) {
                    // Deliberately not an error. A transfer that has already started belongs to the
                    // node running it; use stop for that.
                    body.addProperty("reason", "the transfer is not waiting: it has started, finished,"
                            + " or does not exist. Use action=stop to end a running transfer");
                }
            }
            default -> {
                Utils.setJsonPayLoad(axis2MessageContext, Utils.createJsonErrorObject(
                        "Unknown action '" + action + "'; expected pause, start, stop or cancel"));
                axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
                return;
            }
        }

        Utils.setJsonPayLoad(axis2MessageContext, body);
        org.json.JSONObject audit = new org.json.JSONObject();
        audit.put(JOB_ID, jobId);
        audit.put(ACTION, action);
        AuditLogger.logAuditMessage(userName, "mft-transfer", action, audit);
    }

    /**
     * Reports what a pause or stop request achieved.
     *
     * <p>The distinction the response has to carry is <b>requested</b> versus <b>done</b>: cancellation
     * is cooperative, so a running transfer enters a winding-down state and stops up to one unit later.
     * A caller that read "paused" and then saw bytes still moving would think the API had lied.
     */
    private static void describeRequest(JsonObject body, TransferStatus now, String what,
                                        String atRest) {
        body.addProperty("accepted", now != null);
        body.addProperty(STATUS, now == null ? null : now.name());
        if (now == null) {
            body.addProperty("reason", "the transfer is not in a state that can " + what
                    + ": it has finished, is already stopping, or does not exist");
        } else if (now.isStopping()) {
            body.addProperty("reason", "it is running, so it stops at its next unit");
        } else {
            // Supplied per action rather than fixed: a transfer can reach a resting state from more
            // than one place, and "it had not started" is false of a paused one that a stop ended.
            body.addProperty("reason", atRest);
        }
    }

    /**
     * One transfer as JSON.
     *
     * <p>"Retried" is reported rather than stored: a transfer on its second attempt says so through
     * {@code attempt}, and one waiting for another go is {@code QUEUED} with a {@code nextAttemptAt} in
     * the future. Two facts that cannot contradict each other, unlike a status that means the same.
     */
    private static JsonObject describe(Transfer transfer) {
        JsonObject body = new JsonObject();
        body.addProperty(JOB_ID, transfer.jobId());
        body.addProperty(STORE, transfer.storeName());
        body.addProperty(STATUS, transfer.status());
        body.addProperty("attempt", transfer.attempt());
        if (transfer.nextAttemptAt() > 0L) {
            body.addProperty("nextAttemptAt", transfer.nextAttemptAt());
        }
        if (transfer.submitter() != null) {
            // Half of the run's invocation path, and the half only this row knows -- the store and the
            // processor are known to whoever claims it. The whole path is on the run itself:
            //   GET /management/mft/runs?invoker=<submitter>
            body.addProperty("submitter", transfer.submitter());
        }
        if (transfer.runId() != null) {
            body.addProperty("runId", transfer.runId());
        }
        body.addProperty("createdAt", transfer.createdAt());
        if (transfer.updatedAt() > 0L) {
            body.addProperty("updatedAt", transfer.updatedAt());
        }
        if (transfer.endedAt() > 0L) {
            body.addProperty("endedAt", transfer.endedAt());
        }

        JsonObject parameters = new JsonObject();
        for (Map.Entry<String, String> parameter : transfer.parameters().entrySet()) {
            parameters.addProperty(parameter.getKey(), parameter.getValue());
        }
        body.add("parameters", parameters);

        if (transfer.errorCode() != null || transfer.errorMessage() != null) {
            JsonObject error = new JsonObject();
            error.addProperty("code", transfer.errorCode());
            error.addProperty("message", transfer.errorMessage());
            body.add("error", error);
        }
        return body;
    }
}
