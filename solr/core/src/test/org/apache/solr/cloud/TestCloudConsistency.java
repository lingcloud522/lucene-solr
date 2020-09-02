/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.solr.JSONTestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.cloud.SocketProxy;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.util.NamedList;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore // nocommit debug
public class TestCloudConsistency extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static Map<JettySolrRunner, SocketProxy> proxies;
  private static Map<URI, JettySolrRunner> jettys;

  @Before
  public void setupCluster() throws Exception {
    useFactory(null);
    System.setProperty("solr.directoryFactory", "solr.StandardDirectoryFactory");
    System.setProperty("solr.ulog.numRecordsToKeep", "1000");
    System.setProperty("leaderVoteWait", "10000");
    System.setProperty("solr.skipCommitOnClose", "false");

    configureCluster(4)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
    // Add proxies
    proxies = new HashMap<>(cluster.getJettySolrRunners().size());
    jettys = new HashMap<>();
    for (JettySolrRunner jetty:cluster.getJettySolrRunners()) {
      SocketProxy proxy = new SocketProxy();
      jetty.setProxyPort(proxy.getListenPort());
      cluster.stopJettySolrRunner(jetty);//TODO: Can we avoid this restart
      cluster.startJettySolrRunner(jetty);
      proxy.open(new URI(jetty.getBaseUrl()));
      if (log.isInfoEnabled()) {
        log.info("Adding proxy for URL: {}. Proxy: {}", jetty.getBaseUrl(), proxy.getUrl());
      }
      proxies.put(jetty, proxy);
      jettys.put(proxy.getUrl(), jetty);
    }
  }

  @After
  public void tearDownCluster() throws Exception {
    if (null != proxies) {
      for (SocketProxy proxy : proxies.values()) {
        proxy.close();
      }
      proxies = null;
    }
    jettys = null;
    System.clearProperty("solr.directoryFactory");
    System.clearProperty("solr.ulog.numRecordsToKeep");
    System.clearProperty("leaderVoteWait");
    
    shutdownCluster();
  }

  @Test
  public void testOutOfSyncReplicasCannotBecomeLeader() throws Exception {
    testOutOfSyncReplicasCannotBecomeLeader(false);
  }

  @Test
  public void testOutOfSyncReplicasCannotBecomeLeaderAfterRestart() throws Exception {
    testOutOfSyncReplicasCannotBecomeLeader(true);
  }

  public void testOutOfSyncReplicasCannotBecomeLeader(boolean onRestart) throws Exception {
    final String collectionName = "outOfSyncReplicasCannotBecomeLeader-"+onRestart;
    CollectionAdminRequest.createCollection(collectionName, 1, 3)
        .setCreateNodeSet("")
        .process(cluster.getSolrClient());
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1")
        .setNode(cluster.getJettySolrRunner(0).getNodeName())
        .process(cluster.getSolrClient());

    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1")
        .setNode(cluster.getJettySolrRunner(1).getNodeName())
        .process(cluster.getSolrClient());
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1")
        .setNode(cluster.getJettySolrRunner(2).getNodeName())
        .process(cluster.getSolrClient());
    
    cluster.waitForActiveCollection(collectionName, 1, 3);

    addDocs(collectionName, 3, 1);

    final Replica oldLeader = getCollectionState(collectionName).getSlice("shard1").getLeader();
    assertEquals(cluster.getJettySolrRunner(0).getNodeName(), oldLeader.getNodeName());

    if (onRestart) {
      addDocToWhenOtherReplicasAreDown(collectionName, oldLeader, 4);
    } else {
      addDocWhenOtherReplicasAreNetworkPartitioned(collectionName, oldLeader, 4);
    }

    assertDocsExistInAllReplicas(getCollectionState(collectionName).getReplicas(), collectionName, 1, 4);

    CollectionAdminRequest.deleteCollection(collectionName).process(cluster.getSolrClient());
  }


  /**
   * Adding doc when replicas (not leader) are down,
   * These replicas are out-of-sync hence they should not become leader even when current leader is DOWN.
   * Leader should be on node - 0
   */
  private void addDocToWhenOtherReplicasAreDown(String collection, Replica leader, int docId) throws Exception {
    JettySolrRunner j1 = cluster.getJettySolrRunner(1);
    JettySolrRunner j2 = cluster.getJettySolrRunner(2);
    j1.stop();
    j2.stop();
    cluster.waitForJettyToStop(j1);
    cluster.waitForJettyToStop(j2);

    addDocs(collection, 1, docId);
    JettySolrRunner j3 = cluster.getJettySolrRunner(0);
    j3.stop();
    cluster.waitForJettyToStop(j3);

    cluster.getJettySolrRunner(1).start();
    cluster.getJettySolrRunner(2).start();
    
    cluster.waitForNode(j1, 30);
    cluster.waitForNode(j2, 30);

    // the meat of the test -- wait to see if a different replica become a leader
    // the correct behavior is that this should time out, if it succeeds we have a problem...
    expectThrows(TimeoutException.class,
                 "Did not time out waiting for new leader, out of sync replica became leader",
                 () -> {
                   cluster.getSolrClient().waitForState(collection, 4, TimeUnit.SECONDS, (state) -> {
            Replica newLeader = state.getSlice("shard1").getLeader();
            if (newLeader != null && !newLeader.getName().equals(leader.getName()) && newLeader.getState() == Replica.State.ACTIVE) {
              // this is is the bad case, our "bad" state was found before timeout
              log.error("WTF: New Leader={}", newLeader);
              return true;
            }
            return false; // still no bad state, wait for timeout
          });
      });

    JettySolrRunner j0 = cluster.getJettySolrRunner(0);
    j0.start();
    cluster.waitForNode(j0, 30);
    
    // waitForNode not solid yet?
    cluster.waitForAllNodes(30);
    
    waitForState("Timeout waiting for leader", collection, (liveNodes, collectionState) -> {
      Replica newLeader = collectionState.getLeader("shard1");
      return newLeader != null && newLeader.getName().equals(leader.getName());
    });
    cluster.waitForActiveCollection(collection, 1, 3);
  }


  /**
   * Adding doc when replicas (not leader) are network partitioned with leader,
   * These replicas are out-of-sync hence they should not become leader even when current leader is DOWN.
   * Leader should be on node - 0
   */
  private void addDocWhenOtherReplicasAreNetworkPartitioned(String collection, Replica leader, int docId) throws Exception {
    DocCollection col = cluster.getSolrClient().getZkStateReader().getClusterState().getCollection(collection);
    Replica shard1Leader = col.getLeader("shard1");
    String baseUrl = shard1Leader.getBaseUrl();
    JettySolrRunner j1 = null;
    for (JettySolrRunner j : cluster.getJettySolrRunners()) {
      if (j.getProxyBaseUrl().toString().equals(baseUrl)) {
        j1 = j;
        break;
      }
    }

    assertNotNull(baseUrl, j1);

    for (JettySolrRunner j : cluster.getJettySolrRunners()) {
      if (j != j1) {
        proxies.get(j).close();
      }
    }

    addDoc(collection, docId, j1);

    j1.stop();
    cluster.waitForJettyToStop(j1);
    for (JettySolrRunner j : cluster.getJettySolrRunners()) {
      if (j != j1) {
        proxies.get(j).reopen();
      }
    }
    waitForState("Timeout waiting for leader goes DOWN", collection, (liveNodes, collectionState)
        ->  collectionState.getReplica(leader.getName()).getState() == Replica.State.DOWN);

    // the meat of the test -- wait to see if a different replica become a leader
    // the correct behavior is that this should time out, if it succeeds we have a problem...
    expectThrows(TimeoutException.class,
                 "Did not time out waiting for new leader, out of sync replica became leader",
                 () -> {
                   cluster.getSolrClient().waitForState(collection, 4, TimeUnit.SECONDS, (state) -> {
            Replica newLeader = state.getSlice("shard1").getLeader();
            if (newLeader != null && !newLeader.getName().equals(leader.getName()) && newLeader.getState() == Replica.State.ACTIVE) {
              // this is is the bad case, our "bad" state was found before timeout
              log.error("WTF: New Leader={} Old Leader={}", newLeader, leader);
              return true;
            }
            return false; // still no bad state, wait for timeout
          });
      });

    proxies.get(j1).reopen();
    j1.start();
    cluster.waitForAllNodes(30);;
    waitForState("Timeout waiting for leader", collection, (liveNodes, collectionState) -> {
      Replica newLeader = collectionState.getLeader("shard1");
      return newLeader != null && newLeader.getName().equals(leader.getName());
    });
    
    cluster.waitForActiveCollection(collection, 1, 3);
  }

  private void addDocs(String collection, int numDocs, int startId) throws SolrServerException, IOException {
    List<SolrInputDocument> docs = new ArrayList<>(numDocs);
    for (int i = 0; i < numDocs; i++) {
      int id = startId + i;
      docs.add(new SolrInputDocument("id", String.valueOf(id), "fieldName_s", String.valueOf(id)));
    }
    cluster.getSolrClient().add(collection, docs);
    cluster.getSolrClient().commit(collection);
  }

  private void addDoc(String collection, int docId, JettySolrRunner solrRunner) throws IOException, SolrServerException {
    try (HttpSolrClient solrClient = new HttpSolrClient.Builder(solrRunner.getBaseUrl().toString()).build()) {
      solrClient.add(collection, new SolrInputDocument("id", String.valueOf(docId), "fieldName_s", String.valueOf(docId)));
      solrClient.commit(collection);
    }
  }

  private void assertDocsExistInAllReplicas(List<Replica> notLeaders,
                                              String testCollectionName, int firstDocId, int lastDocId) throws Exception {
    Replica leader =
        cluster.getSolrClient().getZkStateReader().getLeaderRetry(testCollectionName, "shard1", 10000);
    Http2SolrClient leaderSolr = getHttpSolrClient(leader, testCollectionName);
    List<Http2SolrClient> replicas =
            new ArrayList<>(notLeaders.size());

    for (Replica r : notLeaders) {
      replicas.add(getHttpSolrClient(r, testCollectionName));
    }
    try {
      for (int d = firstDocId; d <= lastDocId; d++) {
        String docId = String.valueOf(d);
        assertDocExists(leaderSolr, testCollectionName, docId);
        for (Http2SolrClient replicaSolr : replicas) {
          assertDocExists(replicaSolr, testCollectionName, docId);
        }
      }
    } finally {
      if (leaderSolr != null) {
        leaderSolr.close();
      }
      for (Http2SolrClient replicaSolr : replicas) {
        replicaSolr.close();
      }
    }
  }

  private void assertDocExists(Http2SolrClient solr, String coll, String docId) throws Exception {
    NamedList rsp = realTimeGetDocId(solr, docId);
    String match = JSONTestUtil.matchObj("/id", rsp.get("doc"), docId);
    assertTrue("Doc with id=" + docId + " not found in " + solr.getBaseURL()
        + " due to: " + match + "; rsp="+rsp, match == null);
  }

  private NamedList realTimeGetDocId(Http2SolrClient solr, String docId) throws SolrServerException, IOException {
    QueryRequest qr = new QueryRequest(params("qt", "/get", "id", docId, "distrib", "false"));
    return solr.request(qr);
  }

  protected Http2SolrClient getHttpSolrClient(Replica replica, String coll) throws Exception {
    ZkCoreNodeProps zkProps = new ZkCoreNodeProps(replica);
    String url = zkProps.getBaseUrl() + "/" + coll;
    return SolrTestCaseJ4.getHttpSolrClient(url);
  }

}
