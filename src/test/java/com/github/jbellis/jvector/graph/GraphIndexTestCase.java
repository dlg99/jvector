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

package com.github.jbellis.jvector.graph;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.github.jbellis.jvector.exceptions.ThreadInterruptedException;
import com.github.jbellis.jvector.util.Bits;
import com.github.jbellis.jvector.util.FixedBitSet;
import com.github.jbellis.jvector.vector.VectorEncoding;
import com.github.jbellis.jvector.vector.VectorSimilarityFunction;
import com.github.jbellis.jvector.vector.VectorUtil;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static com.github.jbellis.jvector.util.DocIdSetIterator.NO_MORE_DOCS;
import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests KNN graphs */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public abstract class GraphIndexTestCase<T> extends RandomizedTest {

  VectorSimilarityFunction similarityFunction;

  abstract VectorEncoding getVectorEncoding();

  abstract T randomVector(int dim);

  abstract AbstractMockVectorValues<T> vectorValues(int size, int dimension);

  abstract AbstractMockVectorValues<T> vectorValues(float[][] values);

  abstract AbstractMockVectorValues<T> vectorValues(
      int size,
      int dimension,
      AbstractMockVectorValues<T> pregeneratedVectorValues,
      int pregeneratedOffset);

  abstract RandomAccessVectorValues<T> circularVectorValues(int nDoc);

  abstract T getTargetVector();

  List<Integer> sortedNodes(GraphIndex h) throws IOException {
    var nodesOnLevel = h.getNodes();
    List<Integer> nodes = new ArrayList<>();
    while (nodesOnLevel.hasNext()) {
      nodes.add(nodesOnLevel.next());
    }
    Collections.sort(nodes);
    return nodes;
  }

  void assertGraphEqual(GraphIndex g, GraphIndex h) throws IOException {
    // construct these up front since they call seek which will mess up our test loop
    String prettyG = GraphIndex.prettyPrint(g);
    String prettyH = GraphIndex.prettyPrint(h);
    assertEquals(
        String.format(
            Locale.ROOT,
            "the number of nodes in the graphs are different:%n%s%n%s",
            prettyG,
            prettyH),
        g.size(),
        h.size());

    // assert equal nodes on each level
    List<Integer> hNodes = sortedNodes(h);
    List<Integer> gNodes = sortedNodes(g);
    assertEquals(
            String.format(
                    Locale.ROOT,
                    "nodes in the graphs are different:%n%s%n%s",
                    prettyG,
                    prettyH),
            gNodes,
            hNodes);

    // assert equal nodes' neighbours on each level
    NodesIterator nodesOnLevel = g.getNodes();
    var gv = g.getView();
    var hv = h.getView();
    while (nodesOnLevel.hasNext()) {
      int node = nodesOnLevel.nextInt();
      gv.seek(node);
      hv.seek(node);
      assertEquals(
              String.format(
                      Locale.ROOT,
                      "arcs differ for node %d%n%s%n%s",
                      node,
                      prettyG,
                      prettyH),
              getNeighborNodes(gv),
              getNeighborNodes(hv));
    }
  }

  // Make sure we actually approximately find the closest k elements. Mostly this is about
  // ensuring that we have all the distance functions, comparators, priority queues and so on
  // oriented in the right directions
  @Test
  @SuppressWarnings("unchecked")
  public void testAknnDiverse() throws IOException {
    int nDoc = 100;
    similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
    RandomAccessVectorValues<T> vectors = circularVectorValues(nDoc);
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 10, 100, 1.0f, 1.4f);
    var graph = buildInOrder(builder, vectors);
    // run some searches
    NeighborQueue nn = GraphSearcher.search(
            getTargetVector(),
            10,
            vectors.copy(),
            getVectorEncoding(),
            similarityFunction,
            graph,
            null,
            Integer.MAX_VALUE);
    int[] nodes = nn.nodesCopy();
    assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
    int sum = 0;
    for (int node : nodes) {
      sum += node;
    }
    // We expect to get approximately 100% recall;
    // the lowest docIds are closest to zero; sum(0,9) = 45
    assertTrue("sum(result docs)=" + sum + " for " + GraphIndex.prettyPrint(builder.graph), sum < 75);

    for (int i = 0; i < nDoc; i++) {
      ConcurrentNeighborSet neighbors = graph.getNeighbors(i);
      Iterator<Integer> it = neighbors.nodeIterator();
      while (it.hasNext()) {
        // all neighbors should be valid node ids.
        assertTrue(it.next() < nDoc);
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSearchWithAcceptOrds() throws IOException {
    int nDoc = 100;
    RandomAccessVectorValues<T> vectors = circularVectorValues(nDoc);
    similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 16, 100, 1.0f, 1.4f);
    OnHeapGraphIndex graph = buildInOrder(builder, vectors);
    // the first 10 docs must not be deleted to ensure the expected recall
    Bits acceptOrds = createRandomAcceptOrds(10, nDoc);
    NeighborQueue nn =
            GraphSearcher.search(
                    getTargetVector(),
                    10,
                    vectors.copy(),
                    getVectorEncoding(),
                    similarityFunction,
                    graph,
                    acceptOrds,
                    Integer.MAX_VALUE);
    int[] nodes = nn.nodesCopy();
    assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
    int sum = 0;
    for (int node : nodes) {
      assertTrue("the results include a deleted document: " + node, acceptOrds.get(node));
      sum += node;
    }
    // We expect to get approximately 100% recall;
    // the lowest docIds are closest to zero; sum(0,9) = 45
    assertTrue("sum(result docs)=" + sum + " for " + GraphIndex.prettyPrint(builder.graph), sum < 75);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSearchWithSelectiveAcceptOrds() throws IOException {
    int nDoc = 100;
    RandomAccessVectorValues<T> vectors = circularVectorValues(nDoc);
    similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 16, 100, 1.0f, 1.4f);
    OnHeapGraphIndex graph = buildInOrder(builder, vectors);
    // Only mark a few vectors as accepted
    var acceptOrds = new FixedBitSet(nDoc);
    for (int i = 0; i < nDoc; i += getRandom().nextInt(15, 20)) {
      acceptOrds.set(i);
    }

    // Check the search finds all accepted vectors
    int numAccepted = acceptOrds.cardinality();
    NeighborQueue nn =
            GraphSearcher.search(
                    getTargetVector(),
                    numAccepted,
                    vectors.copy(),
                    getVectorEncoding(),
                    similarityFunction,
                    graph,
                    acceptOrds,
                    Integer.MAX_VALUE);

    int[] nodes = nn.nodesCopy();
    for (int node : nodes) {
      assertTrue("the results include a deleted document: %d for %s".formatted(
              node, GraphIndex.prettyPrint(builder.graph)), acceptOrds.get(node));
    }
    for (int i = 0; i < acceptOrds.length(); i++) {
      if (acceptOrds.get(i)) {
        int finalI = i;
        assertTrue("the results do not include an accepted document: %d for %s".formatted(
                i, GraphIndex.prettyPrint(builder.graph)), Arrays.stream(nodes).anyMatch(j -> j == finalI));
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVisitedLimit() throws IOException {
    int nDoc = 500;
    similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
    RandomAccessVectorValues<T> vectors = circularVectorValues(nDoc);
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 16, 100, 1.0f, 1.4f);
    OnHeapGraphIndex graph = builder.build();

    int topK = 50;
    int visitedLimit = topK + getRandom().nextInt(5);
    NeighborQueue nn =
        switch (getVectorEncoding()) {
          case FLOAT32 -> GraphSearcher.search(
              (float[]) getTargetVector(),
              topK,
              (RandomAccessVectorValues<float[]>) vectors.copy(),
              getVectorEncoding(),
              similarityFunction,
              graph,
              createRandomAcceptOrds(0, nDoc),
              visitedLimit);
          case BYTE -> GraphSearcher.search(
              (byte[]) getTargetVector(),
              topK,
              (RandomAccessVectorValues<byte[]>) vectors.copy(),
              getVectorEncoding(),
              similarityFunction,
              graph,
              createRandomAcceptOrds(0, nDoc),
              visitedLimit);
        };

    assertTrue(nn.incomplete());
    // The visited count shouldn't exceed the limit
    assertTrue(nn.visitedCount() <= visitedLimit);
  }

  @Test
  public void testGraphIndexBuilderInvalid() {
    assertThrows(
        NullPointerException.class, () -> new GraphIndexBuilder<>(null, null, null, 0, 0, 1.0f, 1.0f));
    // M must be > 0
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          RandomAccessVectorValues<T> vectors = vectorValues(1, 1);
          VectorEncoding vectorEncoding = getVectorEncoding();
          new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 0, 10, 1.0f, 1.0f);
        });
    // beamWidth must be > 0
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          RandomAccessVectorValues<T> vectors = vectorValues(1, 1);
          VectorEncoding vectorEncoding = getVectorEncoding();
          new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 10, 0, 1.0f, 1.0f);
        });
  }

  // FIXME
  @Test
  public void testRamUsageEstimate() throws IOException {
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDiversity() throws IOException {
    similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
    // Some carefully checked test cases with simple 2d vectors on the unit circle:
    float[][] values = {
      unitVector2d(0.5),
      unitVector2d(0.75),
      unitVector2d(0.2),
      unitVector2d(0.9),
      unitVector2d(0.8),
      unitVector2d(0.77),
      unitVector2d(0.6)
    };
    AbstractMockVectorValues<T> vectors = vectorValues(values);
    // First add nodes until everybody gets a full neighbor list
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 2, 10, 1.0f, 1.0f);
    // node 0 is added by the builder constructor
    builder.addGraphNode(0, vectors);
    builder.addGraphNode(1, vectors);
    builder.addGraphNode(2, vectors);
    // now every node has tried to attach every other node as a neighbor, but
    // some were excluded based on diversity check.
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    assertLevel0Neighbors(builder.graph, 1, 0);
    assertLevel0Neighbors(builder.graph, 2, 0);

    builder.addGraphNode(3, vectors);
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    // we added 3 here
    assertLevel0Neighbors(builder.graph, 1, 0, 3);
    assertLevel0Neighbors(builder.graph, 2, 0);
    assertLevel0Neighbors(builder.graph, 3, 1);

    // supplant an existing neighbor
    builder.addGraphNode(4, vectors);
    // 4 is the same distance from 0 that 2 is; we leave the existing node in place
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    assertLevel0Neighbors(builder.graph, 1, 0, 3, 4);
    assertLevel0Neighbors(builder.graph, 2, 0);
    // 1 survives the diversity check
    assertLevel0Neighbors(builder.graph, 3, 1, 4);
    assertLevel0Neighbors(builder.graph, 4, 1, 3);

    builder.addGraphNode(5, vectors);
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    assertLevel0Neighbors(builder.graph, 1, 0, 3, 4, 5);
    assertLevel0Neighbors(builder.graph, 2, 0);
    // even though 5 is closer, 3 is not a neighbor of 5, so no update to *its* neighbors occurs
    assertLevel0Neighbors(builder.graph, 3, 1, 4);
    assertLevel0Neighbors(builder.graph, 4, 1, 3, 5);
    assertLevel0Neighbors(builder.graph, 5, 1, 4);
  }

  @Test
  public void testDiversityFallback() throws IOException {
    similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
    // Some test cases can't be exercised in two dimensions;
    // in particular if a new neighbor displaces an existing neighbor
    // by being closer to the target, yet none of the existing neighbors is closer to the new vector
    // than to the target -- ie they all remain diverse, so we simply drop the farthest one.
    float[][] values = {
      {0, 0, 0},
      {0, 10, 0},
      {0, 0, 20},
      {10, 0, 0},
      {0, 4, 0}
    };
    AbstractMockVectorValues<T> vectors = vectorValues(values);
    // First add nodes until everybody gets a full neighbor list
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 1, 10, 1.0f, 1.0f);
    builder.addGraphNode(0, vectors);
    builder.addGraphNode(1, vectors);
    builder.addGraphNode(2, vectors);
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    // 2 is closer to 0 than 1, so it is excluded as non-diverse
    assertLevel0Neighbors(builder.graph, 1, 0);
    // 1 is closer to 0 than 2, so it is excluded as non-diverse
    assertLevel0Neighbors(builder.graph, 2, 0);

    builder.addGraphNode(3, vectors);
    // this is one case we are testing; 2 has been displaced by 3
    assertLevel0Neighbors(builder.graph, 0, 1, 3);
    assertLevel0Neighbors(builder.graph, 1, 0);
    assertLevel0Neighbors(builder.graph, 2, 0);
    assertLevel0Neighbors(builder.graph, 3, 0);
  }

  @Test
  public void testDiversity3d() throws IOException {
    similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
    // test the case when a neighbor *becomes* non-diverse when a newer better neighbor arrives
    float[][] values = {
      {0, 0, 0},
      {0, 10, 0},
      {0, 0, 20},
      {0, 9, 0}
    };
    AbstractMockVectorValues<T> vectors = vectorValues(values);
    // First add nodes until everybody gets a full neighbor list
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 1, 10, 1.0f, 1.0f);
    builder.addGraphNode(0, vectors);
    builder.addGraphNode(1, vectors);
    builder.addGraphNode(2, vectors);
    assertLevel0Neighbors(builder.graph, 0, 1, 2);
    // 2 is closer to 0 than 1, so it is excluded as non-diverse
    assertLevel0Neighbors(builder.graph, 1, 0);
    // 1 is closer to 0 than 2, so it is excluded as non-diverse
    assertLevel0Neighbors(builder.graph, 2, 0);

    builder.addGraphNode(3, vectors);
    // this is one case we are testing; 1 has been displaced by 3
    assertLevel0Neighbors(builder.graph, 0, 2, 3);
    assertLevel0Neighbors(builder.graph, 1, 0, 3);
    assertLevel0Neighbors(builder.graph, 2, 0);
    assertLevel0Neighbors(builder.graph, 3, 0, 1);
  }

  private void assertLevel0Neighbors(OnHeapGraphIndex graph, int node, int... expected) {
    Arrays.sort(expected);
    ConcurrentNeighborSet nn = graph.getNeighbors(node);
    Iterator<Integer> it = nn.nodeIterator();
    int[] actual = new int[nn.size()];
    for (int i = 0; i < actual.length; i++) {
      actual[i] = it.next();
    }
    Arrays.sort(actual);
    assertArrayEquals(
        "expected: " + Arrays.toString(expected) + " actual: " + Arrays.toString(actual),
        expected,
        actual);
  }

  @Test
  @SuppressWarnings("unchecked")
  // build a random graph, then check that it has at least 90% recall
  public void testRandom() throws IOException {
    similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
    int size = between(100, 150);
    int dim = 2;
    AbstractMockVectorValues<T> vectors = vectorValues(size, dim);
    int topK = 5;
    VectorEncoding vectorEncoding = getVectorEncoding();
    GraphIndexBuilder<T> builder =
        new GraphIndexBuilder<>(vectors, vectorEncoding, similarityFunction, 10, 30, 1.0f, 1.4f);
    OnHeapGraphIndex graph = builder.build();
    for (int j = 0; j < vectors.size(); j++) {
      System.out.printf("{%d: (%.2f, %.2f)}%n", j, ((float[]) vectors.vectorValue(j))[0], ((float[]) vectors.vectorValue(j))[1]);
    }
    System.out.println(GraphIndex.prettyPrint(graph));
    Bits acceptOrds = getRandom().nextBoolean() ? null : createRandomAcceptOrds(0, size);

    int efSearch = 100;
    assert vectors.size() < efSearch : vectors.size();
    int totalMatches = 0;
    for (int i = 0; i < 100; i++) {
      NeighborQueue actual;
      T query = randomVector(dim);
      actual =
              GraphSearcher.search(
                      query,
                      efSearch,
                      vectors,
                      getVectorEncoding(),
                      similarityFunction,
                      graph,
                      acceptOrds,
                      Integer.MAX_VALUE);

      System.out.println(Arrays.toString(actual.nodesCopy()));
      while (actual.size() > topK) {
        actual.pop();
      }
      NeighborQueue expected = new NeighborQueue(topK, false);
      for (int j = 0; j < size; j++) {
        if (vectors.vectorValue(j) != null && (acceptOrds == null || acceptOrds.get(j))) {
          if (getVectorEncoding() == VectorEncoding.BYTE) {
            assert query instanceof byte[];
            expected.add(
                j, similarityFunction.compare((byte[]) query, (byte[]) vectors.vectorValue(j)));
          } else {
            assert query instanceof float[];
            expected.add(
                j, similarityFunction.compare((float[]) query, (float[]) vectors.vectorValue(j)));
          }
          if (expected.size() > topK) {
            expected.pop();
          }
        }
      }
      int[] a = actual.nodesCopy();
      int[] b = expected.nodesCopy();
      totalMatches = computeOverlap(a, b);
      double overlap = totalMatches / (double) (topK);
      System.out.println(Arrays.toString(a));
      System.out.println(Arrays.toString(b));
      assertArrayEquals("After visiting %d nodes out of %d, overlap=%.2f".formatted(actual.visitedCount(), vectors.size(), overlap), a, b);
    }
  }

  protected OnHeapGraphIndex buildInOrder(GraphIndexBuilder<T> builder, RandomAccessVectorValues<T> vectors) {
    for (var i = 0; i < vectors.size(); i++) {
      builder.addGraphNode(i, vectors);
    }
    builder.complete();
    return builder.getGraph();
  }

  private int computeOverlap(int[] a, int[] b) {
    Arrays.sort(a);
    Arrays.sort(b);
    int overlap = 0;
    for (int i = 0, j = 0; i < a.length && j < b.length; ) {
      if (a[i] == b[j]) {
        ++overlap;
        ++i;
        ++j;
      } else if (a[i] > b[j]) {
        ++j;
      } else {
        ++i;
      }
    }
    return overlap;
  }

  /** Returns vectors evenly distributed around the upper unit semicircle. */
  @Test
  public void testConcurrentNeighbors() throws IOException {
    RandomAccessVectorValues<T> vectors = circularVectorValues(3);
    GraphIndexBuilder<T> builder =
            new GraphIndexBuilder<>(vectors, getVectorEncoding(), similarityFunction, 1, 30, 1.0f, 1.0f) {
              @Override
              protected float scoreBetween(T v1, T v2) {
                try {
                  Thread.sleep(10);
                } catch (InterruptedException e) {
                  throw new ThreadInterruptedException(e);
                }
                return super.scoreBetween(v1, v2);
              }
            };
    OnHeapGraphIndex graph = builder.build();
    for (int i = 0; i < vectors.size(); i++) {
      assertTrue(graph.getNeighbors(i).size() <= 2); // Level 0 gets 2x neighbors
    }
  }
  static class CircularFloatVectorValues
      implements RandomAccessVectorValues<float[]> {

    private final int size;

    int doc = -1;

    CircularFloatVectorValues(int size) {
      this.size = size;
    }

    @Override
    public CircularFloatVectorValues copy() {
      return new CircularFloatVectorValues(size);
    }

    @Override
    public int dimension() {
      return 2;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public float[] vectorValue(int ord) {
      return unitVector2d(ord / (double) size);
    }
  }

  /** Returns vectors evenly distributed around the upper unit semicircle. */
  static class CircularByteVectorValues
      implements RandomAccessVectorValues<byte[]> {
    private final int size;

    int doc = -1;

    CircularByteVectorValues(int size) {
      this.size = size;
    }

    @Override
    public CircularByteVectorValues copy() {
      return new CircularByteVectorValues(size);
    }

    @Override
    public int dimension() {
      return 2;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public byte[] vectorValue(int ord) {
      float[] value = unitVector2d(ord / (double) size);
      byte[] bValue = new byte[value.length];
      for (int i = 0; i < value.length; i++) {
        bValue[i] = (byte) (value[i] * 127);
      }
      return bValue;
    }
  }

  private static float[] unitVector2d(double piRadians) {
    return unitVector2d(piRadians, new float[2]);
  }

  private static float[] unitVector2d(double piRadians, float[] value) {
    return new float[] {
      (float) Math.cos(Math.PI * piRadians), (float) Math.sin(Math.PI * piRadians)
    };
  }

  private Set<Integer> getNeighborNodes(GraphIndex.View g) throws IOException {
    Set<Integer> neighbors = new HashSet<>();
    for (int n = g.nextNeighbor(); n != NO_MORE_DOCS; n = g.nextNeighbor()) {
      neighbors.add(n);
    }
    return neighbors;
  }

  void assertVectorsEqual(AbstractMockVectorValues<T> u, AbstractMockVectorValues<T> v)
      throws IOException {
    int uDoc, vDoc;
    while (true) {
      uDoc = u.nextDoc();
      vDoc = v.nextDoc();
      assertEquals(uDoc, vDoc);
      if (uDoc == NO_MORE_DOCS) {
        break;
      }
      switch (getVectorEncoding()) {
        case BYTE -> assertArrayEquals(
            "vectors do not match for doc=" + uDoc,
            (byte[]) u.vectorValue(),
            (byte[]) v.vectorValue());
        case FLOAT32 -> assertArrayEquals(
            "vectors do not match for doc=" + uDoc,
            (float[]) u.vectorValue(),
            (float[]) v.vectorValue(),
            1e-4f);
        default -> throw new IllegalArgumentException(
            "unknown vector encoding: " + getVectorEncoding());
      }
    }
  }

  static float[][] createRandomFloatVectors(int size, int dimension, Random random) {
    float[][] vectors = new float[size][];
    for (int offset = 0; offset < size; offset += random.nextInt(3) + 1) {
      vectors[offset] = randomVector(random, dimension);
    }
    return vectors;
  }

  static byte[][] createRandomByteVectors(int size, int dimension, Random random) {
    byte[][] vectors = new byte[size][];
    for (int offset = 0; offset < size; offset += random.nextInt(3) + 1) {
      vectors[offset] = randomVector8(random, dimension);
    }
    return vectors;
  }

  /**
   * Generate a random bitset where before startIndex all bits are set, and after startIndex each
   * entry has a 2/3 probability of being set.
   */
  private static Bits createRandomAcceptOrds(int startIndex, int length) {
    FixedBitSet bits = new FixedBitSet(length);
    // all bits are set before startIndex
    for (int i = 0; i < startIndex; i++) {
      bits.set(i);
    }
    // after startIndex, bits are set with 2/3 probability
    for (int i = startIndex; i < bits.length(); i++) {
      if (getRandom().nextFloat() < 0.667f) {
        bits.set(i);
      }
    }
    return bits;
  }

  static float[] randomVector(Random random, int dim) {
    float[] vec = new float[dim];
    for (int i = 0; i < dim; i++) {
      vec[i] = random.nextFloat();
      if (random.nextBoolean()) {
        vec[i] = -vec[i];
      }
    }
    VectorUtil.l2normalize(vec);
    return vec;
  }

  static byte[] randomVector8(Random random, int dim) {
    float[] fvec = randomVector(random, dim);
    byte[] bvec = new byte[dim];
    for (int i = 0; i < dim; i++) {
      bvec[i] = (byte) (fvec[i] * 127);
    }
    return bvec;
  }
}
