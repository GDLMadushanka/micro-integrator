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

import org.wso2.config.mapper.ConfigParser;

import java.util.Map;

/**
 * This node's identity, as written on every job row it owns.
 *
 * <h2>Standalone has no id, and does not need one</h2>
 * {@code node_id} is a <b>clustering</b> setting. A standalone MI never has one, because there is no
 * cluster to be a member of — which is also why there is nothing for it to be confused with. So a
 * standalone node names itself after the only thing that distinguishes one installation from another:
 * where it is installed. No configuration, and a restart of the same installation recognises its own
 * rows, which is all crash recovery asks of an identity.
 *
 * <p>Derived from the installation path rather than from a fixed string like {@code "standalone"}, for
 * one case: two standalone servers pointed at one MFT database. That is an uncoordinated cluster and is
 * not a supported deployment, but under a fixed string each node's startup sweep would requeue the
 * other's live transfers, and one job id would then run twice — silently, into one output.
 *
 * <p>The path is <b>hashed, never stored</b>. It is only a source of uniqueness, and the column it
 * lands in is read by whoever can read the database — which is not necessarily whoever owns the host.
 *
 * <h2>Why it is resolved per call rather than cached</h2>
 * When clustering is enabled, {@code RDBMSCoordinationStrategy} publishes the id it settled on as the
 * {@code node.id} system property. That happens when the coordination component starts, which is not
 * ordered against MFT's own registration — so a value cached at registration time can be null while the
 * cluster has a perfectly good id. Reading it per run costs a property lookup and cannot go stale.
 *
 * <p>Preferring that published value is also what keeps the two in agreement. Resolving independently
 * would produce a <i>different</i> id in exactly the case that matters — when no id was configured and
 * the coordination component generated one — and crash recovery matches on this string.
 *
 * <h2>Absent is recorded honestly</h2>
 * A <i>clustered</i> node with no configured id gets {@code null} from {@link #owner} rather than an
 * invented one. {@code RDBMSCoordinationStrategy} generates a random id in that case, and a random id is
 * worse than none here: it changes on every restart, so the node could never recognise its own rows and
 * they would never be cleaned up by anything. Reporting that is more use than papering over it.
 */
final class MftNodeId {

    /** Published by the coordination component once it has settled this node's identity. */
    private static final String PUBLISHED_PROPERTY = "node.id";

    /** What an operator sets directly, as a system property or an environment variable. */
    private static final String NODE_ID = "nodeId";

    /** {@code [cluster_config] node_id} in deployment.toml. */
    private static final String CLUSTER_CONFIG = "cluster_config";
    private static final String NODE_ID_CONFIG = "node_id";

    private MftNodeId() {
    }

    /** What a standalone node's identity is built from, so it is obvious in a log and in the table. */
    private static final String STANDALONE_PREFIX = "standalone:";

    /**
     * Who this node is, for the purpose of owning a row.
     *
     * @param clustered whether a coordination datasource is configured — the same question
     *                  {@code TasksDSComponent} asks, and the only reliable one at startup: in a cluster
     *                  the published {@code node.id} may not exist yet when MFT registers, so its absence
     *                  cannot be read as "standalone"
     * @return the configured or published id when there is one; a stable identity for this installation
     *         when this node is standalone; {@code null} for a clustered node that has no configured id,
     *         because the one it will generate is random and recognises nothing
     */
    static String owner(boolean clustered) {
        String configured = resolve();
        if (configured != null) {
            return configured;
        }
        return clustered ? null : standalone();
    }

    /**
     * @return an identity for this installation, the same on every start and different from any other
     *         installation's
     */
    private static String standalone() {
        String home = trimToNull(System.getProperty("carbon.home"));
        if (home == null) {
            // No installation to name -- a unit test, or an embedded run. One node, one id.
            return STANDALONE_PREFIX + "local";
        }
        // Hashed, never the path itself. The path is what makes two installations distinguishable, but
        // OWNER_NODE is written to a database that may be shared, managed elsewhere or backed up
        // off-host, and a filesystem path discloses layout and usually a username to all of them. The
        // hash keeps every property the startup sweep needs -- stable across restarts of this
        // installation, different from any other -- and discloses nothing. The mapping back to the path
        // is logged locally at startup, on the host that already knows its own path.
        return STANDALONE_PREFIX + shortHash(home);
    }

    /**
     * @return the installation this node's standalone identity was derived from, for the local log
     *         only; {@code null} when there is none
     */
    static String installation() {
        return trimToNull(System.getProperty("carbon.home"));
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * @return this node's id, or {@code null} when none is configured
     */
    static String resolve() {
        String published = trimToNull(System.getProperty(PUBLISHED_PROPERTY));
        if (published != null) {
            return published;
        }
        String property = trimToNull(System.getProperty(NODE_ID));
        if (property != null) {
            return property;
        }
        String environment = trimToNull(System.getenv(NODE_ID));
        if (environment != null) {
            return environment;
        }
        // Null-checked: getParsedConfigs() returns null before the configuration has been parsed, and
        // this is now on the claim path as well as the startup one -- an NPE there would fail a claim
        // over a question whose answer is simply "not configured".
        Map<String, Object> configs = ConfigParser.getParsedConfigs();
        Object configured = configs == null ? null : configs.get(CLUSTER_CONFIG + "." + NODE_ID_CONFIG);
        return configured == null ? null : trimToNull(configured.toString());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
