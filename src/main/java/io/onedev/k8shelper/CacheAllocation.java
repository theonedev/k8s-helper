package io.onedev.k8shelper;

import java.io.File;
import java.util.Date;
import java.util.List;

public class CacheAllocation {

    private final CacheConfigFacade config;

    private final List<File> dirs;

    private final Date setupDate;

    public CacheAllocation(CacheConfigFacade config, List<File> dirs, Date setupDate) {
        this.config = config;
        this.dirs = dirs;
        this.setupDate = setupDate;
    }

    public CacheConfigFacade getConfig() {
        return config;
    }

    public List<File> getDirs() {
        return dirs;
    }

    public Date getSetupDate() {
        return setupDate;
    }

}
