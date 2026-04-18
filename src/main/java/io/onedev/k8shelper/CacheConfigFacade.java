package io.onedev.k8shelper;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;

public class CacheConfigFacade implements Serializable {

    private static final long serialVersionUID = 1L;

    private String key;

    private final Pair<Set<String>, Set<String>> checksumFiles;

    private final List<String> paths;

    private final UploadStrategy uploadStrategy;

    private final String changeDetectionExcludes;

    private final String uploadProjectPath;

    private final String uploadAccessToken;

    private String checksum;

    public CacheConfigFacade(String key, @Nullable Pair<Set<String>, Set<String>> checksumFiles,
                            List<String> paths, UploadStrategy uploadStrategy,
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

    public List<String> getPaths() {
        return paths;
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

    public void replacePlaceholders(File baseDir) {
        key = KubernetesHelper.replacePlaceholders(key, baseDir);
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

    private String describeKeyAndChecksum(String path) {
        if (checksum != null)
            return String.format("key: %s, checksum: %s, path: %s", getKey(), checksum, path);
        else
            return String.format("key: %s, path: %s", getKey(), path);
    }

    public String describe(String path) {
        return String.format("cache (%s)", describeKeyAndChecksum(path));
    }

    public String describeUpload(String path) {
        var keyAndChecksum = describeKeyAndChecksum(path);
        if (getUploadProjectPath() != null)
            return String.format("cache (project: %s, %s)", getUploadProjectPath(), keyAndChecksum);
        else
            return String.format("cache (%s)", keyAndChecksum);
    }

}
