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
package org.apache.lucene.queryparser.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.analysis.MockTokenFilter;
import org.apache.lucene.tests.analysis.MockTokenizer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.junit.AfterClass;
import org.xml.sax.SAXException;

public class TestCoreParser extends LuceneTestCase {

  private static final String defaultField = "contents";

  @SuppressWarnings("NonFinalStaticField")
  private static Analyzer analyzer;

  @SuppressWarnings("NonFinalStaticField")
  private static CoreParser coreParser;

  @SuppressWarnings("NonFinalStaticField")
  private static CoreParserTestIndexData indexData;

  protected Analyzer newAnalyzer() {
    // TODO: rewrite test (this needs to set QueryParser.enablePositionIncrements, too, for work
    // with CURRENT):
    return new MockAnalyzer(
        random(), MockTokenizer.WHITESPACE, true, MockTokenFilter.ENGLISH_STOPSET);
  }

  protected CoreParser newCoreParser(String defaultField, Analyzer analyzer) {
    return new CoreParser(defaultField, analyzer);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (indexData != null) {
      indexData.close();
      indexData = null;
    }
    coreParser = null;
    analyzer = null;
  }

  public void testTermQueryXML() throws ParserException, IOException {
    Query q = parse("TermQuery.xml");
    dumpResults("TermQuery", q, 5);
  }

  public void test_DOCTYPE_TermQueryXML() throws ParserException, IOException {
    SAXException saxe =
        LuceneTestCase.expectThrows(
            ParserException.class, SAXException.class, () -> parse("DOCTYPE_TermQuery.xml"));
    assertTrue(saxe.getMessage().startsWith("External Entity resolving unsupported:"));
  }

  public void test_ENTITY_TermQueryXML() throws ParserException, IOException {
    SAXException saxe =
        LuceneTestCase.expectThrows(
            ParserException.class, SAXException.class, () -> parse("ENTITY_TermQuery.xml"));
    assertTrue(saxe.getMessage().startsWith("External Entity resolving unsupported:"));
  }

  public void testTermQueryEmptyXML() throws ParserException, IOException {
    parseShouldFail("TermQueryEmpty.xml", "TermQuery has no text");
  }

  public void testTermsQueryXML() throws ParserException, IOException {
    Query q = parse("TermsQuery.xml");
    dumpResults("TermsQuery", q, 5);
  }

  public void testBooleanQueryXML() throws ParserException, IOException {
    Query q = parse("BooleanQuery.xml");
    dumpResults("BooleanQuery", q, 5);
  }

  public void testDisjunctionMaxQueryXML() throws ParserException, IOException {
    Query q = parse("DisjunctionMaxQuery.xml");
    Query expected =
        new DisjunctionMaxQuery(
            Arrays.asList(
                new TermQuery(new Term("a", "merger")),
                new DisjunctionMaxQuery(
                    Arrays.asList(new TermQuery(new Term("b", "verger"))), 0.3f)),
            0.0f);
    assertEquals(expected, q);
  }

  public void testRangeQueryXML() throws ParserException, IOException {
    Query q = parse("RangeQuery.xml");
    dumpResults("RangeQuery", q, 5);
  }

  public void testUserQueryXML() throws ParserException, IOException {
    Query q = parse("UserInputQuery.xml");
    dumpResults("UserInput with Filter", q, 5);
  }

  public void testCustomFieldUserQueryXML() throws ParserException, IOException {
    Query q = parse("UserInputQueryCustomField.xml");
    long h = searcher().search(q, 1000).totalHits.value();
    assertEquals("UserInputQueryCustomField should produce 0 result ", 0, h);
  }

  public void testBoostingTermQueryXML() throws Exception {
    Query q = parse("BoostingTermQuery.xml");
    dumpResults("BoostingTermQuery", q, 5);
  }

  public void testSpanTermXML() throws Exception {
    Query q = parse("SpanQuery.xml");
    dumpResults("Span Query", q, 5);
    SpanQuery sq = parseAsSpan("SpanQuery.xml");
    dumpResults("Span Query", sq, 5);
    assertEquals(q, sq);
  }

  public void testSpanPositionRangeQueryXML() throws Exception {
    Query q = parse("SpanPositionRangeQuery.xml");
    long h = searcher().search(q, 10).totalHits.value();
    assertEquals("SpanPositionRangeQuery should produce 2 result ", 2, h);
    SpanQuery sq = parseAsSpan("SpanPositionRangeQuery.xml");
    dumpResults("SpanPositionRangeQuery", sq, 5);
    assertEquals(q, sq);
  }

  public void testSpanNearQueryWithoutSlopXML() throws Exception {
    // expected NumberFormatException from empty "slop" string
    assertThrows(NumberFormatException.class, () -> parse("SpanNearQueryWithoutSlop.xml"));
    assertThrows(NumberFormatException.class, () -> parseAsSpan("SpanNearQueryWithoutSlop.xml"));
  }

  public void testConstantScoreQueryXML() throws Exception {
    Query q = parse("ConstantScoreQuery.xml");
    dumpResults("ConstantScoreQuery", q, 5);
  }

  public void testMatchAllDocsPlusFilterXML() throws ParserException, IOException {
    Query q = parse("MatchAllDocsQuery.xml");
    dumpResults("MatchAllDocsQuery with range filter", q, 5);
  }

  public void testNestedBooleanQuery() throws ParserException, IOException {
    Query q = parse("NestedBooleanQuery.xml");
    dumpResults("Nested Boolean query", q, 5);
  }

