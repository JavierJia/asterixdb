/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.metadata.api;

import java.util.Set;

import org.apache.asterix.common.api.IClusterEventsSubscriber;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.event.schema.cluster.Node;

public interface IClusterManager {

    /**
     * @param node
     * @throws AsterixException
     */
    public void addNode(ICcApplicationContext appCtx, Node node) throws AsterixException;

    /**
     * @param node
     * @throws AsterixException
     */
    public void removeNode(Node node) throws AsterixException;

    /**
     * @param subscriber
     */
    public void registerSubscriber(IClusterEventsSubscriber subscriber);

    /**
     * @param sunscriber
     * @return
     */
    public boolean deregisterSubscriber(IClusterEventsSubscriber sunscriber);

    /**
     * @return
     */
    public Set<IClusterEventsSubscriber> getRegisteredClusterEventSubscribers();

    void notifyStartupCompleted() throws Exception;
}
