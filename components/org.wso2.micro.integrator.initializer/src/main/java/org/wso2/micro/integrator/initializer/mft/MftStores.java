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
import org.apache.synapse.stream.CheckpointStore;
import org.apache.synapse.stream.CheckpointStores;
import org.apache.synapse.stream.JobStores;
import org.wso2.config.mapper.ConfigParser;
import org.wso2.micro.integrator.ndatasource.common.DataSourceException;
import org.wso2.micro.integrator.ndatasource.core.CarbonDataSource;
import org.wso2.micro.integrator.ndatasource.core.DataSourceService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Connects Synapse's checkpoint and job-record contracts to a datasource the product owns.
 *
 * <p>Synapse defines {@link CheckpointStore} and {@code JobStore} and has no database of its own; MI resolves a named
 * datasource through {@link DataSourceService}, which is published only once the repository is
 * populated. So the storage lives here and the contract lives there.
 *
 * <h2>Configuration</h2>
 * <pre>
 *   [mft]
 *   data_source = "WSO2_MFT_DB"
 * </pre>
 *
 * <p>Absent means checkpointing is unavailable, and that is a legitimate state — a pipeline whose
 * operators keep no durable position runs perfectly well without it. What must not happen is a pipeline
 * that <i>declares</i> {@code checkpointed()} running anyway: it would report success on a transfer
 * that could never resume. Synapse refuses that at deployment, which is only possible because this
 * class registers nothing when it cannot connect.
 *
 * <h2>Why this fails loudly when configured but unusable</h2>
 * MI's coordination database degrades silently when its datasource is absent — no warning, the feature
 * simply switches off. That is the behaviour this deliberately does not copy. A misconfigured datasource
 * here is announced, because the alternative is a transfer that looks fine and cannot be resumed.
 */
public final class MftStores {

    private static final Log LOG = LogFactory.getLog(MftStores.class);

    private static final String CONFIG_SECTION = "mft";
    private static final String CONFIG_DATA_SOURCE = "data_source";

    private MftStores() {
    }

    /**
     * Registers the checkpoint and job-record providers with Synapse, if a datasource is configured and
     * reachable.
     *
     * <p>Never throws. A server that cannot record should still start and serve every pipeline that
     * does not need it; the pipelines that do are refused at deployment with a message naming the cause.
     *
     * <p>Both providers come from one datasource and one connection probe, so a deployment cannot end up
     * checkpointing without recording, or the reverse.
     *
     * @param dataSourceService the bound datasource service
     */
    public static void register(DataSourceService dataSourceService) {

        String dataSourceId = configuredDataSourceId();
        if (dataSourceId == null) {
            LOG.debug("No MFT datasource configured ([" + CONFIG_SECTION + "] " + CONFIG_DATA_SOURCE
                    + "); checkpointing is unavailable and pipelines needing it will be refused at"
                    + " deployment.");
            return;
        }

        DataSource dataSource;
        try {
            dataSource = resolve(dataSourceService, dataSourceId);
        } catch (DataSourceException e) {
            LOG.error("MFT datasource '" + dataSourceId + "' is configured but unusable, so"
                    + " checkpointing is unavailable. Any pipeline declaring checkpointed() will be"
                    + " refused at deployment until this is fixed.", e);
            return;
        }

        // Connect once now rather than on the first checkpoint. A datasource that is wrong should say so
        // at startup, not part-way through the first transfer that depends on it.
        try (Connection probe = dataSource.getConnection()) {
            LOG.info("MFT store connected to '" + dataSourceId + "' ("
                    + probe.getMetaData().getDatabaseProductName() + ")");
        } catch (SQLException e) {
            LOG.error("MFT datasource '" + dataSourceId + "' could not be connected to, so"
                    + " checkpointing is unavailable. Any pipeline declaring checkpointed() will be"
                    + " refused at deployment until this is fixed.", e);
            return;
        }

        final DataSource resolved = dataSource;
        CheckpointStores.register((pipelineName, runId, stageName) ->
                new JdbcCheckpointStore(resolved, runId, stageName));
        JobStores.register(pipelineName -> new JdbcJobStore(resolved, MftNodeId::resolve));
        LOG.debug("MFT checkpoint and job store providers registered");

        if (MftNodeId.resolve() == null) {
            // Not fatal here, and deliberately not silent. Recovery after a crash matches a RUNNING row
            // against the node that owns it, so rows written without one can never be cleaned up.
            LOG.warn("MFT is recording runs but this node has no id ([cluster] node_id is unset), so"
                    + " job rows will carry no owner and a run interrupted by a restart cannot be"
                    + " identified afterwards. Set node_id on every node.");
        }
    }

    /** Withdraws both providers, so a pipeline needing one is refused rather than failing mid-transfer. */
    public static void deregister() {
        CheckpointStores.register(null);
        JobStores.register(null);
    }

    private static String configuredDataSourceId() {
        Object configured = ConfigParser.getParsedConfigs().get(CONFIG_SECTION + "." + CONFIG_DATA_SOURCE);
        if (configured == null) {
            return null;
        }
        String id = configured.toString().trim();
        return id.isEmpty() ? null : id;
    }

    private static DataSource resolve(DataSourceService service, String id) throws DataSourceException {
        CarbonDataSource carbonDataSource = service.getDataSource(id);
        if (Objects.isNull(carbonDataSource)) {
            throw new DataSourceException("datasource '" + id + "' is not configured");
        }
        Object dsObject = carbonDataSource.getDSObject();
        if (!(dsObject instanceof DataSource)) {
            throw new DataSourceException("datasource '" + id + "' is not an RDBMS data source");
        }
        return (DataSource) dsObject;
    }
}