  public void testPointRangeQuery() throws ParserException, IOException {
    Query q = parse("PointRangeQuery.xml");
    dumpResults("PointRangeQuery", q, 5);
  }

  public void testPointRangeQueryWithoutLowerTerm() throws ParserException, IOException {
    Query q = parse("PointRangeQueryWithoutLowerTerm.xml");
    dumpResults("PointRangeQueryWithoutLowerTerm", q, 5);
  }

  public void testPointRangeQueryWithoutUpperTerm() throws ParserException, IOException {
    Query q = parse("PointRangeQueryWithoutUpperTerm.xml");
    dumpResults("PointRangeQueryWithoutUpperTerm", q, 5);
  }

  public void testPointRangeQueryWithoutRange() throws ParserException, IOException {
    Query q = parse("PointRangeQueryWithoutRange.xml");
    dumpResults("PointRangeQueryWithoutRange", q, 5);
  }

  public void testSpanBoosts() throws Exception {
    String topLevel = "<SpanTerm fieldName=\"field\" boost=\"2\">value</SpanTerm>";
    try (ByteArrayInputStream is =
        new ByteArrayInputStream(topLevel.getBytes(StandardCharsets.UTF_8))) {
      Query actual = coreParser().parse(is);
      Query expected = new BoostQuery(new SpanTermQuery(new Term("field", "value")), 2);
      assertEquals(expected, actual);
    }

    String nested =
        "<SpanNear fieldName=\"field\" boost=\"2\" slop=\"8\" inOrder=\"false\">"
            + // top level boost is preserved
            " <SpanTerm boost=\"4\">value1</SpanTerm>"
            + // interior boost is ignored
            " <SpanTerm>value2</SpanTerm>"
            + "</SpanNear>";
    try (ByteArrayInputStream is =
        new ByteArrayInputStream(nested.getBytes(StandardCharsets.UTF_8))) {
      Query actual = coreParser().parse(is);
      Query expected =
          new BoostQuery(
              new SpanNearQuery(
                  new SpanQuery[] {
                    new SpanTermQuery(new Term("field", "value1")),
                    new SpanTermQuery(new Term("field", "value2"))
                  },
                  8,
                  false),
              2);
      assertEquals(expected, actual);
    }
  }

  // ================= Helper methods ===================================

  protected String defaultField() {
    return defaultField;
  }

  protected Analyzer analyzer() {
    if (analyzer == null) {
      analyzer = newAnalyzer();
    }
    return analyzer;
  }

  protected CoreParser coreParser() {
    if (coreParser == null) {
      coreParser = newCoreParser(defaultField, analyzer());
    }
    return coreParser;
  }

  private CoreParserTestIndexData indexData() {
    if (indexData == null) {
      try {
        indexData = new CoreParserTestIndexData(analyzer());
      } catch (Exception e) {
        fail("caught Exception " + e);
      }
    }
    return indexData;
  }

  protected IndexReader reader() {
    return indexData().reader;
  }

  protected IndexSearcher searcher() {
    return indexData().searcher;
  }

  protected void parseShouldFail(String xmlFileName, String expectedParserExceptionMessage)
      throws IOException {
    Query q = null;
    ParserException pe = null;
    try {
      q = parse(xmlFileName);
    } catch (ParserException e) {
      pe = e;
    }
    assertNull("for " + xmlFileName + " unexpectedly got " + q, q);
    assertNotNull("expected a ParserException for " + xmlFileName, pe);
    assertEquals(
        "expected different ParserException for " + xmlFileName,
        expectedParserExceptionMessage,
        pe.getMessage());
  }

  protected Query parse(String xmlFileName) throws ParserException, IOException {
    return implParse(xmlFileName, false);
  }

  protected SpanQuery parseAsSpan(String xmlFileName) throws ParserException, IOException {
    return (SpanQuery) implParse(xmlFileName, true);
  }

  private Query implParse(String xmlFileName, boolean span) throws ParserException, IOException {
    try (InputStream xmlStream = TestCoreParser.class.getResourceAsStream(xmlFileName)) {
      assertNotNull("Test XML file " + xmlFileName + " cannot be found", xmlStream);
      if (span) {
        return coreParser().parseAsSpanQuery(xmlStream);
      } else {
        return coreParser().parse(xmlStream);
      }
    }
  }

  protected Query rewrite(Query q) throws IOException {
    return q.rewrite(searcher());
  }

  protected void dumpResults(String qType, Query q, int numDocs) throws IOException {
    if (VERBOSE) {
      System.out.println(
          "TEST: qType="
              + qType
              + " numDocs="
              + numDocs
              + " "
              + q.getClass().getCanonicalName()
              + " query="
              + q);
    }
    final IndexSearcher searcher = searcher();
    TopDocs hits = searcher.search(q, numDocs);
    final boolean producedResults = (hits.totalHits.value() > 0);
    if (!producedResults) {
      System.out.println(
          "TEST: qType="
              + qType
              + " numDocs="
              + numDocs
              + " "
              + q.getClass().getCanonicalName()
              + " query="
              + q);
    }
    if (VERBOSE) {
      ScoreDoc[] scoreDocs = hits.scoreDocs;
      StoredFields storedFields = searcher.storedFields();
      for (int i = 0; i < Math.min(numDocs, hits.totalHits.value()); i++) {
        Document ldoc = storedFields.document(scoreDocs[i].doc);
        System.out.println("[" + ldoc.get("date") + "]" + ldoc.get("contents"));
      }
      System.out.println();
    }
    assertTrue(qType + " produced no results", producedResults);
  }
}
