package io.onedev.k8shelper;

import static io.onedev.k8shelper.UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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

    @Serial
    private static final long serialVersionUID = 1L;

    private final CacheConfigFacade config;

    private final Set<String> exactMatchPaths = new HashSet<>();

    private final boolean allowExternalSymlinks;

    private final Map<String, Date> provisionDates = new HashMap<>();

    public CacheProvisioner(CacheConfigFacade config) {
        this(config, false);
    }

    public CacheProvisioner(CacheConfigFacade config, boolean allowExternalSymlinks) {
        this.config = config;
        this.allowExternalSymlinks = allowExternalSymlinks;
    }

    private String getCacheDirName(String path) {
        try {
            var name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(UTF_8)));
            return "cache/" + name;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public CacheConfigFacade getConfig() {
        return config;
    }

    boolean isAllowExternalSymlinks() {
        return allowExternalSymlinks;
    }

    public String getDataPath(String path) {
        return getCacheDirName(path) + "/data";
    }

    public File getPathDir(File baseDir, String path) {
        File pathDir;
        if (FilenameUtils.getPrefixLength(path) > 0)
            pathDir = new File(baseDir, getDataPath(path));
        else if (!path.contains(".."))
            pathDir = new File(baseDir, "work/" + path);
        else
            throw new ExplicitException("Cache path does not allow to contain '..': " + path);
        if (FileUtils.hasSymbolLinks(baseDir, pathDir))
            throw new ExplicitException("Cache path does not allow to contain symbol links: " + path);
        return pathDir;
    }

    public void download(File baseDir, TaskLogger logger) {
        download(baseDir, logger, false);
    }

    /** Optionally retain cache contents and their upload baseline across workspace activations. */
    public void download(File baseDir, TaskLogger logger, boolean trackState) {
        config.replacePlaceholders(baseDir);
        config.computeChecksum(new File(baseDir, "work"), logger);
        exactMatchPaths.clear();
        provisionDates.clear();

        for (var entry: config.getEntries()) {
            var path = entry.getPath();
            var pathDir = getPathDir(baseDir, path);
            var stateFile = trackState ? getStateFile(baseDir, path) : null;
            var state = stateFile != null ? readState(stateFile) : null;
            if (state != null && pathDir.isDirectory()
                    && config.getKey().equals(state.getProperty("key"))) {
                if (Boolean.parseBoolean(state.getProperty("exactMatch"))
                        && Objects.equals(config.getChecksum(), state.getProperty("checksum"))) {
                    exactMatchPaths.add(path);
                }
                provisionDates.put(path, new Date(Long.parseLong(state.getProperty("provisionDate"))));
                continue;
            }
            // A failed replacement must not leave metadata describing the previous contents.
            if (stateFile != null)
                FileUtils.deletePath(stateFile);
            FileUtils.createDir(pathDir);
            var availability = download(config.getKey(), config.getChecksum(), path, pathDir);
            if (availability == CacheAvailability.EXACT_MATCH)
                logger.log("Exact matched " + config.describe(path));
            else if (availability == CacheAvailability.PARTIAL_MATCH)
                logger.log("Partial matched " + config.describe(path));

            if (availability == CacheAvailability.EXACT_MATCH)
                exactMatchPaths.add(path);
            if (stateFile != null) {
                var restoredAt = new Date();
                state = new Properties();
                state.setProperty("key", config.getKey());
                if (config.getChecksum() != null)
                    state.setProperty("checksum", config.getChecksum());
                state.setProperty("exactMatch", String.valueOf(availability == CacheAvailability.EXACT_MATCH));
                state.setProperty("provisionDate", String.valueOf(restoredAt.getTime()));
                writeState(stateFile, state);
                provisionDates.put(path, restoredAt);
            }
        }
        var completedAt = new Date();
        for (var path : config.getPaths())
            provisionDates.putIfAbsent(path, completedAt);
    }

    private File getStateFile(File baseDir, String path) {
        var stateFile = new File(baseDir, getCacheDirName(path) + "/state");
        if (FileUtils.hasSymbolLinks(baseDir, stateFile))
            throw new ExplicitException("Cache state path does not allow to contain symbol links: " + stateFile);
        return stateFile;
    }

    @Nullable
    private Properties readState(File stateFile) {
        if (!stateFile.exists())
            return null;
        try (var reader = Files.newBufferedReader(stateFile.toPath(), UTF_8)) {
            var state = new Properties();
            state.load(reader);
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeState(File stateFile, Properties state) {
        try {
            Files.createDirectories(stateFile.toPath().getParent());
            var temporary = Files.createTempFile(stateFile.toPath().getParent(), ".restore-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, UTF_8)) {
                    state.store(writer, null);
                }
                try {
                    Files.move(temporary, stateFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, stateFile.toPath(), REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void uploadThenLog(String path, File pathDir, List<String> excludes, TaskLogger logger) {
        if (upload(config, path, pathDir, excludes))
            logger.log(String.format("Uploaded %s", config.describeUpload(path)));
        else
            logger.warning(String.format("Not authorized to upload %s", config.describeUpload(path)));
    }

    public void upload(File baseDir, TaskLogger logger) {
        for (var entry: config.getEntries()) {
            var path = entry.getPath();
            var pathDir = getPathDir(baseDir, path);
            var excludes = Arrays.asList(StringUtils.parseQuoteTokens(entry.getExcludes()));
            if (config.getUploadStrategy() == UPLOAD_IF_NOT_EXACT_MATCH) {
                if (!exactMatchPaths.contains(path)) 
                    uploadThenLog(path, pathDir, excludes, logger);
            } else {
                var restoredAt = provisionDates.get(path);
                if (restoredAt == null || FileUtils.hasChangedFiles(pathDir, restoredAt, excludes)) {
                    logger.log("Changes detected in " + config.describe(path));
                    uploadThenLog(path, pathDir, excludes, logger);
                }   
            }                 
        }
    }

    public void mountVolumes(Commandline docker, File workspaceDir, Function<String, String> hostPathResolver) {
        for (var entry: config.getEntries()) {
            var path = entry.getPath();
            if (FilenameUtils.getPrefixLength(path) > 0) {
                var pathDir = getPathDir(workspaceDir, path);
                docker.addArgs("-v", hostPathResolver.apply(pathDir.getAbsolutePath()) + ":" + path);
            }
        }
    }

    protected abstract CacheAvailability download(String key, @Nullable String checksum,
            String path, File pathDir);

    protected abstract boolean upload(CacheConfigFacade config, String path, File pathDir, List<String> excludes);

}
