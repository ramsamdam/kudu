// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.kudu.client;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.util.ByteVec;
import org.apache.kudu.util.Pair;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.concurrent.NotThreadSafe;

@InterfaceAudience.Private
@NotThreadSafe
public class PartitionPruner {

  private final Deque<Pair<byte[], byte[]>> rangePartitions;

  /**
   * Constructs a new partition pruner.
   * @param rangePartitions the valid partition key ranges, in reverse sorted order
   */
  private PartitionPruner(Deque<Pair<byte[], byte[]>> rangePartitions) {
    this.rangePartitions = rangePartitions;
  }

  /**
   * @return a partition pruner that will prune all partitions
   */
  private static PartitionPruner empty() {
    return new PartitionPruner(new ArrayDeque<Pair<byte[], byte[]>>());
  }

  /**
   * Creates a new partition pruner for the provided scan.
   * @param scanner the scan to prune
   * @return a partition pruner
   */
  public static PartitionPruner create(AbstractKuduScannerBuilder<?, ?> scanner) {
    Schema schema = scanner.table.getSchema();
    PartitionSchema partitionSchema = scanner.table.getPartitionSchema();
    PartitionSchema.RangeSchema rangeSchema = partitionSchema.getRangeSchema();
    Map<String, KuduPredicate> predicates = scanner.predicates;

    // Check if the scan can be short circuited entirely by checking the primary
    // key bounds and predicates. This also allows us to assume some invariants of the
    // scan, such as no None predicates and that the lower bound PK < upper
    // bound PK.
    if (scanner.upperBoundPrimaryKey.length > 0 &&
        Bytes.memcmp(scanner.lowerBoundPrimaryKey, scanner.upperBoundPrimaryKey) >= 0) {
      return PartitionPruner.empty();
    }
    for (KuduPredicate predicate : predicates.values()) {
      if (predicate.getType() == KuduPredicate.PredicateType.NONE) {
        return PartitionPruner.empty();
      }
    }

    // Build a set of partition key ranges which cover the tablets necessary for
    // the scan.
    //
    // Example predicate sets and resulting partition key ranges, based on the
    // following tablet schema:
    //
    // CREATE TABLE t (a INT32, b INT32, c INT32) PRIMARY KEY (a, b, c)
    // DISTRIBUTE BY RANGE (c)
    //               HASH (a) INTO 2 BUCKETS
    //               HASH (b) INTO 3 BUCKETS;
    //
    // Assume that hash(0) = 0 and hash(2) = 2.
    //
    // | Predicates | Partition Key Ranges                                   |
    // +------------+--------------------------------------------------------+
    // | a = 0      | [(bucket=0, bucket=2, c=0), (bucket=0, bucket=2, c=1)) |
    // | b = 2      |                                                        |
    // | c = 0      |                                                        |
    // +------------+--------------------------------------------------------+
    // | a = 0      | [(bucket=0, bucket=2), (bucket=0, bucket=3))           |
    // | b = 2      |                                                        |
    // +------------+--------------------------------------------------------+
    // | a = 0      | [(bucket=0, bucket=0, c=0), (bucket=0, bucket=0, c=1)) |
    // | c = 0      | [(bucket=0, bucket=1, c=0), (bucket=0, bucket=1, c=1)) |
    // |            | [(bucket=0, bucket=2, c=0), (bucket=0, bucket=2, c=1)) |
    // +------------+--------------------------------------------------------+
    // | b = 2      | [(bucket=0, bucket=2, c=0), (bucket=0, bucket=2, c=1)) |
    // | c = 0      | [(bucket=1, bucket=2, c=0), (bucket=1, bucket=2, c=1)) |
    // +------------+--------------------------------------------------------+
    // | a = 0      | [(bucket=0), (bucket=1))                               |
    // +------------+--------------------------------------------------------+
    // | b = 2      | [(bucket=0, bucket=2), (bucket=0, bucket=3))           |
    // |            | [(bucket=1, bucket=2), (bucket=1, bucket=3))           |
    // +------------+--------------------------------------------------------+
    // | c = 0      | [(bucket=0, bucket=0, c=0), (bucket=0, bucket=0, c=1)) |
    // |            | [(bucket=0, bucket=1, c=0), (bucket=0, bucket=1, c=1)) |
    // |            | [(bucket=0, bucket=2, c=0), (bucket=0, bucket=2, c=1)) |
    // |            | [(bucket=1, bucket=0, c=0), (bucket=1, bucket=0, c=1)) |
    // |            | [(bucket=1, bucket=1, c=0), (bucket=1, bucket=1, c=1)) |
    // |            | [(bucket=1, bucket=2, c=0), (bucket=1, bucket=2, c=1)) |
    // +------------+--------------------------------------------------------+
    // | None       | [(), ())                                               |
    //
    // If the partition key is considered as a sequence of the hash bucket
    // components and a range component, then a few patterns emerge from the
    // examples above:
    //
    // 1) The partition keys are truncated after the final constrained component
    //    (hash bucket components are constrained when the scan is limited to a
    //    single bucket via equality predicates on that component, while range
    //    components are constrained if they have an upper or lower bound via
    //    range or equality predicates on that component).
    //
    // 2) If the final constrained component is a hash bucket, then the
    //    corresponding bucket in the upper bound is incremented in order to make
    //    it an exclusive key.
    //
    // 3) The number of partition key ranges in the result is equal to the product
    //    of the number of buckets of each unconstrained hash component which come
    //    before a final constrained component. If there are no unconstrained hash
    //    components, then the number of partition key ranges is one.

    // Step 1: Build the range portion of the partition key. If the range partition
    // columns match the primary key columns, then we can substitute the primary
    // key bounds, if they are tighter.
    byte[] rangeLowerBound = pushPredicatesIntoLowerBoundRangeKey(schema, rangeSchema, predicates);
    byte[] rangeUpperBound = pushPredicatesIntoUpperBoundRangeKey(schema, rangeSchema, predicates);
    if (partitionSchema.isSimpleRangePartitioning()) {
      if (Bytes.memcmp(rangeLowerBound, scanner.lowerBoundPrimaryKey) < 0) {
        rangeLowerBound = scanner.lowerBoundPrimaryKey;
      }
      if (scanner.upperBoundPrimaryKey.length > 0 &&
          (rangeUpperBound.length == 0 ||
           Bytes.memcmp(rangeUpperBound, scanner.upperBoundPrimaryKey) > 0)) {
        rangeUpperBound = scanner.upperBoundPrimaryKey;
      }
    }

    // Step 2: Create the hash bucket portion of the partition key.

    // The list of hash buckets per hash component, or null if the component is
    // not constrained.
    List<Integer> hashBuckets = new ArrayList<>(partitionSchema.getHashBucketSchemas().size());
    for (PartitionSchema.HashBucketSchema hashSchema : partitionSchema.getHashBucketSchemas()) {
      hashBuckets.add(pushPredicatesIntoHashBucket(schema, hashSchema, predicates));
    }

    // The index of the final constrained component in the partition key.
    int constrainedIndex = 0;
    if (rangeLowerBound.length > 0 || rangeUpperBound.length > 0) {
      // The range component is constrained if either of the range bounds are
      // specified (non-empty).
      constrainedIndex = hashBuckets.size();
    } else {
      // Search the hash bucket constraints from right to left, looking for the
      // first constrained component.
      for (int i = hashBuckets.size(); i > 0; i--) {
        if (hashBuckets.get(i - 1) != null) {
          constrainedIndex = i;
          break;
        }
      }
    }

    // Build up a set of partition key ranges out of the hash components.
    //
    // Each constrained hash component simply appends its bucket number to the
    // partition key ranges (possibly incrementing the upper bound by one bucket
    // number if this is the final constraint, see note 2 in the example above).
    //
    // Each unconstrained hash component results in creating a new partition key
    // range for each bucket of the hash component.
    List<Pair<ByteVec, ByteVec>> partitionKeyRanges = new ArrayList<>();
    partitionKeyRanges.add(new Pair<>(ByteVec.create(), ByteVec.create()));

    ByteBuffer bucketBuf = ByteBuffer.allocate(4);
    bucketBuf.order(ByteOrder.BIG_ENDIAN);
    for (int hashIdx = 0; hashIdx < constrainedIndex; hashIdx++) {
      // This is the final partition key component if this is the final constrained
      // bucket, and the range upper bound is empty. In this case we need to
      // increment the bucket on the upper bound to convert from inclusive to
      // exclusive.
      boolean isLast = hashIdx + 1 == constrainedIndex && rangeUpperBound.length == 0;

      if (hashBuckets.get(hashIdx) != null) {
        // This hash component is constrained by equality predicates to a single
        // hash bucket.
        int bucket = hashBuckets.get(hashIdx);
        int bucketUpper = isLast ? bucket + 1 : bucket;

        for (Pair<ByteVec, ByteVec> partitionKeyRange : partitionKeyRanges) {
          KeyEncoder.encodeHashBucket(bucket, partitionKeyRange.getFirst());
          KeyEncoder.encodeHashBucket(bucketUpper, partitionKeyRange.getSecond());
        }
      } else {
        PartitionSchema.HashBucketSchema hashSchema =
            partitionSchema.getHashBucketSchemas().get(hashIdx);
        // Add a partition key range for each possible hash bucket.
        List<Pair<ByteVec, ByteVec>> newPartitionKeyRanges =
            new ArrayList<>(partitionKeyRanges.size() * hashSchema.getNumBuckets());
        for (Pair<ByteVec, ByteVec> partitionKeyRange : partitionKeyRanges) {
          for (int bucket = 0; bucket < hashSchema.getNumBuckets(); bucket++) {
            int bucketUpper = isLast ? bucket + 1 : bucket;
            ByteVec lower = partitionKeyRange.getFirst().clone();
            ByteVec upper = partitionKeyRange.getFirst().clone();
            KeyEncoder.encodeHashBucket(bucket, lower);
            KeyEncoder.encodeHashBucket(bucketUpper, upper);
            newPartitionKeyRanges.add(new Pair<>(lower, upper));
          }
        }
        partitionKeyRanges = newPartitionKeyRanges;
      }
    }

    // Step 3: append the (possibly empty) range bounds to the partition key ranges.
    for (Pair<ByteVec, ByteVec> range : partitionKeyRanges) {
      range.getFirst().append(rangeLowerBound);
      range.getSecond().append(rangeUpperBound);
    }

    // Step 4: Filter ranges that fall outside the scan's upper and lower bound partition keys.
    Deque<Pair<byte[], byte[]>> partitionKeyRangeBytes = new ArrayDeque<>(partitionKeyRanges.size());
    for (Pair<ByteVec, ByteVec> range : partitionKeyRanges) {
      byte[] lower = range.getFirst().toArray();
      byte[] upper = range.getSecond().toArray();

      // Sanity check that the lower bound is less than the upper bound.
      assert upper.length == 0 || Bytes.memcmp(lower, upper) < 0;

      // Find the intersection of the ranges.
      if (scanner.lowerBoundPartitionKey.length > 0 &&
          (lower.length == 0 || Bytes.memcmp(lower, scanner.lowerBoundPartitionKey) < 0)) {
        lower = scanner.lowerBoundPartitionKey;
      }
      if (scanner.upperBoundPartitionKey.length > 0 &&
          (upper.length == 0 || Bytes.memcmp(upper, scanner.upperBoundPartitionKey) > 0)) {
        upper = scanner.upperBoundPartitionKey;
      }

      // If the intersection is valid, then add it as a range partition.
      if (upper.length == 0 || Bytes.memcmp(lower, upper) < 0) {
        partitionKeyRangeBytes.add(new Pair<>(lower, upper));
      }
    }

    return new PartitionPruner(partitionKeyRangeBytes);
  }

