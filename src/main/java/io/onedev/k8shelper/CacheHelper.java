package io.onedev.k8shelper;

import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static io.onedev.k8shelper.SetupCacheFacade.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jspecify.annotations.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.match.WildcardUtils;

public abstract class CacheHelper {

    // Do not change this if absolutely necessary
    public static final int MARK_BUFFER_SIZE = 8192;

    private final File buildDir;

    private final TaskLogger logger;

    private final List<Triple<SetupCacheFacade, List<File>, Date>> caches = new ArrayList<>();

    private final Set<Pair<String, String>> exactMatchCacheKeyAndChecksums = new HashSet<>();

    public CacheHelper(File buildDir, TaskLogger logger) {
        this.buildDir = buildDir;
        this.logger = logger;
    }

    public void setupCache(SetupCacheFacade cacheConfig) {
        List<File> cacheDirs = new ArrayList<>();
        for (var cachePath: cacheConfig.getPaths()) {
            if (cachePath.getPathValue().contains(".."))
                throw new ExplicitException("Cache path does not allow to contain '..': " + cachePath.getPathValue());

            File cacheDir;
            if (cachePath.isAbsolute()) {
                int count = 0;
                for (var cache: caches)
                    count += cache.getMiddle().size();
                cacheDir = new File(buildDir, "cache/" + (count + cacheDirs.size() + 1));
            } else if (cachePath.isRelativeToHomeIfNotAbsolute()) {
                cacheDir = cachePath.resolveAgainst(new File(buildDir, "user"));
            } else {
                cacheDir = cachePath.resolveAgainst(new File(buildDir, "workspace"));
            }
            FileUtils.createDir(cacheDir);
            cacheDirs.add(cacheDir);
        }

        cacheConfig.replacePlaceholders(buildDir);
        cacheConfig.computeChecksum(new File(buildDir, "workspace"), logger);

        caches.add(new ImmutableTriple<>(cacheConfig, cacheDirs, new Date()));

        var cacheAvailability = downloadCache(cacheConfig.getKey(), cacheConfig.getChecksum(), cacheConfig.getPathsAsString(), cacheDirs);
        if (cacheAvailability == CacheAvailability.EXACT_MATCH) {
            logger.log(String.format("Exact matched %s", cacheConfig.describe()));
            exactMatchCacheKeyAndChecksums.add(ImmutablePair.of(cacheConfig.getKey(), cacheConfig.getChecksum()));
        } else if (cacheAvailability == CacheAvailability.PARTIAL_MATCH) {
            logger.log(String.format("Partial matched %s", cacheConfig.describe()));
        }
    }

    private void uploadCacheThenLog(SetupCacheFacade cacheConfig, List<File> cacheDirs) {
        if (uploadCache(cacheConfig, cacheDirs))
            logger.log(String.format("Uploaded %s", cacheConfig.describeUpload()));
        else
            logger.warning(String.format("Not authorized to upload %s", cacheConfig.describeUpload()));
    }

    public void buildFinished(boolean successful) {
        if (successful) {
            for (var cache : caches) {
                var cacheConfig = cache.getLeft();
                var cacheDirs = cache.getMiddle();
                if (cacheConfig.getUploadStrategy() == UPLOAD_IF_NOT_EXACT_MATCH) {
                    if (!exactMatchCacheKeyAndChecksums.contains(ImmutablePair.of(cacheConfig.getKey(), cacheConfig.getChecksum())))
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
                if (cachePath.isAbsolute()) {
                    var mountFrom = sourceTransformer.apply(cacheDirs.get(i).getAbsolutePath());
                    docker.addArgs("-v", mountFrom + ":" + cachePath.getPathValue());
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

    protected abstract CacheAvailability downloadCache(String key, @Nullable String checksum, 
            String cachePathsString, List<File> cacheDirs);

    protected abstract boolean uploadCache(SetupCacheFacade cacheConfig, List<File> cacheDirs);

}
