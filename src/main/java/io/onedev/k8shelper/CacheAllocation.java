package io.onedev.k8shelper;

import java.io.File;
import java.util.Date;
import java.util.List;

public class CacheAllocation {

    private final CacheConfigFacade definition;

    private final List<File> dirs;

    private final Date setupDate;

    public CacheAllocation(CacheConfigFacade definition, List<File> dirs, Date setupDate) {
        this.definition = definition;
        this.dirs = dirs;
        this.setupDate = setupDate;
    }

    public CacheConfigFacade getDefinition() {
        return definition;
    }

    public List<File> getDirs() {
        return dirs;
    }

    public Date getSetupDate() {
        return setupDate;
    }

}
