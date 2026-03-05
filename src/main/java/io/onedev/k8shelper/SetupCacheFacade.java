package io.onedev.k8shelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;

public class SetupCacheFacade extends LeafFacade {

    private static final long serialVersionUID = 1L;

    public enum UploadStrategy {UPLOAD_IF_NOT_EXACT_MATCH, UPLOAD_IF_CHANGED}

    private String key;

    private final Pair<Set<String>, Set<String>> checksumFiles;

    private final List<CachePathFacade> paths;

    private final UploadStrategy uploadStrategy;

    private final String changeDetectionExcludes;

    private final String uploadProjectPath;

    private final String uploadAccessToken;

    private String checksum;

    public SetupCacheFacade(String key, @Nullable Pair<Set<String>, Set<String>> checksumFiles,
                            List<CachePathFacade> paths, UploadStrategy uploadStrategy,
                            @Nullable String changeDetectionExcludes,
                            @Nullable String uploadProjectPath, @Nullable String uploadAccessToken) {
        this.key = key;
        this.checksumFiles = checksumFiles;
        this.paths = paths;
        this.uploadStrategy = uploadStrategy;
        this.changeDetectionExcludes = changeDetectionExcludes;
        this.uploadProjectPath = uploadProjectPath;
        this.uploadAccessToken = uploadAccessToken;
    }

    public String getKey() {
        return key;
    }

    @Nullable   
    public Pair<Set<String>, Set<String>> getChecksumFiles() {
        return checksumFiles;
    }

    @Nullable
    public String getChecksum() {
        return checksum;
    }

    public List<CachePathFacade> getPaths() {
        return paths;
    }

    public String getPathsAsString() {
        return paths.stream().map(CachePathFacade::toString).collect(Collectors.joining(","));
    }
 
    public UploadStrategy getUploadStrategy() {
        return uploadStrategy;
    }

    @Nullable
    public String getChangeDetectionExcludes() {
        return changeDetectionExcludes;
    }

    public String getUploadProjectPath() {
        return uploadProjectPath;
    }

    @Nullable
    public String getUploadAccessToken() {
        return uploadAccessToken;
    }

    public void replacePlaceholders(File buildDir) {
        key = KubernetesHelper.replacePlaceholders(key, buildDir);
    }

    public void computeChecksum(File workDir, TaskLogger logger) {
        if (checksumFiles != null) {
            if (workDir.exists()) {    
                try {
                    var digest = MessageDigest.getInstance("MD5");
                    var count = 0;
                    for (var file: FileUtils.listFiles(workDir, checksumFiles.getLeft(), checksumFiles.getRight())) {
                        digest.update(Files.readAllBytes(file.toPath()));
                        count++;
                    }
                    if (count == 0)
                        throw new ExplicitException("No checksum files found");
                    var hashBytes = digest.digest();
                    var sb = new StringBuilder();
                    for (byte b : hashBytes)
                        sb.append(String.format("%02x", b));
                    checksum = sb.toString();
                } catch (NoSuchAlgorithmException | IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new ExplicitException("Work dir does not exist: " + workDir);
            }    
        }
    }

    private String describeKeyAndChecksum() {
        if (checksum != null)
            return String.format("key: %s, checksum: %s", getKey(), checksum);
        else
            return String.format("key: %s", getKey());
    }

    public String describe() {
        return String.format("cache (%s)", describeKeyAndChecksum());
    }

    public String describeUpload() {
        var keyAndChecksum = describeKeyAndChecksum();
        if (getUploadProjectPath() != null)
            return String.format("cache (project: %s, %s)", getUploadProjectPath(), keyAndChecksum);
        else
            return String.format("cache (%s)", keyAndChecksum);
    }

}
