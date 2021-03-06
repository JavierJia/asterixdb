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
package org.apache.asterix.api.http.servlet;

public class ServletConstants {
    public static final String HYRACKS_CONNECTION_ATTR = "org.apache.asterix.HYRACKS_CONNECTION";
    public static final String HYRACKS_DATASET_ATTR = "org.apache.asterix.HYRACKS_DATASET";
    public static final String ASTERIX_BUILD_PROP_ATTR = "org.apache.asterix.PROPS";
    public static final String EXECUTOR_SERVICE = "org.apache.asterix.EXECUTOR_SERVICE";

    private ServletConstants() {
    }
}
