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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.lucene.codecs.lucene90.IndexedDISI;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.knn.KnnCollectorManager;
import org.apache.lucene.search.knn.KnnSearchStrategy;

/**
 * Like {@link KnnFloatVectorQuery} but makes an optimistic assumption about the distribution of
 * documents among segments: namely that they are uniform-random w.r.t. vector distance. This is
 * unsafe, so it checks the assumption after running the queries and runs a second pass if needed in
 * any segments for which the assumption proves to be false. The check is simple: is the worst hit
 * in the result queue for a segment in the global top K? If so, explore that segment further using
 * seeded KNN search query, seeding with the initial results.
 */
// TODO: rename as float? move methods to AbstractKnnVectorQuery?  make a Strategy? Replace existing
// collection strategy?
// yes, I think we should merge this stuff w/AbstractKnnVectorQuery, enable it with a
// KnnSearchStrategy,
// and extend KnnFloatVectorQuery/KnnByteVectorQuery in a simple way
public class OptimisticKnnVectorQuery extends KnnFloatVectorQuery {

  // Magic number that controls the per-leaf pro-rata calculation. Higher numbers mean a larger
  // queue is maintained, proportionally, for each leaf.
  private static final int LAMBDA = 5;
  private final int lambda;

  public OptimisticKnnVectorQuery(String field, float[] target, int k, int lambda, Query filter) {
    super(field, target, k, filter);
    this.lambda = lambda;
  }

  public OptimisticKnnVectorQuery(String field, float[] target, int k, int lambda) {
    this(field, target, k, lambda, null);
  }

  public OptimisticKnnVectorQuery(String field, float[] target, int k) {
    this(field, target, k, LAMBDA);
  }

  public OptimisticKnnVectorQuery(String field, float[] target, int k, Query filter) {
    this(field, target, k, LAMBDA, filter);
  }

  @Override
  protected TopDocs postProcessResults(IndexSearcher indexSearcher,
                                       Weight filterWeight,
                                       List<TopDocs> taskResults) throws IOException {
    if (taskResults.size() <= 1) {
      return super.postProcessResults(indexSearcher, filterWeight, taskResults);
    }
    List<LeafReaderContext> leafReaderContexts = indexSearcher.getIndexReader().leaves();
    Map<Integer, TopDocs> perLeafResults = new HashMap<>(leafReaderContexts.size());
    TopDocs topK = getTopDocs(taskResults, perLeafResults, leafReaderContexts);
    if (topK.scoreDocs.length == 0) {
      return topK;
    }

    float minTopKScore = topK.scoreDocs[topK.scoreDocs.length - 1].score;
    List<Callable<TopDocs>> tasks = new ArrayList<>();
    TimeLimitingKnnCollectorManager knnCollectorManagerInner =
            new TimeLimitingKnnCollectorManager(
                    new ReentrantKnnCollectorManager(
                            super.getKnnCollectorManager(k, indexSearcher), perLeafResults),
                    indexSearcher.getTimeout());
    Iterator<LeafReaderContext> ctxIter = leafReaderContexts.iterator();
    while (ctxIter.hasNext()) {
      LeafReaderContext ctx = ctxIter.next();
      TopDocs perLeaf = perLeafResults.get(ctx.ord);
      if (perLeaf.scoreDocs.length > 0
              && perLeaf.scoreDocs[perLeaf.scoreDocs.length - 1].score >= minTopKScore) {
        // All this leaf's hits are at or above the global topK min score; explore it further, and
        // we have not yet tried perLeafK >= k.
        // System.out.println("re-try search of leaf " + ctx.ord + "; K'=" + kInLoop);
        tasks.add(() -> searchLeaf(ctx, filterWeight, knnCollectorManagerInner));
      } else {
        // This leaf is tapped out; discard the context from the active list so we maintain
        // correspondence between tasks and leaves
        ctxIter.remove();
      }
    }

    taskResults = indexSearcher.getTaskExecutor().invokeAll(tasks);

    assert leafReaderContexts.size() == tasks.size();
    assert perLeafResults.size() == indexSearcher.getIndexReader().leaves().size();
    return getTopDocs(taskResults, perLeafResults, leafReaderContexts);
  }

