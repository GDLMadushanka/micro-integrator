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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Deletes a run's workspace directory.
 *
 * <h2>Who is allowed to call this</h2>
 * Only a caller that can show <b>no thread is writing into the directory</b>. Deleting under a live
 * writer is the hazard ADR-0028's "only the owner deletes files" rule exists to prevent, and there are
 * exactly two moments that can prove it:
 *
 * <ul>
 *   <li><b>Startup</b> — "I am starting, so I am not running anything" ({@link MftRecovery}).</li>
 *   <li><b>A transfer at rest whose row has just been made unclaimable</b> — a {@code PAUSED} run wrote
 *       that status itself after unwinding, so its thread is gone, and moving the row to
 *       {@code STOPPING} before deleting stops {@code start} from producing a new one
 *       ({@link JdbcTransferStore}).</li>
 * </ul>
 *
 * <p>A live {@code RUNNING} transfer is neither: its own thread discards its workspace on the way out,
 * because it is the only party that knows it has stopped touching it.
 *
 * <p>Node-agnostic on purpose. The workspace is a cluster-mounted share, so the path resolves on any
 * node; with no writer, which node issues the delete does not matter. A directory that is missing is
 * not an error — a scratch run may never have created one.
 */
final class MftWorkspaces {

    private static final Log LOG = LogFactory.getLog(MftWorkspaces.class);

    private MftWorkspaces() {
    }

    /**
     * Removes the directory and everything under it.
     *
     * <p>Never throws. A workspace that cannot be deleted is logged with its path and left behind: the
     * row that names it survives, which is what an operator needs to remove it by hand, and failing the
     * stop over an undeletable file would leave the transfer in a worse state than the leak.
     *
     * @param workspacePath the recorded path, which may be {@code null} or blank for a run that has none
     * @param runId         named in the log, because a bare path does not say which transfer left it
     * @param why           what this deletion is part of, for the log line
     * @return whether a directory was found and fully removed
     */
    static boolean discard(String workspacePath, String runId, String why) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return false;
        }
        Path directory = Paths.get(workspacePath);
        if (!Files.exists(directory)) {
            return false;
        }
        // Deepest first: a directory cannot be removed until its children are gone.
        try (Stream<Path> tree = Files.walk(directory)) {
            List<Path> entries = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
            LOG.debug("MFT discarded the workspace of run '" + runId + "' at '" + directory
                    + "' (" + why + ")");
            return true;
        } catch (IOException e) {
            LOG.warn("MFT could not discard the workspace of run '" + runId + "' at '" + directory
                    + "' (" + why + "); it can be deleted by hand.", e);
            return false;
        }
    }
}
