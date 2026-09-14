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

import org.apache.axis2.util.GracefulShutdownTimer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.stream.StreamShutdown;
import org.wso2.config.mapper.ConfigParser;
import org.wso2.micro.core.WaitBeforeShutdownObserver;

/**
 * Tells in-flight stream transfers that the server is going down, and holds the shutdown briefly while
 * they wind down.
 *
 * <h2>Why a transfer needs telling at all</h2>
 * A transfer is not a request. Nothing else in the shutdown path knows one is running: the bytes move
 * on a thread belonging to an inbound endpoint's poll or an API mediation, and the transfer may have
 * hours of work left. Killing it outright is <b>safe</b> — {@code unitDone} fsyncs the artifact before
 * it advances the position, so the recorded position always describes work that landed — but it is
 * wasteful, and for a transfer nothing will redo it is worse than wasteful. Hence the two arms of
 * ADR-0037: a <b>resumable</b> run is asked to stop at its next unit and records what it earned; a
 * <b>non-resumable</b> one is left to finish, because stopping it destroys work that has no second
 * attempt. Synapse implements the split; this class raises the signal and waits.
 *
 * <h2>Why this interface</h2>
 * {@code ServerShutdownHandler} runs after the wait — too late to ask anything to stop, and too late
 * to be waited for. {@link WaitBeforeShutdownObserver} is the one hook called <em>before</em> the wait
 * and then polled until it says it is done, which is the shape this needs. It is reached on an ordinary
 * shutdown: the JVM shutdown hook calls {@code shutdownGracefully()}, which calls
 * {@code startMaintenanceForShutDown()}, which polls every observer.
 *
 * <h2>The poll loop has no sleep and no timeout</h2>
 * {@code ServerManagement.waitForServerTaskCompletion} is a bare {@code do/while} over every observer.
 * Two obligations follow, and both are load-bearing:
 * <ul>
 *   <li>{@link #isTaskComplete()} <b>blocks</b> for up to a second rather than answering immediately,
 *       or the loop spins a core for the whole grace period.</li>
 *   <li>It <b>stops saying no</b> at a deadline. An observer that never completes hangs the server's
 *       shutdown for ever, and nothing else bounds this loop.</li>
 * </ul>
 * Expiry is not a failure to escalate. It lands on abrupt termination, which is what would have
 * happened without any of this, and which is already correct.
 *
 * <h2>Which deadline</h2>
 * The server's own, wherever there is one: {@code shutdownGracefully()} starts
 * {@link GracefulShutdownTimer} from {@code GracefulShutdown.Timeout} (default 180 s) before the
 * observers are polled, and other components already yield to it. Honouring the same clock means MFT
 * gives up when everything else does, rather than competing with it.
 *
 * <p>Only when that timer is not running — {@code GracefulShutdown.Enabled=false}, or a graceful
 * restart, which starts no timer — does this fall back to its own bound:
 * <pre>
 *   [mft]
 *   shutdown_grace_seconds = 60
 * </pre>
 * 60 seconds by default, matching {@code ServerManagement}'s own wait for in-service requests. Zero or
 * less stops transfers without waiting for any of them.
 */
public final class MftShutdownObserver implements WaitBeforeShutdownObserver {

    private static final Log LOG = LogFactory.getLog(MftShutdownObserver.class);

    private static final String CONFIG_SECTION = "mft";
    private static final String CONFIG_GRACE_SECONDS = "shutdown_grace_seconds";
    private static final long DEFAULT_GRACE_SECONDS = 60L;

    /** One poll's wait. Long enough not to spin, short enough that the deadline is honoured closely. */
    private static final long POLL_MILLIS = 1000L;

    /** Set by {@link #startingShutdown()}, read by the polling thread. */
    private volatile long deadline;

    /** So giving up is logged once rather than once per poll. */
    private volatile boolean expiryReported;

    @Override
    public void startingShutdown() {
        int running = StreamShutdown.inFlight();

        long budgetMillis;
        String bound;
        long serverRemaining = GracefulShutdownTimer.getInstance().getRemainingTimeMillis();
        if (serverRemaining > 0L) {
            budgetMillis = serverRemaining;
            bound = "the server's graceful shutdown timeout";
        } else {
            budgetMillis = Math.max(0L, configuredGraceSeconds() * 1000L);
            bound = "[" + CONFIG_SECTION + "] " + CONFIG_GRACE_SECONDS;
        }
        deadline = System.currentTimeMillis() + budgetMillis;

        StreamShutdown.request();
        if (running > 0) {
            LOG.info("Shutdown: " + running + " stream transfer(s) in flight; waiting up to "
                    + (budgetMillis / 1000L) + "s (" + bound + "). Resumable ones stop at their next"
                    + " unit and record what they finished; the rest are left to complete");
        }
    }

    @Override
    public boolean isTaskComplete() {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            int left = StreamShutdown.inFlight();
            if (left > 0 && !expiryReported) {
                expiryReported = true;
                LOG.warn("Shutdown: " + left + " stream transfer(s) did not finish within the grace"
                        + " period and will be terminated. A resumable one resumes from its last"
                        + " checkpoint on the next attempt, so this costs repeated work rather than"
                        + " data");
            }
            return true;
        }
        // Blocks deliberately: the caller's loop has no sleep of its own.
        return StreamShutdown.awaitQuiet(Math.min(remaining, POLL_MILLIS));
    }

    private static long configuredGraceSeconds() {
        Object configured = ConfigParser.getParsedConfigs()
                .get(CONFIG_SECTION + "." + CONFIG_GRACE_SECONDS);
        if (configured == null) {
            return DEFAULT_GRACE_SECONDS;
        }
        try {
            return Long.parseLong(configured.toString().trim());
        } catch (NumberFormatException e) {
            LOG.warn("[" + CONFIG_SECTION + "] " + CONFIG_GRACE_SECONDS + " is '" + configured
                    + "', which is not a number of seconds; waiting the default "
                    + DEFAULT_GRACE_SECONDS + "s for stream transfers instead");
            return DEFAULT_GRACE_SECONDS;
        }
    }
}
