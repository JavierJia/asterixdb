/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.algebricks.runtime.operators.base;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.runtime.base.IPushRuntime;
import edu.uci.ics.hyracks.algebricks.runtime.base.IPushRuntimeFactory;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;

public abstract class AbstractOneInputOneOutputRuntimeFactory implements IPushRuntimeFactory {

    private static final long serialVersionUID = 1L;

    protected int[] projectionList;

    public AbstractOneInputOneOutputRuntimeFactory(int[] projectionList) {
        this.projectionList = projectionList;
    }

    @Override
    public IPushRuntime createPushRuntime(IHyracksTaskContext ctx) throws AlgebricksException, HyracksDataException {
        return createOneOutputPushRuntime(ctx);
    }

    public abstract AbstractOneInputOneOutputPushRuntime createOneOutputPushRuntime(IHyracksTaskContext ctx)
            throws AlgebricksException, HyracksDataException;

}