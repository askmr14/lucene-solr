
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

package org.apache.solr.update;

import static org.junit.internal.matchers.StringContains.containsString;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.util.TestUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.TestRTGBase;
import org.apache.solr.update.processor.AtomicUpdateDocumentMerger;
import org.apache.solr.util.RefCounted;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Tests the in-place updates (docValues updates) for a standalone Solr instance.
 */
public class TestInPlaceUpdatesStandalone extends TestRTGBase {
  // nocommit: why is this class extending TestRTGBase?
  // nocommit: it doesn't seem to use any features of that baseclass (and was subclassing SolrTestCaseJ4 in previous patches)

  private static SolrClient client;

  @BeforeClass
  public static void beforeClass() throws Exception {

    // nocommit: does this test need to randomize between diff schema/fields used?
    // nocommit: see nocommits/jira questions related to special dynamicField logic in AtomicUpdateDocumentMerger.isInPlaceUpdate
    
    initCore("solrconfig-tlog.xml", "schema-inplace-updates.xml");

    // sanity check that autocommits are disabled
    assertEquals(-1, h.getCore().getSolrConfig().getUpdateHandlerInfo().autoCommmitMaxTime);
    assertEquals(-1, h.getCore().getSolrConfig().getUpdateHandlerInfo().autoSoftCommmitMaxTime);
    assertEquals(-1, h.getCore().getSolrConfig().getUpdateHandlerInfo().autoCommmitMaxDocs);
    assertEquals(-1, h.getCore().getSolrConfig().getUpdateHandlerInfo().autoSoftCommmitMaxDocs);

    // validate that the schema was not changed to an unexpected state
    IndexSchema schema = h.getCore().getLatestSchema();
    for (String fieldName : Arrays.asList("_version_", "inplace_updatable_float", "inplace_l_dvo")) {
      // these fields must only be using docValues to support inplace updates
      SchemaField field = schema.getField(fieldName);
      assertTrue(field.toString(),
                 field.hasDocValues() && ! field.indexed() && ! field.stored());
    }
    for (String fieldName : Arrays.asList("title_s", "regular_l", "stored_i")) {
      // these fields must support atomic updates, but not inplace updates (ie: stored)
      SchemaField field = schema.getField(fieldName);
      assertTrue(field.toString(), field.stored());
    }    

    // Don't close this client, it would shutdown the CoreContainer
    client = new EmbeddedSolrServer(h.getCoreContainer(), h.coreName);
  }

  @Before
  public void deleteAllAndCommit() {
    clearIndex();
    assertU(commit("softCommit", "false"));
  }

