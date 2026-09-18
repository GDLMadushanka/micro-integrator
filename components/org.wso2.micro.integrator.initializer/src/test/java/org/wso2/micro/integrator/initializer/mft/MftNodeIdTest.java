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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Who a node says it is, which is the whole input to crash recovery.
 *
 * <p>The case that matters is the ordinary one: a standalone MI, where {@code node_id} is a clustering
 * setting nobody sets. It has to arrive at an identity by itself, and the same one on the next start.
 */
public class MftNodeIdTest {

    private static final String CARBON_HOME = "carbon.home";
    private static final String NODE_ID = "nodeId";
    private static final String PUBLISHED = "node.id";

    private String home;
    private String nodeId;
    private String published;

    @Before
    public void remember() {
        home = System.getProperty(CARBON_HOME);
        nodeId = System.getProperty(NODE_ID);
        published = System.getProperty(PUBLISHED);
        System.clearProperty(NODE_ID);
        System.clearProperty(PUBLISHED);
    }

    @After
    public void restore() {
        restore(CARBON_HOME, home);
        restore(NODE_ID, nodeId);
        restore(PUBLISHED, published);
    }

    @Test
    public void aStandaloneNodeNamesItselfWithoutBeingConfigured() {
        System.setProperty(CARBON_HOME, "/opt/wso2mi");

        String owner = MftNodeId.owner(false);

        assertNotNull("standalone must not need node_id; nobody sets it outside a cluster", owner);
        assertTrue("the kind of node is worth reading off the id", owner.startsWith("standalone:"));
        // The filesystem layout of the server is not the database's business: MFT_TRANSFER can be read
        // by anyone with access to the schema, and an install path names directories and often a user.
        assertFalse("the install path must not leak into a column an outsider can read",
                owner.contains("/opt/wso2mi"));
        assertEquals("a hash of the home, so it is fixed-width whatever the path",
                "standalone:".length() + 16, owner.length());
        assertEquals("and it is the same on the next start", owner, MftNodeId.owner(false));
    }

    /**
     * Two standalone servers pointed at one MFT database is an uncoordinated cluster and unsupported,
     * but under one shared name each would requeue the other's live transfers at startup.
     */
    @Test
    public void twoInstallationsAreDifferentNodes() {
        System.setProperty(CARBON_HOME, "/opt/mi-a");
        String a = MftNodeId.owner(false);
        System.setProperty(CARBON_HOME, "/opt/mi-b");
        String b = MftNodeId.owner(false);

        assertFalse("each installation owns only its own rows", a.equals(b));
    }

    /** OWNER_NODE is VARCHAR(255), and a truncated owner would match nothing. */
    @Test
    public void anAbsurdlyDeepInstallationPathIsHashedRatherThanTruncated() {
        System.setProperty(CARBON_HOME, "/opt/" + "very-long-directory-name/".repeat(20) + "mi");

        String owner = MftNodeId.owner(false);

        assertTrue(owner.length() <= 255);
        assertEquals("the same path still gives the same node", owner, MftNodeId.owner(false));
    }

    @Test
    public void aConfiguredIdWinsOverTheStandaloneFallback() {
        System.setProperty(CARBON_HOME, "/opt/wso2mi");
        System.setProperty(NODE_ID, "mi-1");

        assertEquals("mi-1", MftNodeId.owner(false));
        assertEquals("mi-1", MftNodeId.owner(true));
    }

    /**
     * The one case with no identity. {@code RDBMSCoordinationStrategy} would generate a random one per
     * start, which recognises nothing it wrote last time — so recovery reports it rather than sweeping
     * on a name that means nothing.
     */
    @Test
    public void aClusteredNodeWithNoConfiguredIdHasNoIdentity() {
        System.setProperty(CARBON_HOME, "/opt/wso2mi");

        assertNull(MftNodeId.owner(true));
    }

    /** The coordination component publishes what it settled on; that is the id MFT must agree with. */
    @Test
    public void thePublishedClusterIdIsPreferred() {
        System.setProperty(CARBON_HOME, "/opt/wso2mi");
        System.setProperty(PUBLISHED, "node-7");

        assertEquals("node-7", MftNodeId.owner(true));
        assertEquals("node-7", MftNodeId.owner(false));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
