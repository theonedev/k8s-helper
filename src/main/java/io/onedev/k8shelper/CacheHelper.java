package io.onedev.k8shelper;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.function.Function;

import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.util.stream.Collectors.toList;

public abstract class CacheHelper {

    private final File buildHome;

    private final TaskLogger logger;

    private final Map<String, Triple<String, String, File>> cacheInfos = new HashMap<>();

    private final Set<String> hitCacheKeys = new HashSet<>();

    public CacheHelper(File buildHome, TaskLogger logger) {
        this.buildHome = buildHome;
        this.logger = logger;
    }

    public void setupCache(SetupCacheFacade cache) {
        File cacheDir;
        var cachePath = cache.getPath();
        if (cachePath.contains(".."))
            throw new ExplicitException("Cache path does not allow to contain '..': " + cachePath);

        if (new File(cachePath).isAbsolute())
            cacheDir = new File(buildHome, "cache/" + (cacheInfos.size() + 1));
        else
            cacheDir = new File(buildHome, "workspace/" + cachePath);

        if (cacheInfos.values().stream().anyMatch(it -> it.getLeft().equals(cachePath)))
            throw new ExplicitException("Duplicate cache path: " + cachePath);
        var cacheInfo = new ImmutableTriple<>(cachePath, cache.getUploadAccessToken(), cacheDir);
        var cacheKey = replacePlaceholders(cache.getKey(), buildHome);
        if (cacheInfos.putIfAbsent(cacheKey, cacheInfo) != null)
            throw new ExplicitException("Duplicate cache key: " + cacheKey);

        FileUtils.createDir(cacheDir);
        if (downloadCache(cacheKey, cachePath, cacheDir)) {
            logger.log("Hit cache '" + cacheKey + "'");
            hitCacheKeys.add(cacheKey);
        } else if (!cache.getLoadKeys().isEmpty()) {
            var cacheLoadKeys = cache.getLoadKeys().stream()
                    .map(it -> replacePlaceholders(it, buildHome))
                    .collect(toList());
            if (downloadCache(cacheLoadKeys, cachePath, cacheDir))
                logger.log("Loaded cache " + cacheLoadKeys);
        }
    }

    public void uploadCaches() {
        for (var entry: cacheInfos.entrySet()) {
            var cacheKey = entry.getKey();
            var cacheInfo = entry.getValue();
            if (!hitCacheKeys.contains(cacheKey)) {
                if (uploadCache(cacheKey, cacheInfo.getLeft(), cacheInfo.getMiddle(), cacheInfo.getRight()))
                    logger.log("Uploaded cache '" + cacheKey + "'");
                else
                    logger.warning("Not authorized to upload cache '" + cacheKey + "'");
            }
        }
    }

    public void mountVolumes(Commandline docker, Function<String, String> sourceTransformer) {
        for (var cacheInfo: cacheInfos.values()) {
            var cachePath = cacheInfo.getLeft();
            if (new File(cachePath).isAbsolute()) {
                var mountFrom = sourceTransformer.apply(cacheInfo.getRight().getAbsolutePath());
                docker.addArgs("-v", mountFrom + ":" + cachePath);
            }
        }
    }

    protected abstract boolean downloadCache(String cacheKey, String cachePath, File cacheDir);

    protected abstract boolean downloadCache(List<String> cacheLoadKeys, String cachePath, File cacheDir);

    protected abstract boolean uploadCache(String cacheKey, String cachePath,
                                           @Nullable String accessToken, File cacheDir);

}