  @Test
  public void testUpdatingDocValues() throws Exception {
    long version1 = addAndGetVersion(sdoc("id", "1", "title_s", "first"), null);
    long version2 = addAndGetVersion(sdoc("id", "2", "title_s", "second"), null);
    long version3 = addAndGetVersion(sdoc("id", "3", "title_s", "third"), null);
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "*:*"), "//*[@numFound='3']");

    // the reason we're fetching these docids is to validate that the subsequent updates 
    // are done in place and don't cause the docids to change
    int docid1 = getDocId("1");
    int docid2 = getDocId("2");
    int docid3 = getDocId("3");

    // Check docValues were "set"
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 200));
    version2 = addAndAssertVersion(version2, "id", "2", "inplace_updatable_float", map("set", 300));
    version3 = addAndAssertVersion(version3, "id", "3", "inplace_updatable_float", map("set", 100));
    assertU(commit("softCommit", "false"));

    assertQ(req("q", "*:*", "sort", "id asc", "fl", "*,[docid]"),
        "//*[@numFound='3']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='200.0']",
        "//result/doc[2]/float[@name='inplace_updatable_float'][.='300.0']",
        "//result/doc[3]/float[@name='inplace_updatable_float'][.='100.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version1+"']",
        "//result/doc[2]/long[@name='_version_'][.='"+version2+"']",
        "//result/doc[3]/long[@name='_version_'][.='"+version3+"']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']",
        "//result/doc[2]/int[@name='[docid]'][.='"+docid2+"']",
        "//result/doc[3]/int[@name='[docid]'][.='"+docid3+"']"
        );

    // Check docValues are "inc"ed
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", 1));
    version2 = addAndAssertVersion(version2, "id", "2", "inplace_updatable_float", map("inc", -2));
    version3 = addAndAssertVersion(version3, "id", "3", "inplace_updatable_float", map("inc", 3));
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "*:*", "sort", "id asc", "fl", "*,[docid]"),
        "//*[@numFound='3']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='201.0']",
        "//result/doc[2]/float[@name='inplace_updatable_float'][.='298.0']",
        "//result/doc[3]/float[@name='inplace_updatable_float'][.='103.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version1+"']",
        "//result/doc[2]/long[@name='_version_'][.='"+version2+"']",
        "//result/doc[3]/long[@name='_version_'][.='"+version3+"']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']",
        "//result/doc[2]/int[@name='[docid]'][.='"+docid2+"']",
        "//result/doc[3]/int[@name='[docid]'][.='"+docid3+"']"
        );

    // Check back to back "inc"s are working (off the transaction log)
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", 1));
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", 2)); // new value should be 204
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "id:1", "fl", "*,[docid]"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='204.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version1+"']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']");

    // Now let the document be atomically updated (non-inplace), ensure the old docvalue is part of new doc
    version1 = addAndAssertVersion(version1, "id", "1", "title_s", map("set", "new first"));
    assertU(commit("softCommit", "false"));
    int newDocid1 = getDocId("1");
    assertTrue(newDocid1 != docid1);
    docid1 = newDocid1;

    assertQ(req("q", "id:1"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='204.0']",
        "//result/doc[1]/str[@name='title_s'][.='new first']",
        "//result/doc[1]/long[@name='_version_'][.='"+version1+"']");

    // Check if atomic update with "inc" to a docValue works
    version2 = addAndAssertVersion(version2, "id", "2", "title_s", map("set", "new second"), "inplace_updatable_float", map("inc", 2));
    assertU(commit("softCommit", "false"));
    int newDocid2 = getDocId("2");
    assertTrue(newDocid2 != docid2);
    docid2 = newDocid2;

    assertQ(req("q", "id:2"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='300.0']",
        "//result/doc[1]/str[@name='title_s'][.='new second']",
        "//result/doc[1]/long[@name='_version_'][.='"+version2+"']");

    // Check if docvalue "inc" update works for a newly created document, which is not yet committed
    // Case1: docvalue was supplied during add of new document
    long version4 = addAndGetVersion(sdoc("id", "4", "title_s", "fourth", "inplace_updatable_float", "400"), params());
    version4 = addAndAssertVersion(version4, "id", "4", "inplace_updatable_float", map("inc", 1));
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "id:4"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='401.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version4+"']");

    // Check if docvalue "inc" update works for a newly created document, which is not yet committed
    // Case2: docvalue was not supplied during add of new document, should assume default
    long version5 = addAndGetVersion(sdoc("id", "5", "title_s", "fifth"), params());
    version5 = addAndAssertVersion(version5, "id", "5", "inplace_updatable_float", map("inc", 1));
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "id:5"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='1.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version5+"']");

    // Check if docvalue "set" update works for a newly created document, which is not yet committed
    long version6 = addAndGetVersion(sdoc("id", "6", "title_s", "sixth"), params());
    version6 = addAndAssertVersion(version6, "id", "6", "inplace_updatable_float", map("set", 600));
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "id:6"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='600.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version6+"']");

    // Check optimistic concurrency works
    long v20 = addAndGetVersion(sdoc("id", "20", "title_s","first", "inplace_updatable_float", 100), params());    
    SolrException exception = expectThrows(SolrException.class, () -> {
      addAndGetVersion(sdoc("id","20", "_version_", -1, "inplace_updatable_float", map("inc", 1)), null);
    });
    assertEquals(exception.toString(), SolrException.ErrorCode.CONFLICT.code, exception.code());
    assertThat(exception.getMessage(), containsString("expected=-1"));
    assertThat(exception.getMessage(), containsString("actual="+v20));


    long oldV20 = v20;
    v20 = addAndAssertVersion(v20, "id","20", "_version_", v20, "inplace_updatable_float", map("inc", 1));
    exception = expectThrows(SolrException.class, () -> {
      addAndGetVersion(sdoc("id","20", "_version_", oldV20, "inplace_updatable_float", map("inc", 1)), null);
    });
    assertEquals(exception.toString(), SolrException.ErrorCode.CONFLICT.code, exception.code());
    assertThat(exception.getMessage(), containsString("expected="+oldV20));
    assertThat(exception.getMessage(), containsString("actual="+v20));

    v20 = addAndAssertVersion(v20, "id","20", "_version_", v20, "inplace_updatable_float", map("inc", 1));
    // RTG before a commit
    assertJQ(req("qt","/get", "id","20", "fl","id,inplace_updatable_float,_version_"),
        "=={'doc':{'id':'20', 'inplace_updatable_float':" + 102.0 + ",'_version_':" + v20 + "}}");
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "id:20"), 
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='102.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+v20+"']");

    // Check if updated DVs can be used for search
    assertQ(req("q", "inplace_updatable_float:102"), 
        "//result/doc[1]/str[@name='id'][.='20']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='102.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+v20+"']");

    // Check if updated DVs can be used for sorting
    assertQ(req("q", "*:*", "sort", "inplace_updatable_float asc"), 
        "//result/doc[4]/str[@name='id'][.='1']",
        "//result/doc[4]/float[@name='inplace_updatable_float'][.='204.0']",

        "//result/doc[5]/str[@name='id'][.='2']",
        "//result/doc[5]/float[@name='inplace_updatable_float'][.='300.0']",

        "//result/doc[3]/str[@name='id'][.='3']",
        "//result/doc[3]/float[@name='inplace_updatable_float'][.='103.0']",

        "//result/doc[6]/str[@name='id'][.='4']",
        "//result/doc[6]/float[@name='inplace_updatable_float'][.='401.0']",

        "//result/doc[1]/str[@name='id'][.='5']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='1.0']",

        "//result/doc[7]/str[@name='id'][.='6']",
        "//result/doc[7]/float[@name='inplace_updatable_float'][.='600.0']",

        "//result/doc[2]/str[@name='id'][.='20']",
        "//result/doc[2]/float[@name='inplace_updatable_float'][.='102.0']");
  }

  @Test
  public void testUpdateTwoDifferentFields() throws Exception {
    long version1 = addAndGetVersion(sdoc("id", "1", "title_s", "first"), null);
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "*:*"), "//*[@numFound='1']");

    int docid1 = getDocId("1");

    // Check docValues were "set"
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 200));
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_int", map("set", 10));
    assertU(commit("softCommit", "false"));

    assertU(commit("softCommit", "false"));

    assertQ(req("q", "*:*", "sort", "id asc", "fl", "*,[docid]"),
        "//*[@numFound='1']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='200.0']",
        "//result/doc[1]/long[@name='_version_'][.='"+version1+"']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']"
        );

    // two different update commands, updating each of the fields separately
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_int", map("inc", 1));
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", 1));
    // same update command, updating both the fields together
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_int", map("inc", 1),
        "inplace_updatable_float", map("inc", 1));

    if (random().nextBoolean()) {
      assertU(commit("softCommit", "false"));
      assertQ(req("q", "*:*", "sort", "id asc", "fl", "*,[docid]"),
          "//*[@numFound='1']",
          "//result/doc[1]/float[@name='inplace_updatable_float'][.='202.0']",
          "//result/doc[1]/int[@name='inplace_updatable_int'][.='12']",
          "//result/doc[1]/long[@name='_version_'][.='"+version1+"']",
          "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']"
          );
    } 

    // RTG
    assertJQ(req("qt","/get", "id","1", "fl","id,inplace_updatable_float,inplace_updatable_int"),
        "=={'doc':{'id':'1', 'inplace_updatable_float':" + 202.0 + ",'inplace_updatable_int':" + 12 + "}}");

  }

  @Test
  public void testDVUpdatesWithDBQofUpdatedValue() throws Exception {
    long version1 = addAndGetVersion(sdoc("id", "1", "title_s", "first", "inplace_updatable_float", "0"), null);
    assertU(commit());

    // in-place update
    addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 100), "_version_", version1);

    // DBQ where q=inplace_updatable_float:100
    assertU(delQ("inplace_updatable_float:100"));

    assertU(commit());

    assertQ(req("q", "*:*"), "//*[@numFound='0']");
  }

  @Test
  public void testDVUpdatesWithDelete() throws Exception {
    long version1 = 0;

    for (boolean postAddCommit : Arrays.asList(true, false)) {
      for (boolean delById : Arrays.asList(true, false)) {
        for (boolean postDelCommit : Arrays.asList(true, false)) {
          addAndGetVersion(sdoc("id", "1", "title_s", "first"), params());
          if (postAddCommit) assertU(commit());
          assertU(delById ? delI("1") : delQ("id:1"));
          if (postDelCommit) assertU(commit());
          version1 = addAndGetVersion(sdoc("id", "1", "inplace_updatable_float", map("set", 200)), params());
          // assert current doc#1 doesn't have old value of "title_s"
          assertU(commit());
          assertQ(req("q", "title_s:first", "sort", "id asc", "fl", "*,[docid]"),
              "//*[@numFound='0']");
        }
      }
    }

    // Update to recently deleted (or non-existent) document with a "set" on updateable 
    // field should succeed, since it is executed internally as a full update
    // because AUDM.doInPlaceUpdateMerge() returns false
    assertU(random().nextBoolean()? delI("1"): delQ("id:1"));
    if (random().nextBoolean()) assertU(commit());
    addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 200));
    assertU(commit());
    assertQ(req("q", "id:1", "sort", "id asc", "fl", "*"),
        "//*[@numFound='1']",
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='200.0']");

    // Another "set" on the same field should be an in-place update 
    int docid1 = getDocId("1");
    addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 300));
    assertU(commit());
    assertQ(req("q", "id:1", "fl", "*,[docid]"),
        "//result/doc[1]/float[@name='inplace_updatable_float'][.='300.0']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']");
  }

  public static long addAndAssertVersion(long expectedCurrentVersion, Object... fields) throws Exception {
    assert 0 < expectedCurrentVersion;
    long currentVersion = addAndGetVersion(sdoc(fields), null);
    assertTrue(currentVersion > expectedCurrentVersion);
    return currentVersion;
  }

  /**
   * Helper method to search for the specified (uniqueKey field) id using <code>fl=[docid]</code> 
   * and return the internal lucene docid.
   */
  private int getDocId(String id) throws NumberFormatException, Exception {
    SolrDocumentList results = client.query(params("q","id:" + id, "fl", "[docid]")).getResults();
    assertEquals(1, results.getNumFound());
    assertEquals(1, results.size());
    Object docid = results.get(0).getFieldValue("[docid]");
    assertTrue(docid instanceof Integer);
    return ((Integer)docid);
  }

  @Test
  public void testUpdateOfNonExistentDVsShouldNotFail() throws Exception {
    // schema sanity check: assert that the nonexistent_field_i_dvo doesn't exist already
    FieldInfo fi;
    RefCounted<SolrIndexSearcher> holder = h.getCore().getSearcher();
    try {
      fi = holder.get().getSlowAtomicReader().getFieldInfos().fieldInfo("nonexistent_field_i_dvo");
    } finally {
      holder.decref();
    }
    assertNull(fi);

    // Partial update
    addAndGetVersion(sdoc("id", "0", "nonexistent_field_i_dvo", map("set", "42")), null);

    addAndGetVersion(sdoc("id", "1"), null);
    addAndGetVersion(sdoc("id", "1", "nonexistent_field_i_dvo", map("inc", "1")), null);
    addAndGetVersion(sdoc("id", "1", "nonexistent_field_i_dvo", map("inc", "1")), null);

    assertU(commit());

    assertQ(req("q", "*:*"), "//*[@numFound='2']");    
    assertQ(req("q", "nonexistent_field_i_dvo:42"), "//*[@numFound='1']");    
    assertQ(req("q", "nonexistent_field_i_dvo:2"), "//*[@numFound='1']");    
  }

  @Test
  public void testOnlyPartialUpdatesBetweenCommits() throws Exception {
    // Full updates
    long version1 = addAndGetVersion(sdoc("id", "1", "title_s", "first", "val1_i_dvo", "1", "val2_l_dvo", "1"), params());
    long version2 = addAndGetVersion(sdoc("id", "2", "title_s", "second", "val1_i_dvo", "2", "val2_l_dvo", "2"), params());
    long version3 = addAndGetVersion(sdoc("id", "3", "title_s", "third", "val1_i_dvo", "3", "val2_l_dvo", "3"), params());
    assertU(commit("softCommit", "false"));

    assertQ(req("q", "*:*", "fl", "*,[docid]"), "//*[@numFound='3']");

    int docid1 = getDocId("1");
    int docid2 = getDocId("2");
    int docid3 = getDocId("3");

    int numPartialUpdates = 1 + random().nextInt(5000);
    for (int i=0; i<numPartialUpdates; i++) {
      version1 = addAndAssertVersion(version1, "id", "1", "val1_i_dvo", map("set", i));
      version2 = addAndAssertVersion(version2, "id", "2", "val1_i_dvo", map("inc", 1));
      version3 = addAndAssertVersion(version3, "id", "3", "val1_i_dvo", map("set", i));

      version1 = addAndAssertVersion(version1, "id", "1", "val2_l_dvo", map("set", i));
      version2 = addAndAssertVersion(version2, "id", "2", "val2_l_dvo", map("inc", 1));
      version3 = addAndAssertVersion(version3, "id", "3", "val2_l_dvo", map("set", i));
    }
    assertU(commit("softCommit", "true"));

    assertQ(req("q", "*:*", "sort", "id asc", "fl", "*,[docid]"),
        "//*[@numFound='3']",
        "//result/doc[1]/int[@name='val1_i_dvo'][.='"+(numPartialUpdates-1)+"']",
        "//result/doc[2]/int[@name='val1_i_dvo'][.='"+(numPartialUpdates+2)+"']",
        "//result/doc[3]/int[@name='val1_i_dvo'][.='"+(numPartialUpdates-1)+"']",
        "//result/doc[1]/long[@name='val2_l_dvo'][.='"+(numPartialUpdates-1)+"']",
        "//result/doc[2]/long[@name='val2_l_dvo'][.='"+(numPartialUpdates+2)+"']",
        "//result/doc[3]/long[@name='val2_l_dvo'][.='"+(numPartialUpdates-1)+"']",
        "//result/doc[1]/int[@name='[docid]'][.='"+docid1+"']",
        "//result/doc[2]/int[@name='[docid]'][.='"+docid2+"']",
        "//result/doc[3]/int[@name='[docid]'][.='"+docid3+"']",
        "//result/doc[1]/long[@name='_version_'][.='" + version1 + "']",
        "//result/doc[2]/long[@name='_version_'][.='" + version2 + "']",
        "//result/doc[3]/long[@name='_version_'][.='" + version3 + "']"
        );
  }

  /**
   * Useful to store the state of an expected document into an in-memory model
   * representing the index.
   */
  private static class DocInfo {
    public final long version;
    public final Long value;

    public DocInfo(long version, Long val) {
      this.version = version;
      this.value = val;
    }

    @Override
    public String toString() {
      return "["+version+", "+value+"]";
    }
  }

  /** @see #checkReplay */
  @Test
  public void testReplay_AfterInitialAddMixOfIncAndSet() throws Exception {
    checkReplay("val2_l_dvo",
        //
        sdoc("id", "0", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("inc", 3)),
        HARDCOMMIT,
        sdoc("id", "0", "val2_l_dvo", map("inc", 5)),
        sdoc("id", "1", "val2_l_dvo", 2000000000L),
        sdoc("id", "1", "val2_l_dvo", map("set", 2000000002L)),
        sdoc("id", "1", "val2_l_dvo", map("set", 3000000000L)),
        sdoc("id", "0", "val2_l_dvo", map("inc", 7)),
        sdoc("id", "1", "val2_l_dvo", map("set", 7000000000L)),
        sdoc("id", "0", "val2_l_dvo", map("inc", 11)),
        sdoc("id", "2", "val2_l_dvo", 2000000000L),
        HARDCOMMIT,
        sdoc("id", "2", "val2_l_dvo", map("set", 3000000000L)),
        HARDCOMMIT);
  }

  /** @see #checkReplay */
  @Test
  public void testReplay_AfterInitialAddMixOfIncAndSetAndFullUpdates() throws Exception {
    checkReplay("val2_l_dvo",
        //
        sdoc("id", "0", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000003L)),
        HARDCOMMIT,
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000008L)),
        sdoc("id", "1", "val2_l_dvo", 2000000000L),
        sdoc("id", "1", "val2_l_dvo", map("inc", 2)),
        sdoc("id", "1", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000015L)),
        sdoc("id", "1", "val2_l_dvo", 7000000000L),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000026L)),
        sdoc("id", "2", "val2_l_dvo", 2000000000L),
        HARDCOMMIT,
        sdoc("id", "2", "val2_l_dvo", 3000000000L),
        HARDCOMMIT);
  }

  /** @see #checkReplay */
  @Test
  public void testReplay_AllUpdatesAfterInitialAddAreInc() throws Exception {
    checkReplay("val2_l_dvo",
        //
        sdoc("id", "0", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("inc", 3)),
        HARDCOMMIT,
        sdoc("id", "0", "val2_l_dvo", map("inc", 5)),
        sdoc("id", "1", "val2_l_dvo", 2000000000L),
        sdoc("id", "1", "val2_l_dvo", map("inc", 2)),
        sdoc("id", "1", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("inc", 7)),
        sdoc("id", "1", "val2_l_dvo", 7000000000L),
        sdoc("id", "0", "val2_l_dvo", map("inc", 11)),
        sdoc("id", "2", "val2_l_dvo", 2000000000L),
        HARDCOMMIT,
        sdoc("id", "2", "val2_l_dvo", 3000000000L),
        HARDCOMMIT);
  }

  /** @see #checkReplay */
  @Test
  public void testReplay_AllUpdatesAfterInitialAddAreSets() throws Exception {
    checkReplay("val2_l_dvo",
        //
        sdoc("id", "0", "val2_l_dvo", 3000000000L),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000003L)),
        HARDCOMMIT,
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000008L)),
        sdoc("id", "1", "val2_l_dvo", 2000000000L),
        sdoc("id", "1", "val2_l_dvo", map("set", 2000000002L)),
        sdoc("id", "1", "val2_l_dvo", map("set", 3000000000L)),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000015L)),
        sdoc("id", "1", "val2_l_dvo", map("set", 7000000000L)),
        sdoc("id", "0", "val2_l_dvo", map("set", 3000000026L)),
        sdoc("id", "2", "val2_l_dvo", 2000000000L),
        HARDCOMMIT,
        sdoc("id", "2", "val2_l_dvo", map("set", 3000000000L)),
        HARDCOMMIT
        );
  }
  
  /** @see #checkReplay */
  @Test
  public void testReplay_MixOfInplaceAndNonInPlaceAtomicUpdates() throws Exception {
    checkReplay("inplace_l_dvo",
                //
                sdoc("id", "3", "inplace_l_dvo", map("inc", -13)),
                sdoc("id", "3", "inplace_l_dvo", map("inc", 19),    "regular_l", map("inc", -17)),
                sdoc("id", "1",                                     "regular_l", map("inc", -19)),
                sdoc("id", "3", "inplace_l_dvo", map("inc", -11)),
                sdoc("id", "2", "inplace_l_dvo", map("set", 28)),
                HARDCOMMIT,
                sdoc("id", "2", "inplace_l_dvo", map("inc", 45)),
                sdoc("id", "3", "inplace_l_dvo", map("set", 72)),
                sdoc("id", "2",                                     "regular_l", map("inc", -55)),
                sdoc("id", "2", "inplace_l_dvo", -48,               "regular_l", 159),
                sdoc("id", "3", "inplace_l_dvo", 52,                "regular_l", 895),
                sdoc("id", "2", "inplace_l_dvo", map("inc", 19)),
                sdoc("id", "3", "inplace_l_dvo", map("inc", -264),  "regular_l", map("inc", -207)),
                sdoc("id", "3", "inplace_l_dvo", -762,              "regular_l", 272),
                SOFTCOMMIT);
  }
  
  @Test
  public void testReplay_SetOverriddenWithNoValueThenInc() throws Exception {
    final String inplaceField = "inplace_l_dvo"; 
    // final String inplaceField = "inplace_nocommit_not_really_l"; // nocommit: "inplace_l_dvo"
    
    checkReplay(inplaceField,
                //
                sdoc("id", "1", inplaceField, map("set", 555L)),
                SOFTCOMMIT,
                sdoc("id", "1", "regular_l", 666L), // NOTE: no inplaceField, regular add w/overwrite 
                sdoc("id", "1", inplaceField, map("inc", -77)),
                HARDCOMMIT);
  }

  @Test
  public void testReplay_nocommit() throws Exception { 
    // nocommit: this sequence came from a randomized test fail
    // nocommit: distilled what seem to be key bits of this sequence into testReplay_SetOverriddenWithNoValueThenInc
    // nocommit: if this test passes once testReplay_SetOverriddenWithNoValueThenInc passes, can prob just delete this test
    // nocommit: otherwise there's still some other bug, and this test should stay with a better name
    
    
    checkReplay("inplace_l_dvo",
                //
                sdoc("id", "1", "inplace_l_dvo", map("set", 5227783332305435299L)),
                sdoc("id", "1", "inplace_l_dvo", map("set", 5177016292017914821L)),
                SOFTCOMMIT,
                sdoc("id", "4", "inplace_l_dvo", -1041456048397735257L, "regular_l", -8163239469025076016L),
                sdoc("id", "1", "regular_l", 1844427848265038310L),
                sdoc("id", "3", "regular_l", -5364557547718093009L),
                sdoc("id", "4", "inplace_l_dvo", 352737585764277911L, "regular_l", -2324552916378921247L),
                sdoc("id", "4", "inplace_l_dvo", map("set", 1522902648458117343L)),
                sdoc("id", "4", "inplace_l_dvo", map("inc", -1561423187L)),
                sdoc("id", "4", "inplace_l_dvo", map("set", -7306651717735309226L)),
                sdoc("id", "2", "inplace_l_dvo", map("set", -5108528772159723270L)),
                sdoc("id", "4", "inplace_l_dvo", map("set", -3571192220077854115L)),
                sdoc("id", "1", "inplace_l_dvo", map("inc", -770582321L)),
                sdoc("id", "4", "inplace_l_dvo", map("inc", -342982790L)),
                sdoc("id", "2", "inplace_l_dvo", map("inc", -806437401L)),
                sdoc("id", "4", "inplace_l_dvo", map("inc", -617215801), "regular_l", map("inc", 1767173241L)),
                sdoc("id", "4", "inplace_l_dvo", map("inc", 1951931987L)),
                sdoc("id", "3", "regular_l", -3216004491312079553L),
                sdoc("id", "4", "inplace_l_dvo", map("set", -7877563014171453076L)),
                sdoc("id", "2", "regular_l", 6651364545098361399L),
                HARDCOMMIT);
    
  }

  
  
  /** 
   * Simple enum for randomizing a type of update.
   * Each enum value has an associated probability, and the class has built in sanity checks 
   * that the total is 100%
   * 
   * @see RandomUpdate#pick
   * @see #checkRandomReplay
   */
  private static enum RandomUpdate {
    HARD_COMMIT(5), 
    SOFT_COMMIT(5),

    /** doc w/o the inplaceField, atomic update on some other (non-inplace) field */
    ATOMIC_NOT_INPLACE(5),
    
    /** atomic update of a doc w/ inc on both inplaceField *AND* non-inplace field */
    ATOMIC_INPLACE_AND_NOT_INPLACE(10), 

    
    /** atomic update of a doc w/ set inplaceField */
    ATOMIC_INPLACE_SET(25),
    /** atomic update of a doc w/ inc inplaceField */
    ATOMIC_INPLACE_INC(25), 
    
    /** doc w/o the inplaceField, normal add */
    ADD_NO_INPLACE_VALUE(5),
    /** a non atomic update of a doc w/ new inplaceField value */
    ADD_INPLACE_VALUE(20); 
    
    private RandomUpdate(int odds) {
      this.odds = odds;
    }
    public final int odds;

    static { // sanity check odds add up to 100%
      int total = 0;
      for (RandomUpdate candidate : RandomUpdate.values()) {
        total += candidate.odds;
      }
      assertEquals("total odds doesn't equal 100", 100, total);
    }

    /** pick a random type of RandomUpdate */
    public static final RandomUpdate pick(Random r) {
      final int target = TestUtil.nextInt(r, 1, 100);
      int cumulative_odds = 0;
      for (RandomUpdate candidate : RandomUpdate.values()) {
        cumulative_odds += candidate.odds;
        if (target <= cumulative_odds) {
          return candidate;
        }
      }
      fail("how did we not find a candidate? target=" + target + ", cumulative_odds=" + cumulative_odds);
      return null; // compiler mandated return
    }
  }
  
  /** @see #checkRandomReplay */
  @Test
  public void testReplay_Random_ManyDocsManyUpdates() throws Exception {
    
    // build up a random list of updates
    final int maxDocId = atLeast(50);
    final int numUpdates = maxDocId * 3;
    checkRandomReplay(maxDocId, numUpdates);
  }
  
  /** @see #checkRandomReplay */
  @Test
  public void testReplay_Random_FewDocsManyUpdates() throws Exception {
    
    // build up a random list of updates
    final int maxDocId = atLeast(3);
    final int numUpdates = maxDocId * 50;
    checkRandomReplay(maxDocId, numUpdates);
  }
  
  /** @see #checkRandomReplay */
  @Test
  public void testReplay_Random_FewDocsManyShortSequences() throws Exception {
    
    // build up a random list of updates
    final int numIters = atLeast(50);
    
    for (int i = 0; i < numIters; i++) {
      final int maxDocId = atLeast(3);
      final int numUpdates = maxDocId * 5;
      checkRandomReplay(maxDocId, numUpdates);
      deleteAllAndCommit();
    }
  }


  /** 
   * @see #checkReplay 
   * @see RandomUpdate
   */
  public void checkRandomReplay(final int maxDocId, final int numCmds) throws Exception {
    
    final String not_inplaceField = "regular_l";

    // nocommit: can use a regular long field to sanity check if failing seed is general
    //           bug with test/atomic update code, or specific to inplace update
    //
    // nocommit: should we randomize this when committing?
    //
    //final String inplaceField = "nocommit_not_really_inplace_l"; // nocommit
    final String inplaceField = "inplace_l_dvo"; 

    final Object[] cmds = new Object[numCmds];
    for (int iter = 0; iter < numCmds; iter++) {
      final int id = TestUtil.nextInt(random(), 1, maxDocId);
      final RandomUpdate update = RandomUpdate.pick(random());

      switch (update) {
        
      case HARD_COMMIT:
        cmds[iter] = HARDCOMMIT;
        break;
        
      case SOFT_COMMIT:
        cmds[iter] = SOFTCOMMIT;
        break;

      case ATOMIC_NOT_INPLACE:
        // atomic update on non_inplaceField, w/o any value specified for inplaceField
        cmds[iter] = sdoc("id", id,
                          not_inplaceField, map("inc", random().nextInt()));
        break;
        
      case ATOMIC_INPLACE_AND_NOT_INPLACE:
        // atomic update of a doc w/ inc on both inplaceField and not_inplaceField
        cmds[iter] = sdoc("id", id,
                          inplaceField, map("inc", random().nextInt()),
                          not_inplaceField, map("inc", random().nextInt()));
        break;

      case ATOMIC_INPLACE_SET:
        // atomic update of a doc w/ set inplaceField
        cmds[iter] = sdoc("id", id,
                          inplaceField, map("set", random().nextLong()));
        break;

      case ATOMIC_INPLACE_INC:
        // atomic update of a doc w/ inc inplaceField
        cmds[iter] = sdoc("id", id,
                          inplaceField, map("inc", random().nextInt()));
        break;

      case ADD_NO_INPLACE_VALUE:
        // regular add of doc w/o the inplaceField, but does include non_inplaceField
        cmds[iter] = sdoc("id", id,
                          not_inplaceField, random().nextLong());
        break;

      case ADD_INPLACE_VALUE:
        // a non atomic update of a doc w/ new inplaceField value
        cmds[iter] = sdoc("id", id,
                          inplaceField, random().nextLong(),
                          not_inplaceField, random().nextLong());
        break;
        
      default:
        fail("WTF is this? ... " + update);
      }
      
      assertNotNull(cmds[iter]); // sanity check switch
    }

    // nocommit: uncomment for quick sanity checking reproducibility, not a good idea to log in general
    // System.err.println("nocommit: sequence == " + Arrays.asList(cmds));
    
    checkReplay(inplaceField, cmds);
  }
  
  /** sentinal object for {@link #checkReplay} */
  public Object SOFTCOMMIT = new Object() { public String toString() { return "SOFTCOMMIT"; } };
  /** sentinal object for {@link #checkReplay} */
  public Object HARDCOMMIT = new Object() { public String toString() { return "HARDCOMMIT"; } };

  /**
   * Executes a sequence of commands against Solr, while tracking the expected value of a specified 
   * <code>valField</code> Long field (presumably that only uses docvalues) against an in memory model 
   * maintained in parallel (for the purpose of testing the correctness of in-place updates..
   *
   * <p>
   * A few restrictions are placed on the {@link SolrInputDocument}s that can be included when using 
   * this method, in order to keep the in-memory model management simple:
   * </p>
   * <ul>
   *  <li><code>id</code> must be uniqueKey field</li>
   *  <li><code>id</code> may have any FieldType, but all values must be parsable as Integers</li>
   *  <li><code>valField</code> must be a single valued field</li>
   *  <li>All values in the <code>valField</code> must either be {@link Number}s, or Maps containing 
   *      atomic updates ("inc" or "set") where the atomic value is a {@link Number}</li>
   * </ul>
   * 
   * @param valField the field to model
   * @param commands A sequence of Commands which can either be SolrInputDocuments 
   *                 (regular or containing atomic update Maps)
   *                 or one of the {@link TestInPlaceUpdatesStandalone#HARDCOMMIT} or {@link TestInPlaceUpdatesStandalone#SOFTCOMMIT} sentinal objects.
   */
  public void checkReplay(final String valField, Object... commands) throws Exception {
    
    HashMap<Integer, DocInfo> model = new LinkedHashMap<>();
    HashMap<Integer, DocInfo> committedModel = new LinkedHashMap<>();

    // by default, we only check the committed model after a commit
    // of if the number of total commands is relatively small.
    //
    // (in theory, there's no reason to check the committed model unless we know there's been a commit
    // but for smaller tests the overhead of doing so is tiny, so we might as well)
    //
    // if some test seed fails, and you want to force the committed model to be checked
    // after every command, just temporaribly force this variable to true...
    boolean checkCommittedModel = (commands.length < 50);
    
    for (Object cmd : commands) {
      if (cmd == SOFTCOMMIT) {
        assertU(commit("softCommit", "true"));
        committedModel = new LinkedHashMap(model);
        checkCommittedModel = true;
      } else if (cmd == HARDCOMMIT) {
        assertU(commit("softCommit", "false"));
        committedModel = new LinkedHashMap(model);
        checkCommittedModel = true;
      } else {
        assertNotNull("null command in checkReplay", cmd);
        assertTrue("cmd is neither sentinal (HARD|SOFT)COMMIT object, nor Solr doc: " + cmd.getClass(),
                   cmd instanceof SolrInputDocument);
        
        final SolrInputDocument sdoc = (SolrInputDocument) cmd;
        final int id = Integer.parseInt(sdoc.getFieldValue("id").toString());
        
        final DocInfo previousInfo = model.get(id);
        final Long previousValue = (null == previousInfo) ? null : previousInfo.value;
        
        final long version = addAndGetVersion(sdoc, null);
        
        final Object val = sdoc.getFieldValue(valField);
        if (val instanceof Map) {
          // atomic update of the field we're modeling
          
          Map<String,?> atomicUpdate = (Map) val;
          assertEquals(sdoc.toString(), 1, atomicUpdate.size());
          if (atomicUpdate.containsKey("inc")) {
            // Solr treats inc on a non-existing doc (or doc w/o existing value) as if existing value is 0
            final long base = (null == previousValue) ? 0L : previousValue;
            model.put(id, new DocInfo(version,
                                      base + ((Number)atomicUpdate.get("inc")).longValue()));
          } else if (atomicUpdate.containsKey("set")) {
            model.put(id, new DocInfo(version, ((Number)atomicUpdate.get("set")).longValue()));
          } else {
            fail("wtf update is this? ... " + sdoc);
          }
        } else if (null == val) {
          // the field we are modeling is not mentioned in this update, It's either...
          //
          // a) a regular update of some other fields (our model should have a null value)
          // b) an atomic update of some other field (keep existing value in model)
          //
          // for now, assume it's atomic and we're going to keep our existing value...
          Long newValue = (null == previousInfo) ? null : previousInfo.value;
          for (SolrInputField field : sdoc) {
            if (! ( "id".equals(field.getName()) || (field.getValue() instanceof Map)) ) {
              // not an atomic update, newValue in model should be null
              newValue = null;
              break;
            }
          }
          model.put(id, new DocInfo(version, newValue));
          
        } else {
          // regular replacement of the value in the field we're modeling
          
          assertTrue("Model field value is not a Number: " + val.getClass(), val instanceof Number);
          model.put(id, new DocInfo(version, ((Number)val).longValue()));
        }
      }

      // after every op, check the model(s)
      
      // RTG to check the values for every id against the model
      for (Map.Entry<Integer, DocInfo> entry : model.entrySet()) {
        final Long expected = entry.getValue().value;
        assertEquals(expected, client.getById(String.valueOf(entry.getKey())).getFirstValue(valField));
      }

      // search to check the values for every id in the committed model
      if (checkCommittedModel) {
        final int numCommitedDocs = committedModel.size();
        String[] xpaths = new String[1 + numCommitedDocs];
        int i = 0;
        for (Map.Entry<Integer, DocInfo> entry : committedModel.entrySet()) {
          Integer id = entry.getKey();
          Long expected = entry.getValue().value;
          if (null != expected) {
            xpaths[i] = "//result/doc[./str='"+id+"'][./long='"+expected+"']";
          } else {
            xpaths[i] = "//result/doc[./str='"+id+"'][not(./long)]";
          }           
          i++;
        }
        xpaths[i] = "//*[@numFound='"+numCommitedDocs+"']";
        assertQ(req("q", "*:*",
                    "fl", "id," + valField,
                    "rows", ""+numCommitedDocs),
                xpaths);
      }
    }
  }

  @Test
  public void testMixedInPlaceAndNonInPlaceAtomicUpdates() throws Exception {
    SolrDocument rtgDoc = null;
    long version1 = addAndGetVersion(sdoc("id", "1", "inplace_updatable_float", "100", "stored_i", "100"), params());

    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", "1"), "stored_i", map("inc", "1"));
    rtgDoc = client.getById("1");
    assertEquals(101, rtgDoc.getFieldValue("stored_i"));
    assertEquals(101.0f, rtgDoc.getFieldValue("inplace_updatable_float"));
    
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("inc", "1"));
    rtgDoc = client.getById("1");
    assertEquals(101, rtgDoc.getFieldValue("stored_i"));
    assertEquals(102.0f, rtgDoc.getFieldValue("inplace_updatable_float"));

    version1 = addAndAssertVersion(version1, "id", "1", "stored_i", map("inc", "1"));
    rtgDoc = client.getById("1");
    assertEquals(102, rtgDoc.getFieldValue("stored_i"));
    assertEquals(102.0f, rtgDoc.getFieldValue("inplace_updatable_float"));

    assertU(commit("softCommit", "false"));
    assertQ(req("q", "*:*", "sort", "id asc", "fl", "*"),
            "//*[@numFound='1']",
            "//result/doc[1]/float[@name='inplace_updatable_float'][.='102.0']",
            "//result/doc[1]/int[@name='stored_i'][.='102']",
            "//result/doc[1]/long[@name='_version_'][.='" + version1 + "']"
            );

    // recheck RTG after commit
    rtgDoc = client.getById("1");
    assertEquals(102, rtgDoc.getFieldValue("stored_i"));
    assertEquals(102.0f, rtgDoc.getFieldValue("inplace_updatable_float"));
  }

  /** @see AtomicUpdateDocumentMerger#isInPlaceUpdate */
  @Test
  public void testIsInPlaceUpdate() throws Exception {
    Set<String> inPlaceUpdatedFields = new HashSet<String>();

    // In-place updates:
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_float", map("set", 10))));
    assertTrue(inPlaceUpdatedFields.contains("inplace_updatable_float"));

    inPlaceUpdatedFields.clear();
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_float", map("inc", 10))));
    assertTrue(inPlaceUpdatedFields.contains("inplace_updatable_float"));

    inPlaceUpdatedFields.clear();
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_int", map("set", 10))));
    assertTrue(inPlaceUpdatedFields.contains("inplace_updatable_int"));

    // Non in-place updates
    inPlaceUpdatedFields.clear();
    addAndGetVersion(sdoc("id", "1", "stored_i", "0"), params()); // setting up the dv
    assertTrue("stored field updated", AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "stored_i", map("inc", 1)))).isEmpty());

    assertTrue("No map means full document update", AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_int", "100"))).isEmpty());

    assertTrue("non existent dynamic dv field updated first time",
        AtomicUpdateDocumentMerger.isInPlaceUpdate(
            UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "new_updateable_int_i_dvo", map("set", 10)))).isEmpty());

    // After adding a full document with the dynamic dv field, in-place update should work
    addAndGetVersion(sdoc("id", "2", "new_updateable_int_i_dvo", "0"), params()); // setting up the dv
    if (random().nextBoolean())
      assertU(commit("softCommit", "false"));
    inPlaceUpdatedFields.clear();
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate(
        UpdateLogTest.getAddUpdate(null, sdoc("id", "2", "_version_", 42L, "new_updateable_int_i_dvo", map("set", 10))));
    assertTrue(inPlaceUpdatedFields.contains("new_updateable_int_i_dvo"));

    // If a supported dv field has a copyField target which is supported, it should be an in-place update
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate
      (UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L,
                                             "copyfield1_src__both_updateable", map("set", 10))));
    assertTrue(inPlaceUpdatedFields.contains("copyfield1_src__both_updateable"));

    // If a supported dv field has a copyField target which is not supported, it should not be an in-place update
    inPlaceUpdatedFields = AtomicUpdateDocumentMerger.isInPlaceUpdate
      (UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L,
                                             "copyfield2_src__only_src_updatable", map("set", 10))));
    assertTrue(inPlaceUpdatedFields.isEmpty());
  }

  @Test
  /**
   *  Test the @see {@link AtomicUpdateDocumentMerger#doInPlaceUpdateMerge(AddUpdateCommand,Set<String>)} 
   *  method is working fine
   */
  public void testDoInPlaceUpdateMerge() throws Exception {
    long version1 = addAndGetVersion(sdoc("id", "1", "title_s", "first"), null);
    long version2 = addAndGetVersion(sdoc("id", "2", "title_s", "second"), null);
    long version3 = addAndGetVersion(sdoc("id", "3", "title_s", "third"), null);
    assertU(commit("softCommit", "false"));
    assertQ(req("q", "*:*"), "//*[@numFound='3']");

    // Adding a few in-place updates
    version1 = addAndAssertVersion(version1, "id", "1", "inplace_updatable_float", map("set", 200));

    // Test the AUDM.doInPlaceUpdateMerge() method is working fine
    AddUpdateCommand cmd = UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_float", map("inc", 10)));
    SolrQueryRequest req = new LocalSolrQueryRequest(h.getCore(), params());
    AtomicUpdateDocumentMerger docMerger = new AtomicUpdateDocumentMerger(req);
    boolean done = docMerger.doInPlaceUpdateMerge(cmd, AtomicUpdateDocumentMerger.isInPlaceUpdate(cmd));
    assertTrue(done);
    assertEquals(42L, cmd.getSolrInputDocument().getFieldValue("_version_"));
    assertEquals(42L, cmd.getSolrInputDocument().getFieldValue("_version_"));
    assertEquals(210f, cmd.getSolrInputDocument().getFieldValue("inplace_updatable_float"));
    assertFalse(cmd.getSolrInputDocument().containsKey("title_s")); // in-place merged doc shouldn't have non-inplace fields from the index/tlog
    assertEquals(version1, cmd.prevVersion);

    // do a commit, and the same results should be repeated
    assertU(commit("softCommit", "false"));

    cmd = UpdateLogTest.getAddUpdate(null, sdoc("id", "1", "_version_", 42L, "inplace_updatable_float", map("inc", 10)));
    done = docMerger.doInPlaceUpdateMerge(cmd, AtomicUpdateDocumentMerger.isInPlaceUpdate(cmd));
    assertTrue(done);
    assertEquals(42L, cmd.getSolrInputDocument().getFieldValue("_version_"));
    assertEquals(42L, cmd.getSolrInputDocument().getFieldValue("_version_"));
    assertEquals(210f, cmd.getSolrInputDocument().getFieldValue("inplace_updatable_float"));
    assertFalse(cmd.getSolrInputDocument().containsKey("title_s")); // in-place merged doc shouldn't have non-inplace fields from the index/tlog
    assertEquals(version1, cmd.prevVersion);
  }
}