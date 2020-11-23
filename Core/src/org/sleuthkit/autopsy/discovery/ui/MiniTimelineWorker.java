/*
 * Autopsy
 *
 * Copyright 2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.discovery.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.swing.SwingWorker;
import org.apache.commons.lang3.StringUtils;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.discovery.search.DiscoveryEventUtils;
import org.sleuthkit.autopsy.discovery.search.DomainSearch;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.Type;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * SwingWorker to retrieve a list of artifacts for a specified type and domain.
 */
class MiniTimelineWorker extends SwingWorker<List<DateArtifactWrapper>, Void> {

    private final static Logger logger = Logger.getLogger(MiniTimelineWorker.class.getName());
    private final String domain;

    /**
     * Construct a new ArtifactsWorker.
     *
     * @param artifactType The type of artifact being retrieved.
     * @param domain       The domain the artifacts should have as an attribute.
     */
    MiniTimelineWorker(String domain) {
        this.domain = domain;
    }

    @Override
    protected List<DateArtifactWrapper> doInBackground() throws Exception {
        List<DateArtifactWrapper> dateArtifactList = new ArrayList<>();
        if (!StringUtils.isBlank(domain)) {
            DomainSearch domainSearch = new DomainSearch();
            Map<String, List<BlackboardArtifact>> dateMap = new HashMap<>();
            for (BlackboardArtifact artifact : domainSearch.getAllArtifactsForDomain(Case.getCurrentCase().getSleuthkitCase(), domain)) {
                String date = getDate(artifact);
                if (!StringUtils.isBlank(date)) {
                    List<BlackboardArtifact> artifactList = dateMap.get(date);
                    if (artifactList == null) {
                        artifactList = new ArrayList<>();
                    }
                    artifactList.add(artifact);
                    dateMap.put(date, artifactList);
                }
            }
            for (String date : dateMap.keySet()) {
                dateArtifactList.add(new DateArtifactWrapper(date, dateMap.get(date)));
            }
        }
        return dateArtifactList;
    }

    private String getDate(BlackboardArtifact artifact) throws TskCoreException {
        return artifact.getAttribute(new Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME_CREATED)).getDisplayString();
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            try {
                DiscoveryEventUtils.getDiscoveryEventBus().post(new DiscoveryEventUtils.MiniTimelineResultEvent(get()));
                return;
            } catch (InterruptedException | ExecutionException ex) {
                logger.log(Level.SEVERE, "Exception while trying to get list of artifacts for Domain details for mini timeline view for domain: " + domain, ex);
            } catch (CancellationException ignored) {
                //Worker was cancelled after previously finishing its background work, exception ignored to cut down on non-helpful logging
            }
        }
        DiscoveryEventUtils.getDiscoveryEventBus().post(new DiscoveryEventUtils.MiniTimelineResultEvent(new ArrayList<>()));
    }
}