  /** @return {@code true} if there are more range partitions to scan. */
  public boolean hasMorePartitionKeyRanges() {
    return !rangePartitions.isEmpty();
  }

  /** @return the inclusive lower bound partition key of the next tablet to scan. */
  public byte[] nextPartitionKey() {
    return rangePartitions.getFirst().getFirst();
  }

  /** @return the next range partition key range to scan. */
  public Pair<byte[], byte[]> nextPartitionKeyRange() {
    return rangePartitions.getFirst();
  }

  /** Removes all partition key ranges through the provided exclusive upper bound. */
  public void removePartitionKeyRange(byte[] upperBound) {
    if (upperBound.length == 0) {
      rangePartitions.clear();
      return;
    }

    while (!rangePartitions.isEmpty()) {
      Pair<byte[], byte[]> range = rangePartitions.getFirst();
      if (Bytes.memcmp(upperBound, range.getFirst()) <= 0) break;
      rangePartitions.removeFirst();
      if (range.getSecond().length == 0 || Bytes.memcmp(upperBound, range.getSecond()) < 0) {
        // The upper bound falls in the middle of this range, so add it back
        // with the restricted bounds.
        rangePartitions.addFirst(new Pair<>(upperBound, range.getSecond()));
        break;
      }
    }
  }

  /**
   * @param partition to prune
   * @return {@code true} if the partition should be pruned
   */
  boolean shouldPrune(Partition partition) {
    // The C++ version uses binary search to do this with fewer key comparisons,
    // but the algorithm isn't easily translatable, so this just uses a linear
    // search.
    for (Pair<byte[], byte[]> range : rangePartitions) {

      // Continue searching the list of ranges if the partition is greater than
      // the current range.
      if (range.getSecond().length > 0 &&
          Bytes.memcmp(range.getSecond(), partition.getPartitionKeyStart()) <= 0) {
        continue;
      }

      // If the current range is greater than the partitions,
      // then the partition should be pruned.
      return partition.getPartitionKeyEnd().length > 0 &&
             Bytes.memcmp(partition.getPartitionKeyEnd(), range.getFirst()) <= 0;
    }

    // The partition is greater than all ranges.
    return true;
  }

