package io.onedev.k8shelper;

import static io.onedev.k8shelper.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;

public abstract class CacheProvisioner {

    // Do not change this if absolutely necessary
    public static final int MARK_BUFFER_SIZE = 8192;

    private final File baseDir;

    private final TaskLogger logger;

    private final List<CacheAllocation> allocations = new ArrayList<>();

    private final Set<Pair<String, String>> exactMatchCacheKeyAndChecksums = new HashSet<>();

    public CacheProvisioner(File baseDir, TaskLogger logger) {
        this.baseDir = baseDir;
        this.logger = logger;
    }

    public void setupCache(CacheConfigFacade cacheConfig) {
        List<File> cacheDirs = new ArrayList<>();
        for (var path: cacheConfig.getPaths()) {
            if (path.contains(".."))
                throw new ExplicitException("Cache path does not allow to contain '..': " + path);

            File cacheDir;
            if (FilenameUtils.getPrefixLength(path) > 0) {
                int count = 0;
                for (var allocation: allocations)
                    count += allocation.getDirs().size();
                cacheDir = new File(baseDir, "cache/" + (count + cacheDirs.size() + 1));
            } else {
                cacheDir = new File(new File(baseDir, "work"), path);
            }
            FileUtils.createDir(cacheDir);
            cacheDirs.add(cacheDir);
        }

        cacheConfig.replacePlaceholders(baseDir);
        cacheConfig.computeChecksum(new File(baseDir, "work"), logger);

        var cacheAvailability = downloadCache(
                cacheConfig.getKey(), cacheConfig.getChecksum(), 
                cacheConfig.getPathsAsString(), cacheDirs);
        if (cacheAvailability == CacheAvailability.EXACT_MATCH) {
            logger.log(String.format("Exact matched %s", cacheConfig.describe()));
            exactMatchCacheKeyAndChecksums.add(ImmutablePair.of(cacheConfig.getKey(), cacheConfig.getChecksum()));
        } else if (cacheAvailability == CacheAvailability.PARTIAL_MATCH) {
            logger.log(String.format("Partial matched %s", cacheConfig.describe()));    
        }
        allocations.add(new CacheAllocation(cacheConfig, cacheDirs, new Date()));
    }

    private void uploadCacheThenLog(CacheConfigFacade cacheConfig, List<File> cacheDirs) {
        if (uploadCache(cacheConfig, cacheDirs))
            logger.log(String.format("Uploaded %s", cacheConfig.describeUpload()));
        else
            logger.warning(String.format("Not authorized to upload %s", cacheConfig.describeUpload()));
    }

    public void uploadCaches() {
        for (var allocation : allocations) {
            var cacheConfig = allocation.getConfig();
            var cacheDirs = allocation.getDirs();
            if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_EXACT_MATCH) {
                if (!exactMatchCacheKeyAndChecksums.contains(ImmutablePair.of(cacheConfig.getKey(), cacheConfig.getChecksum())))
                    uploadCacheThenLog(cacheConfig, cacheDirs);
            } else {
                if (FileUtils.hasChangedFiles(cacheDirs, allocation.getSetupDate(), cacheConfig.getChangeDetectionExcludes())) {
                    logger.log("Cache changed");
                    uploadCacheThenLog(cacheConfig, cacheDirs);
                }
            }
        }
    }

    public Map<String, File> getPathMap() {
        var pathMap = new HashMap<String, File>();
        for (var allocation: allocations) {
            var cacheConfig = allocation.getConfig();
            var cacheDirs = allocation.getDirs();
            for (int i=0; i<cacheConfig.getPaths().size(); i++) {
                var path = cacheConfig.getPaths().get(i);
                if (FilenameUtils.getPrefixLength(path) > 0) 
                    pathMap.put(path, cacheDirs.get(i));
            }
        }
        return pathMap;
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

    protected abstract CacheAvailability downloadCache(String key, @Nullable String checksum, 
            String cachePathsString, List<File> cacheDirs);

    protected abstract boolean uploadCache(CacheConfigFacade cacheConfig, List<File> cacheDirs);

}
