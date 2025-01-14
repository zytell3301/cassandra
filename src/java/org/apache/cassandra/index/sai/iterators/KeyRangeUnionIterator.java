/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.index.sai.iterators;

import java.util.ArrayList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import org.apache.cassandra.index.sai.utils.PrimaryKey;
import org.apache.cassandra.io.util.FileUtils;

/**
 * Range Union Iterator is used to return sorted stream of elements from multiple RangeIterator instances.
 */
public class KeyRangeUnionIterator extends KeyRangeIterator
{
    private final List<KeyRangeIterator> ranges;
    private final List<KeyRangeIterator> candidates;

    private KeyRangeUnionIterator(Builder.Statistics statistics, List<KeyRangeIterator> ranges)
    {
        super(statistics);
        this.ranges = ranges;
        this.candidates = new ArrayList<>(ranges.size());
    }

    @Override
    public PrimaryKey computeNext()
    {
        candidates.clear();
        PrimaryKey candidateKey = null;
        for (KeyRangeIterator range : ranges)
        {
            if (range.hasNext())
            {
                if (candidateKey == null)
                {
                    candidateKey = range.peek();
                    candidates.add(range);
                }
                else
                {
                    PrimaryKey peeked = range.peek();
    
                    int cmp = candidateKey.compareTo(peeked);

                    if (cmp == 0)
                    {
                        // Replace any existing candidate key if this one is STATIC:  
                        if (peeked.kind() == PrimaryKey.Kind.STATIC)
                            candidateKey = peeked;

                        candidates.add(range);
                    }
                    else if (cmp > 0)
                    {
                        candidates.clear();
                        candidateKey = peeked;
                        candidates.add(range);
                    }
                }
            }
        }
        if (candidates.isEmpty())
            return endOfData();

        for (KeyRangeIterator candidate : candidates)
        {
            do
            {
                // Consume the remaining values equal to the candidate key:
                candidate.next();
            }
            while (candidate.hasNext() && candidate.peek().compareTo(candidateKey) == 0);
        }

        return candidateKey;
    }

    @Override
    protected void performSkipTo(PrimaryKey nextKey)
    {
        for (KeyRangeIterator range : ranges)
        {
            if (range.hasNext())
                range.skipTo(nextKey);
        }
    }

    @Override
    public void close()
    {
        // Due to lazy key fetching, we cannot close iterator immediately
        FileUtils.closeQuietly(ranges);
    }

    public static Builder builder(int size)
    {
        return new Builder(size);
    }

    public static KeyRangeIterator build(List<KeyRangeIterator> keys)
    {
        return new Builder(keys.size()).add(keys).build();
    }

    @VisibleForTesting
    public static class Builder extends KeyRangeIterator.Builder
    {
        protected final List<KeyRangeIterator> rangeIterators;

        Builder(int size)
        {
            super(new UnionStatistics());
            this.rangeIterators = new ArrayList<>(size);
        }

        @Override
        public KeyRangeIterator.Builder add(KeyRangeIterator range)
        {
            if (range == null)
                return this;

            if (range.getCount() > 0)
            {
                rangeIterators.add(range);
                statistics.update(range);
            }
            else
            {
                FileUtils.closeQuietly(range);
            }

            return this;
        }

        @Override
        public int rangeCount()
        {
            return rangeIterators.size();
        }

        @Override
        public void cleanup()
        {
            FileUtils.closeQuietly(rangeIterators);
        }

        @Override
        protected KeyRangeIterator buildIterator()
        {
            if (rangeCount() == 1)
                return rangeIterators.get(0);

            return new KeyRangeUnionIterator(statistics, rangeIterators);
        }
    }

    private static class UnionStatistics extends KeyRangeIterator.Builder.Statistics
    {
        @Override
        public void update(KeyRangeIterator range)
        {
            min = nullSafeMin(min, range.getMinimum());
            max = nullSafeMax(max, range.getMaximum());
            count += range.getCount();
        }
    }
}