  private static List<Integer> idsToIndexes(Schema schema, List<Integer> ids) {
    List<Integer> indexes = new ArrayList<>(ids.size());
    for (int id : ids) {
      indexes.add(schema.getColumnIndex(id));
    }
    return indexes;
  }

  private static boolean incrementKey(PartialRow row, List<Integer> keyIndexes) {
    for (int i = keyIndexes.size() - 1; i >= 0; i--) {
      if (row.incrementColumn(keyIndexes.get(i))) return true;
    }
    return false;
  }

  /**
   * Translates column predicates into a lower bound range partition key.
   * @param schema the table schema
   * @param rangeSchema the range partition schema
   * @param predicates the predicates
   * @return a lower bound range partition key
   */
  private static byte[] pushPredicatesIntoLowerBoundRangeKey(Schema schema,
                                                             PartitionSchema.RangeSchema rangeSchema,
                                                             Map<String, KuduPredicate> predicates) {
    PartialRow row = schema.newPartialRow();
    int pushedPredicates = 0;

    List<Integer> rangePartitionColumnIdxs = idsToIndexes(schema, rangeSchema.getColumns());

    // Copy predicates into the row in range partition key column order,
    // stopping after the first missing predicate.
    for (int idx : rangePartitionColumnIdxs) {
      ColumnSchema column = schema.getColumnByIndex(idx);
      KuduPredicate predicate = predicates.get(column.getName());
      if (predicate == null) break;

      if (predicate.getType() != KuduPredicate.PredicateType.EQUALITY &&
          predicate.getType() != KuduPredicate.PredicateType.RANGE) {
        throw new IllegalArgumentException(
            String.format("unexpected predicate type can not be pushed into key: %s", predicate));
      }

      byte[] value = predicate.getLower();
      if (value == null) break;
      row.setRaw(idx, value);
      pushedPredicates++;
    }

    // If no predicates were pushed, no need to do any more work.
    if (pushedPredicates == 0) return AsyncKuduClient.EMPTY_ARRAY;

    // For each remaining column in the partition key, fill it with the minimum value.
    Iterator<Integer> remainingIdxs = rangePartitionColumnIdxs.listIterator(pushedPredicates);
    while (remainingIdxs.hasNext()) {
      row.setMin(remainingIdxs.next());
    }

    return KeyEncoder.encodeRangePartitionKey(row, rangeSchema);
  }

