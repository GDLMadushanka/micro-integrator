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

/**
 * This node's identity, as written on every job row it owns.
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
 * An unconfigured standalone node has no id, and this returns {@code null} rather than inventing one.
 * A generated id would be worse than none: it changes on every restart, so a node could never recognise
 * its own rows, and the resulting {@code RUNNING} rows would never be cleaned up by anything.
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
        Object configured = ConfigParser.getParsedConfigs().get(CLUSTER_CONFIG + "." + NODE_ID_CONFIG);
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
