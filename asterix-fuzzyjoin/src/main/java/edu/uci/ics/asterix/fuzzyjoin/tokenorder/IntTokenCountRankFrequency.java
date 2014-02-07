/**
 * Copyright 2010-2011 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS"; BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under
 * the License.
 * 
 * Author: Rares Vernica <rares (at) ics.uci.edu>
 */

package edu.uci.ics.asterix.fuzzyjoin.tokenorder;

import java.util.HashMap;

import edu.uci.ics.asterix.fuzzyjoin.IntPair;

public class IntTokenCountRankFrequency implements IntTokenCountRank {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    private final HashMap<IntPair, Integer> ranksMap = new HashMap<IntPair, Integer>();
    private final IntPair tmpPair = new IntPair();
    private int crtRank = 0;

    @Override
    public int add(int token, int count) {
        int prevRank = crtRank;
        ranksMap.put(new IntPair(token, count), prevRank);
        crtRank++;
        return prevRank;
    }

    @Override
    public int getRank(int token, int count) {
        tmpPair.set(token, count);
        Integer rank = ranksMap.get(tmpPair);
        if (rank == null) {
            return -1;
        }
        return rank;
    }

    @Override
    public String toString() {
        return "[" + crtRank + ",\n " + ranksMap + "\n]";
    }
}