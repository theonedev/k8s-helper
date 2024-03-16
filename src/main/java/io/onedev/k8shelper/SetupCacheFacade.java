package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.util.List;

public class SetupCacheFacade extends LeafFacade {

    private static final long serialVersionUID = 1L;

    private final String key;

    private final List<String> loadKeys;

    private final List<String> paths;

    private final String uploadAccessToken;

    public SetupCacheFacade(String key, List<String> loadKeys, List<String> paths, @Nullable String uploadAccessToken) {
        this.key = key;
        this.loadKeys = loadKeys;
        this.paths = paths;
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

    @Nullable
    public String getUploadAccessToken() {
        return uploadAccessToken;
    }

}
