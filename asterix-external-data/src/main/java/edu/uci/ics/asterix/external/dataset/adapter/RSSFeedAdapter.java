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
package edu.uci.ics.asterix.external.dataset.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.metadata.feeds.IFeedAdapter;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;

/**
 * RSSFeedAdapter provides the functionality of fetching an RSS based feed.
 */
public class RSSFeedAdapter extends PullBasedAdapter implements IFeedAdapter {

    private static final long serialVersionUID = 1L;

    private List<String> feedURLs = new ArrayList<String>();
    private boolean isStopRequested = false;
    private boolean isAlterRequested = false;
    private Map<String, String> alteredParams = new HashMap<String, String>();
    private String id_prefix = "";

    private IPullBasedFeedClient rssFeedClient;
    private ARecordType recordType;

    public boolean isStopRequested() {
        return isStopRequested;
    }

    public RSSFeedAdapter(Map<String, String> configuration, ARecordType recordType, IHyracksTaskContext ctx)
            throws AsterixException {
        super(configuration, ctx);
        id_prefix = ctx.getJobletContext().getApplicationContext().getNodeId();
        this.recordType = recordType;
    }

    public void setStopRequested(boolean isStopRequested) {
        this.isStopRequested = isStopRequested;
    }

    @Override
    public void alter(Map<String, String> properties) {
        isAlterRequested = true;
        this.alteredParams = properties;
        reconfigure(properties);
    }

    @Override
    public void stop() {
        isStopRequested = true;
    }

    private void initializeFeedURLs(String rssURLProperty) {
        feedURLs.clear();
        String[] feedURLProperty = rssURLProperty.split(",");
        for (String feedURL : feedURLProperty) {
            feedURLs.add(feedURL);
        }
    }

    protected void reconfigure(Map<String, String> arguments) {
        String rssURLProperty = configuration.get("KEY_RSS_URL");
        if (rssURLProperty != null) {
            initializeFeedURLs(rssURLProperty);
        }
    }

    public boolean isAlterRequested() {
        return isAlterRequested;
    }

    public Map<String, String> getAlteredParams() {
        return alteredParams;
    }

    @Override
    public IPullBasedFeedClient getFeedClient(int partition) throws Exception {
        if (rssFeedClient == null) {
            rssFeedClient = new RSSFeedClient(this, feedURLs.get(partition), id_prefix);
        }
        return rssFeedClient;
    }

    public ARecordType getRecordType() {
        return recordType;
    }

}