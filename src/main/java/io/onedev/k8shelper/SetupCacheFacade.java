package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.util.List;

public class SetupCacheFacade extends LeafFacade {

    private static final long serialVersionUID = 1L;

    public enum UploadStrategy {UPLOAD_IF_NOT_HIT, UPLOAD_IF_CHANGED}

    private final String key;

    private final List<String> loadKeys;

    private final List<String> paths;

    private final UploadStrategy uploadStrategy;

    private final String changeDetectionExcludes;

    private final String uploadProjectPath;

    private final String uploadAccessToken;

    public SetupCacheFacade(String key, List<String> loadKeys, List<String> paths,
                            UploadStrategy uploadStrategy, @Nullable String changeDetectionExcludes,
                            @Nullable String uploadProjectPath, @Nullable String uploadAccessToken) {
        this.key = key;
        this.loadKeys = loadKeys;
        this.paths = paths;
        this.uploadStrategy = uploadStrategy;
        this.changeDetectionExcludes = changeDetectionExcludes;
        this.uploadProjectPath = uploadProjectPath;
        this.uploadAccessToken = uploadAccessToken;
    }

    public String getKey() {
        return key;
    }

    public List<String> getLoadKeys() {
        return loadKeys;
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

    public String getHitDescription() {
        return String.format("cache (key: %s, paths: %s)", getKey(), getPaths());
    }

    public String getMatchedDescription() {
        return String.format("cache (load keys: %s, paths: %s)", getLoadKeys(), getPaths());
    }

    public String getUploadDescription() {
        if (getUploadProjectPath() != null) {
            return String.format("cache (project: %s, key: %s, paths: %s)",
                   getUploadProjectPath(), getKey(), getPaths());
        } else {
            return String.format("cache (key: %s, paths: %s)", getKey(), getPaths());
        }
    }

}
