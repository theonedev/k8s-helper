package io.onedev.k8shelper;

import java.io.File;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class CacheAllocation {

    private final CacheConfigFacade config;

    private final Map<String, File> pathMap;

    private final Set<String> exactMatchPaths;

    private final Date setupDate = new Date();

    public CacheAllocation(CacheConfigFacade config, 
            Map<String, File> pathMap, Set<String> exactMatchPaths) {
        this.config = config;
        this.pathMap = pathMap;
        this.exactMatchPaths = exactMatchPaths;
    }

    public CacheConfigFacade getConfig() {
        return config;
    }

    public Map<String, File> getPathMap() {
        return pathMap;
    }

    public Set<String> getExactMatchPaths() {
        return exactMatchPaths;
    }

    public Date getSetupDate() {
        return setupDate;
    }

}