  /**
   * Translates column predicates into an upper bound range partition key.
   * @param schema the table schema
   * @param rangeSchema the range partition schema
   * @param predicates the predicates
   * @return an upper bound range partition key
   */
  private static byte[] pushPredicatesIntoUpperBoundRangeKey(Schema schema,
                                                             PartitionSchema.RangeSchema rangeSchema,
                                                             Map<String, KuduPredicate> predicates) {
    PartialRow row = schema.newPartialRow();
    int pushedPredicates = 0;
    KuduPredicate finalPredicate = null;

    List<Integer> rangePartitionColumnIdxs = idsToIndexes(schema, rangeSchema.getColumns());

    // Step 1: copy predicates into the row in range partition key column order, stopping after
    // the first missing predicate.
    for (int idx : rangePartitionColumnIdxs) {
      ColumnSchema column = schema.getColumnByIndex(idx);
      KuduPredicate predicate = predicates.get(column.getName());
      if (predicate == null) break;

      if (predicate.getType() == KuduPredicate.PredicateType.EQUALITY) {
        byte[] value = predicate.getLower();
        row.setRaw(idx, value);
        pushedPredicates++;
        finalPredicate = predicate;
      } else if (predicate.getType() == KuduPredicate.PredicateType.RANGE) {

        if (predicate.getUpper() != null) {
          row.setRaw(idx, predicate.getUpper());
          pushedPredicates++;
          finalPredicate = predicate;
        }

        // After the first column with a range constraint we stop pushing
        // constraints into the upper bound. Instead, we push minimum values
        // to the remaining columns (below), which is the maximally tight
        // constraint.
        break;
      } else {
        throw new IllegalArgumentException(
            String.format("unexpected predicate type can not be pushed into key: %s", predicate));
      }
    }

    // If no predicates were pushed, no need to do any more work.
    if (pushedPredicates == 0) return AsyncKuduClient.EMPTY_ARRAY;

    // Step 2: If the final predicate is an equality predicate, increment the
    // key to convert it to an exclusive upper bound.
    if (finalPredicate.getType() == KuduPredicate.PredicateType.EQUALITY) {
      incrementKey(row, rangePartitionColumnIdxs.subList(0, pushedPredicates));
    }

    // Step 3: Fill the remaining columns without predicates with the min value.
    Iterator<Integer> remainingIdxs = rangePartitionColumnIdxs.listIterator(pushedPredicates);
    while (remainingIdxs.hasNext()) {
      row.setMin(remainingIdxs.next());
    }

    return KeyEncoder.encodeRangePartitionKey(row, rangeSchema);
  }

  /**
   * Determines if the provided predicates can constrain the hash component to a
   * single bucket, and if so, returns the bucket number. Otherwise returns null.
   */
  private static Integer pushPredicatesIntoHashBucket(Schema schema,
                                                      PartitionSchema.HashBucketSchema hashSchema,
                                                      Map<String, KuduPredicate> predicates) {
    List<Integer> columnIdxs = idsToIndexes(schema, hashSchema.getColumnIds());
    PartialRow row = schema.newPartialRow();
    for (int idx : columnIdxs) {
      ColumnSchema column = schema.getColumnByIndex(idx);
      KuduPredicate predicate = predicates.get(column.getName());
      if (predicate == null || predicate.getType() != KuduPredicate.PredicateType.EQUALITY) {
        return null;
      }
      row.setRaw(idx, predicate.getLower());
    }
    return KeyEncoder.getHashBucket(row, hashSchema);
  }
}
