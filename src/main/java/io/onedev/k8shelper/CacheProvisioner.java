package io.onedev.k8shelper;

import static io.onedev.k8shelper.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;

public abstract class CacheProvisioner {

    private final File baseDir;

    private final TaskLogger logger;

    private final List<CacheAllocation> allocations = new ArrayList<>();

    private int cacheIndex = 1;

    public CacheProvisioner(File baseDir, TaskLogger logger) {
        this.baseDir = baseDir;
        this.logger = logger;
    }

    public void setupCache(CacheConfigFacade cacheConfig) {
        var pathMap = new HashMap<String, File>();
        for (var path: cacheConfig.getPaths()) {
            if (path.contains(".."))
                throw new ExplicitException("Cache path does not allow to contain '..': " + path);

            File cacheDir;
            if (FilenameUtils.getPrefixLength(path) > 0) {
                cacheDir = new File(baseDir, "cache/" + (cacheIndex++));
            } else {
                cacheDir = new File(new File(baseDir, "work"), path);
            }
            FileUtils.createDir(cacheDir);
            pathMap.put(path, cacheDir);
        }

        cacheConfig.replacePlaceholders(baseDir);
        cacheConfig.computeChecksum(new File(baseDir, "work"), logger);

        var exactMatchPaths = new HashSet<String>();
        for (var entry: pathMap.entrySet()) {
            var availability = downloadCache(cacheConfig.getKey(), cacheConfig.getChecksum(),
                    entry.getKey(), entry.getValue());
            if (availability == CacheAvailability.EXACT_MATCH)
                logger.log("Exact matched " + cacheConfig.describe(entry.getKey()));
            else if (availability == CacheAvailability.PARTIAL_MATCH)
                logger.log("Partial matched " + cacheConfig.describe(entry.getKey()));

            if (availability == CacheAvailability.EXACT_MATCH)
                exactMatchPaths.add(entry.getKey());
        }
        allocations.add(new CacheAllocation(cacheConfig, pathMap, exactMatchPaths));
    }

    private void uploadCacheThenLog(CacheConfigFacade cacheConfig, String path, File cacheDir) {
        if (uploadCache(cacheConfig, path, cacheDir))
            logger.log(String.format("Uploaded %s", cacheConfig.describeUpload(path), path));
        else
            logger.warning(String.format("Not authorized to upload %s", cacheConfig.describeUpload(path)));
    }

    public void uploadCaches() {
        for (var allocation : allocations) {
            var cacheConfig = allocation.getConfig();
            var pathMap = allocation.getPathMap();
            for (var entry: pathMap.entrySet()) {
                if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_EXACT_MATCH) {
                    if (!allocation.getExactMatchPaths().contains(entry.getKey())) 
                        uploadCacheThenLog(cacheConfig, entry.getKey(), entry.getValue());
                } else {
                    if (FileUtils.hasChangedFiles(entry.getValue(), allocation.getSetupDate(), cacheConfig.getChangeDetectionExcludes())) {
                        logger.log("Changes detected in " + cacheConfig.describe(entry.getKey()));
                        uploadCacheThenLog(cacheConfig, entry.getKey(), entry.getValue());
                    }   
                }                 
            }
        }
    }

    public List<CacheAllocation> getAllocations() {
        return allocations;
    }

    protected abstract CacheAvailability downloadCache(String key, @Nullable String checksum,
            String path, File cacheDir);

    protected abstract boolean uploadCache(CacheConfigFacade cacheConfig, String path, File cacheDir);

}