  private TopDocs getTopDocs(List<TopDocs> taskResults,
                             Map<Integer, TopDocs> perLeafResults,
                             List<LeafReaderContext> leafReaderContexts) {
    for (int i = 0; i < taskResults.size(); i++) {
      perLeafResults.put(leafReaderContexts.get(i).ord, taskResults.get(i));
    }
    // Merge sort the results
    return mergeLeafResults(perLeafResults.values().toArray(TopDocs[]::new));
  }

  @Override
  protected KnnCollectorManager getKnnCollectorManager(int k, IndexSearcher searcher) {
    KnnCollectorManager manager =
            (visitedLimit, _, context) -> {
              @SuppressWarnings("resource")
              float leafProportion =
                      context.reader().maxDoc() / (float) context.parent.reader().maxDoc();
              int perLeafTopK = perLeafTopKCalculation(k, leafProportion);
              // if we divided by zero above, leafProportion can be NaN and then this would be 0
              assert perLeafTopK > 0;
              return new TopKnnCollector(perLeafTopK, visitedLimit);
            };
    return manager;
  }

  /*
   * Returns perLeafTopK, the expected number (K * leafProportion) of hits in a leaf with the given
   * proportion of the entire index, plus three standard deviations of a binomial distribution. Math
   * says there is a 95% probability that this segment's contribution to the global top K hits are
   * <= perLeafTopK.
   */
  private int perLeafTopKCalculation(int k, float leafProportion) {
    return (int)
            Math.max(
                    1, k * leafProportion + lambda * Math.sqrt(k * leafProportion * (1 - leafProportion)));
  }

  // forked from SeededKnnVectorQuery.SeededCollectorManager
  private class ReentrantKnnCollectorManager implements KnnCollectorManager {
    final KnnCollectorManager knnCollectorManager;
    final Map<Integer, TopDocs> perLeafResults;

    ReentrantKnnCollectorManager(
            KnnCollectorManager knnCollectorManager, Map<Integer, TopDocs> perLeafResults) {
      this.knnCollectorManager = knnCollectorManager;
      this.perLeafResults = perLeafResults;
    }

    @Override
    public KnnCollector newCollector(
            int visitLimit, KnnSearchStrategy searchStrategy, LeafReaderContext ctx)
            throws IOException {
      KnnCollector delegateCollector =
              knnCollectorManager.newCollector(visitLimit, searchStrategy, ctx);
      TopDocs seedTopDocs = perLeafResults.get(ctx.ord);
      VectorScorer scorer = createVectorScorer(ctx, ctx.reader().getFieldInfos().fieldInfo(field));
      if (seedTopDocs.totalHits.value() == 0 || scorer == null) {
        // shouldn't happen - we only come here when there are results
        assert false;
        // on the other hand, it should be safe to return no results?
        return delegateCollector;
      }
      DocIdSetIterator vectorIterator = scorer.iterator();
      // Handle sparse
      if (vectorIterator instanceof IndexedDISI indexedDISI) {
        vectorIterator = IndexedDISI.asDocIndexIterator(indexedDISI);
      }
      // Most underlying iterators are indexed, so we can map the seed docs to the vector docs
      if (vectorIterator instanceof KnnVectorValues.DocIndexIterator indexIterator) {
        DocIdSetIterator seedDocs =
                new SeededKnnVectorQuery.MappedDISI(
                        indexIterator, new SeededKnnVectorQuery.TopDocsDISI(seedTopDocs, ctx));
        return knnCollectorManager.newCollector(
                visitLimit,
                new KnnSearchStrategy.Seeded(seedDocs, seedTopDocs.scoreDocs.length, searchStrategy),
                ctx);
      }
      // could lead to an infinite loop if this ever happens
      assert false;
      return delegateCollector;
    }
  }
}
