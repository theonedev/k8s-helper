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
import java.util.*;
import java.util.function.Function;

import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.util.stream.Collectors.toList;

public abstract class CacheHelper {

    // Do not change this if absolutely necessary
    public static final int MARK_BUFFER_SIZE = 8192;

    private final File buildHome;

    private final TaskLogger logger;

    private final Map<String, Pair<LinkedHashMap<String, File>, String>> cacheInfos = new HashMap<>();

    private final Set<String> hitCacheKeys = new HashSet<>();

    public CacheHelper(File buildHome, TaskLogger logger) {
        this.buildHome = buildHome;
        this.logger = logger;
    }

    public void setupCache(SetupCacheFacade cache) {
        LinkedHashMap<String, File> cacheDirs = new LinkedHashMap<>();
        for (var cachePath: cache.getPaths()) {
            if (cachePath.contains(".."))
                throw new ExplicitException("Cache path does not allow to contain '..': " + cachePath);

            File cacheDir;
            if (new File(cachePath).isAbsolute())
                cacheDir = new File(buildHome, "cache/" + (cacheInfos.size() + 1));
            else
                cacheDir = new File(buildHome, "workspace/" + cachePath);
            FileUtils.createDir(cacheDir);
            cacheDirs.put(cachePath, cacheDir);
        }

        var cacheInfo = new ImmutablePair<>(cacheDirs, cache.getUploadAccessToken());
        var cacheKey = replacePlaceholders(cache.getKey(), buildHome);
        if (cacheInfos.putIfAbsent(cacheKey, cacheInfo) != null)
            throw new ExplicitException("Duplicate cache key: " + cacheKey);

        if (downloadCache(cacheKey, cacheDirs)) {
            logger.log(String.format("Hit cache (key: %s, paths: %s)", cacheKey, cacheDirs.keySet()));
            hitCacheKeys.add(cacheKey);
        } else if (!cache.getLoadKeys().isEmpty()) {
            var cacheLoadKeys = cache.getLoadKeys().stream()
                    .map(it -> replacePlaceholders(it, buildHome))
                    .collect(toList());
            if (downloadCache(cacheLoadKeys, cacheDirs))
                logger.log(String.format("Matched cache (load keys: %s, paths: %s)", cacheLoadKeys, cacheDirs.keySet()));
        }
    }

    public void uploadCaches() {
        for (var entry: cacheInfos.entrySet()) {
            var cacheKey = entry.getKey();
            var cacheInfo = entry.getValue();
            if (!hitCacheKeys.contains(cacheKey)) {
                var cacheDirs = cacheInfo.getLeft();
                if (uploadCache(cacheKey, cacheDirs, cacheInfo.getRight()))
                    logger.log(String.format("Uploaded cache (key: %s, paths: %s)", cacheKey, cacheDirs.keySet()));
                else
                    logger.log(String.format("Not authorized to upload cache (key: %s, paths: %s)", cacheKey, cacheDirs.keySet()));
            }
        }
    }

    public void mountVolumes(Commandline docker, Function<String, String> sourceTransformer) {
        for (var cacheInfo: cacheInfos.values()) {
            var cacheDirs = cacheInfo.getLeft();
            for (var entry: cacheDirs.entrySet()) {
                if (new File(entry.getKey()).isAbsolute()) {
                    var mountFrom = sourceTransformer.apply(entry.getValue().getAbsolutePath());
                    docker.addArgs("-v", mountFrom + ":" + entry.getKey());
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

    protected abstract boolean downloadCache(String cacheKey, LinkedHashMap<String, File> cacheDirs);

    protected abstract boolean downloadCache(List<String> cacheLoadKeys, LinkedHashMap<String, File> cacheDirs);

    protected abstract boolean uploadCache(String cacheKey, LinkedHashMap<String, File> cacheDirs,
                                           @Nullable String accessToken);

}
