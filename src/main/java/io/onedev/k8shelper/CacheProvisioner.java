package io.onedev.k8shelper;

import static io.onedev.k8shelper.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.io.FilenameUtils;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;

public abstract class CacheProvisioner implements Serializable {

    private static final long serialVersionUID = 1L;

    private final CacheConfigFacade config;

    private final String CACHE_DIR_PREFIX = "cache-";

    private final Map<String, Integer> absolutePathIndexes;

    private final Set<String> exactMatchPaths = new HashSet<>();

    private final int configIndex;

    private Date provisionDate;

    public CacheProvisioner(CacheConfigFacade config, int configIndex) {
        this.config = config;
        this.configIndex = configIndex;
        absolutePathIndexes = new HashMap<>();
        var absolutePathIndex = 1;
        for (var path : config.getPaths()) {
            if (FilenameUtils.getPrefixLength(path) > 0)
                absolutePathIndexes.put(path, absolutePathIndex++);
        }
    }

    private String getCacheDirName() {
        return CACHE_DIR_PREFIX + configIndex;
    }

    public CacheConfigFacade getConfig() {
        return config;
    }

    public Map<String, Integer> getAbsolutePathIndexes() {
        return absolutePathIndexes;
    }

    public String getSubPath(int absolutePathIndex) {
        return getCacheDirName() + "/" + absolutePathIndex;
    }

    public File getPathDir(File baseDir, String path) {
        File pathDir;
        var pathIndex = absolutePathIndexes.get(path);
        if (pathIndex != null)
            pathDir = new File(baseDir, getCacheDirName() + "/" + pathIndex);
        else if (!path.contains(".."))
            pathDir = new File(baseDir, "work/" + path);
        else
            throw new ExplicitException("Cache path does not allow to contain '..': " + path);
        return pathDir;
    }

    public void download(File baseDir, TaskLogger logger) {
        config.replacePlaceholders(baseDir);
        config.computeChecksum(new File(baseDir, "work"), logger);

        for (var path: config.getPaths()) {
            var pathDir = getPathDir(baseDir, path);
            FileUtils.createDir(pathDir);
            var availability = download(config.getKey(), config.getChecksum(), path, pathDir);
            if (availability == CacheAvailability.EXACT_MATCH)
                logger.log("Exact matched " + config.describe(path));
            else if (availability == CacheAvailability.PARTIAL_MATCH)
                logger.log("Partial matched " + config.describe(path));

            if (availability == CacheAvailability.EXACT_MATCH)
                exactMatchPaths.add(path);
        }
        provisionDate = new Date();
    }

    private void uploadThenLog(String path, File pathDir, TaskLogger logger) {
        if (upload(config, path, pathDir))
            logger.log(String.format("Uploaded %s", config.describeUpload(path)));
        else
            logger.warning(String.format("Not authorized to upload %s", config.describeUpload(path)));
    }

    public void upload(File baseDir, TaskLogger logger) {
        var excludePathPatterns = Arrays.asList(StringUtils.parseQuoteTokens(config.getChangeDetectionExcludes()));
        for (var path: config.getPaths()) {
            var pathDir = getPathDir(baseDir, path);
            if (config.getUploadStrategy() == UPLOAD_IF_NOT_EXACT_MATCH) {
                if (!exactMatchPaths.contains(path)) 
                    uploadThenLog(path, pathDir, logger);
            } else {
                if (provisionDate == null || FileUtils.hasChangedFiles(pathDir, provisionDate, excludePathPatterns)) {
                    logger.log("Changes detected in " + config.describe(path));
                    uploadThenLog(path, pathDir, logger);
                }   
            }                 
        }
    }

    public void mountVolumes(Commandline docker, File workspaceDir, Function<String, String> hostPathResolver) {
        for (var path: config.getPaths()) {
            if (FilenameUtils.getPrefixLength(path) > 0) {
                var pathDir = getPathDir(workspaceDir, path);
                docker.addArgs("-v", hostPathResolver.apply(pathDir.getAbsolutePath()) + ":" + path);
            }
        }
    }

    protected abstract CacheAvailability download(String key, @Nullable String checksum,
            String path, File pathDir);

    protected abstract boolean upload(CacheConfigFacade config, String path, File pathDir);

}
