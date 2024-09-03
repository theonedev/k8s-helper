package io.onedev.k8shelper;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.match.WildcardUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.SetupCacheFacade.UploadStrategy.UPLOAD_IF_NOT_HIT;
import static java.util.stream.Collectors.toList;

public abstract class CacheHelper {

    // Do not change this if absolutely necessary
    public static final int MARK_BUFFER_SIZE = 8192;

    private final File buildHome;

    private final TaskLogger logger;

    private final List<Triple<SetupCacheFacade, List<File>, Date>> caches = new ArrayList<>();

    private final Set<String> hitCacheKeys = new HashSet<>();

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
        cacheConfig = new SetupCacheFacade(cacheKey, loadKeys, cacheConfig.getPaths(),
                cacheConfig.getUploadStrategy(), cacheConfig.getChangeDetectionExcludes(),
                cacheConfig.getUploadProjectPath(), cacheConfig.getUploadAccessToken());
        caches.add(new ImmutableTriple<>(cacheConfig, cacheDirs, new Date()));

        if (downloadCache(cacheKey, cacheConfig.getPaths(), cacheDirs)) {
            logger.log("Hit " + cacheConfig.getHitDescription());
            hitCacheKeys.add(cacheKey);
        } else if (!cacheConfig.getLoadKeys().isEmpty()) {
            if (downloadCache(cacheConfig.getLoadKeys(), cacheConfig.getPaths(), cacheDirs))
                logger.log("Matched " + cacheConfig.getMatchedDescription());
        }
    }

    private void uploadCacheThenLog(SetupCacheFacade cacheConfig, List<File> cacheDirs) {
        if (uploadCache(cacheConfig, cacheDirs))
            logger.log("Uploaded " + cacheConfig.getUploadDescription());
        else
            logger.warning("Not authorized to upload " + cacheConfig.getUploadDescription());
    }

    public void buildFinished(boolean successful) {
        if (successful) {
            for (var cache : caches) {
                var cacheConfig = cache.getLeft();
                var cacheDirs = cache.getMiddle();
                if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_HIT) {
                    if (!hitCacheKeys.contains(cacheConfig.getKey()))
                        uploadCacheThenLog(cacheConfig, cacheDirs);
                } else {
                    var changedFile = getChangedFile(cacheDirs, cache.getRight(), cacheConfig);
                    if (changedFile != null) {
                        logger.log("Cache file changed: " + changedFile);
                        uploadCacheThenLog(cacheConfig, cacheDirs);
                    }
                }
            }
        }
    }

    public void mountVolumes(Commandline docker, Function<String, String> sourceTransformer) {
        for (var cache: caches) {
            var cacheConfig = cache.getLeft();
            var cacheDirs = cache.getMiddle();
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

    @Nullable
    public static String getChangedFile(List<File> cacheDirs, Date sinceDate, SetupCacheFacade cacheConfig) {
        var excludeFiles = new ArrayList<String>();
        if (cacheConfig.getChangeDetectionExcludes() != null)
            Collections.addAll(excludeFiles, parseQuoteTokens(cacheConfig.getChangeDetectionExcludes()));
        for (var cacheDir: cacheDirs) {
            try {
                var changedFile = new AtomicReference<String>(null);
                Files.walkFileTree(cacheDir.toPath(), new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (attrs.isSymbolicLink())
                            return FileVisitResult.SKIP_SUBTREE;
                        else
                            return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isSymbolicLink())
                            return FileVisitResult.SKIP_SUBTREE;
                        for (var excludeFile: excludeFiles) {
                            if (WildcardUtils.matchPath(excludeFile, cacheDir.toPath().relativize(file).toString()))
                                return FileVisitResult.CONTINUE;
                        }
                        if (attrs.creationTime().toMillis() > sinceDate.getTime()
                                || attrs.lastModifiedTime().toMillis() > sinceDate.getTime()) {
                            changedFile.set(file.toString());
                            return FileVisitResult.TERMINATE;
                        } else {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        throw exc;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }

                });
                if (changedFile.get() != null)
                    return changedFile.get();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
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

    protected abstract boolean uploadCache(SetupCacheFacade cacheConfig, List<File> cacheDirs);

}
