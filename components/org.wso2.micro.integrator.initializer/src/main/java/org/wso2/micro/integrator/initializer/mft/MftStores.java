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
import org.apache.synapse.stream.TransferStores;
import org.apache.synapse.stream.JobStores;
import org.apache.synapse.stream.RunStores;
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

    /** {@code RDBMSConstantUtils.COORDINATION_DB_NAME}, not imported: this bundle does not depend on
     *  the coordination component, and asking the datasource service for a name is the whole check. */
    private static final String COORDINATION_DATA_SOURCE = "WSO2_COORDINATION_DB";

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

        // Asked once, here, and not re-derived later: in a cluster the coordination component may
        // publish node.id after MFT has registered, so "no id yet" and "standalone" are the same
        // observation at this moment and only the datasource tells them apart. Same question
        // TasksDSComponent asks to decide whether to coordinate at all.
        boolean clustered = isClustered(dataSourceService);

        final DataSource resolved = dataSource;
        CheckpointStores.register((pipelineName, runId, stageName) ->
                new JdbcCheckpointStore(resolved, runId, stageName));
        JobStores.register(pipelineName -> new JdbcJobStore(resolved, () -> MftNodeId.owner(clustered)));
        // Over the same datasource, deliberately: a transfer's claim and the job row its run writes are
        // then one commit apart on one connection, rather than two databases to keep in step.
        //
        // The transfer store is handed a job store of its own rather than reaching through JobStores,
        // because it needs one for a transfer whose run is already over -- there is no pipeline name to
        // look one up by at that point. A JdbcJobStore holds no per-pipeline state, so one serves all.
        JdbcJobStore runs = new JdbcJobStore(resolved, () -> MftNodeId.owner(clustered));
        JdbcTransferStore transfers =
                new JdbcTransferStore(resolved, () -> MftNodeId.owner(clustered), runs);
        TransferStores.register(transfers);
        // The read side of the same table JobStores writes. Registered together so a management API
        // cannot be able to list runs on a node that is not recording them, or the reverse.
        RunStores.register(new JdbcRunStore(resolved));
        LOG.debug("MFT checkpoint store, job store and transfer store registered");

        String owner = MftNodeId.owner(clustered);
        if (owner != null) {
            String where = clustered ? " (clustered)"
                    : " (standalone, " + MftNodeId.installation() + ")";
            // The installation path appears here and not in OWNER_NODE: this log is on the host that
            // already knows its own path, and the column is in a database that may not be.
            LOG.info("MFT owns its transfers as '" + owner + "'" + where
                    + "; transfers interrupted by a restart are recovered under that name");
        }

        // Before the message processor starts, which is what makes this the right place: a transfer put
        // back on the queue here is claimable on the processor's very first poll, so a restart after a
        // crash looks like a pause rather than a transfer that has to be noticed and resubmitted.
        MftRecovery.sweep(resolved, transfers, owner);
    }

    /**
     * @return whether this node is part of a cluster, decided the way {@code TasksDSComponent} decides
     *         it — by whether the coordination datasource resolves. Treated as standalone when the
     *         lookup itself fails: a node that cannot see a coordination database is not coordinating
     *         with anything, and the alternative is to disable crash recovery over a failed lookup
     */
    private static boolean isClustered(DataSourceService service) {
        try {
            return service.getDataSource(COORDINATION_DATA_SOURCE) != null;
        } catch (DataSourceException e) {
            LOG.debug("MFT could not resolve the coordination datasource; treating this node as"
                    + " standalone", e);
            return false;
        }
    }

    /** Withdraws both providers, so a pipeline needing one is refused rather than failing mid-transfer. */
    public static void deregister() {
        CheckpointStores.register(null);
        JobStores.register(null);
        // Withdrawn with the others. Leaving it registered would let a submission be accepted against a
        // datasource this bundle has stopped vouching for, and hand back an id for it.
        TransferStores.register(null);
        RunStores.register(null);
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
