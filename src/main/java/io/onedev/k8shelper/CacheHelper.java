package io.onedev.k8shelper;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.SetupCacheFacade.UploadStrategy.*;
import static java.util.stream.Collectors.toList;

public abstract class CacheHelper {

    // Do not change this if absolutely necessary
    public static final int MARK_BUFFER_SIZE = 8192;

    private final File buildHome;

    private final TaskLogger logger;

    private final List<Pair<SetupCacheFacade, List<File>>> caches = new ArrayList<>();

    private final Set<String> hitCacheKeys = new HashSet<>();

    private final Set<String> matchedCacheKeys = new HashSet<>();

    public CacheHelper(File buildHome, TaskLogger logger) {
        this.buildHome = buildHome;
        this.logger = logger;
    }

    public void setupCache(SetupCacheFacade cacheConfig) {
        List<File> cacheDirs = new ArrayList<>();
        for (var cachePath: cacheConfig.getPaths()) {
            if (cachePath.contains(".."))
                throw new ExplicitException("Cache path does not allow to contain '..': " + cachePath);

            File cacheDir;
            if (new File(cachePath).isAbsolute())
                cacheDir = new File(buildHome, "cache/" + (caches.size() + 1));
            else
                cacheDir = new File(buildHome, "workspace/" + cachePath);
            FileUtils.createDir(cacheDir);
            cacheDirs.add(cacheDir);
        }

        var cacheKey = replacePlaceholders(cacheConfig.getKey(), buildHome);
        var loadKeys = cacheConfig.getLoadKeys().stream()
                .map(it -> replacePlaceholders(it, buildHome))
                .collect(toList());
        cacheConfig = new SetupCacheFacade(cacheKey, loadKeys, cacheConfig.getPaths(), cacheConfig.getUploadStrategy(),
                cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken());
        caches.add(new ImmutablePair<>(cacheConfig, cacheDirs));

        if (downloadCache(cacheKey, cacheConfig.getPaths(), cacheDirs)) {
            logger.log("Hit " + cacheConfig.getHitDescription());
            hitCacheKeys.add(cacheKey);
        } else if (!cacheConfig.getLoadKeys().isEmpty()) {
            if (downloadCache(cacheConfig.getLoadKeys(), cacheConfig.getPaths(), cacheDirs)) {
                logger.log("Matched " + cacheConfig.getMatchedDescription());
                matchedCacheKeys.add(cacheKey);
            }
        }
    }

    public void buildSuccessful() {
        for (var cache: caches) {
            var cacheConfig = cache.getLeft();
            var cacheDirs = cache.getRight();
            if (cacheConfig.getUploadStrategy() == ALWAYS_UPLOAD) {
                if (uploadCache(cacheConfig.getKey(), cacheConfig.getPaths(), cacheDirs, cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken()))
                    logger.log("Uploaded " + cacheConfig.getUploadDescription());
                else
                    logger.warning("Not authorized to upload " + cacheConfig.getUploadDescription());
            } else if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_HIT) {
                if (!hitCacheKeys.contains(cacheConfig.getKey())) {
                    if (uploadCache(cacheConfig.getKey(), cacheConfig.getPaths(), cacheDirs, cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken()))
                        logger.log("Uploaded " + cacheConfig.getUploadDescription());
                    else
                        logger.warning("Not authorized to upload " + cacheConfig.getUploadDescription());
                }
            } else if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_FOUND) {
                if (!hitCacheKeys.contains(cacheConfig.getKey()) && !matchedCacheKeys.contains(cacheConfig.getKey())) {
                    if (uploadCache(cacheConfig.getKey(), cacheConfig.getPaths(), cacheDirs, cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken()))
                        logger.log("Uploaded " + cacheConfig.getUploadDescription());
                    else
                        logger.warning("Not authorized to upload " + cacheConfig.getUploadDescription());
                }
            }
        }
    }

    public void mountVolumes(Commandline docker, Function<String, String> sourceTransformer) {
        for (var cache: caches) {
            var cacheConfig = cache.getLeft();
            var cacheDirs = cache.getRight();
            for (int i=0; i<cacheConfig.getPaths().size(); i++) {
                var cachePath = cacheConfig.getPaths().get(i);
                if (new File(cachePath).isAbsolute()) {
                    var mountFrom = sourceTransformer.apply(cacheDirs.get(i).getAbsolutePath());
                    docker.addArgs("-v", mountFrom + ":" + cachePath);
                }
            }
        }
    }

    public static void tar(List<File> cacheDirs, OutputStream os) {
        var marks = new ArrayList<Long>();
        var cos = new CountingOutputStream(os);
        for (var cacheDir: cacheDirs) {
            TarUtils.tar(cacheDir, cos, true);
            marks.add(cos.getCount());
        }

        var buffer = new byte[MARK_BUFFER_SIZE];
        if (buffer.length < Integer.BYTES + marks.size() * Long.BYTES)
            throw new ExplicitException("Too many marks");

        ByteBuffer.wrap(buffer, 0, Integer.BYTES).putInt(marks.size());
        for (int i = 0; i < marks.size(); i++)
            ByteBuffer.wrap(buffer, Integer.BYTES + i * Long.BYTES, Long.BYTES).putLong(marks.get(i));
        try {
            os.write(buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static void untar(List<File> cacheDirs, InputStream is) {
        var buffer = new byte[MARK_BUFFER_SIZE];
        try {
            IOUtils.readFully(is, buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<Long> marks = new ArrayList<>();
        for (int i = 0; i< ByteBuffer.wrap(buffer, 0, Integer.BYTES).getInt(); i++)
            marks.add(ByteBuffer.wrap(buffer, Integer.BYTES + i * Long.BYTES, Long.BYTES).getLong());

        Preconditions.checkState(cacheDirs.size() == marks.size());
        long lastMark = 0;
        var itMark = marks.iterator();
        for (var cacheDir: cacheDirs) {
            var mark = itMark.next();
            TarUtils.untar(ByteStreams.limit(is, mark - lastMark), cacheDir, true);
            lastMark = mark;
        }
    }

    protected abstract boolean downloadCache(String cacheKey, List<String> cachePaths, List<File> cacheDirs);

    protected abstract boolean downloadCache(List<String> loadKeys, List<String> cachePaths, List<File> cacheDirs);

    protected abstract boolean uploadCache(String cacheKey, List<String> cachePaths, List<File> cacheDirs,
                                           @Nullable String projectPath, @Nullable String accessToken);

}
