package io.onedev.k8shelper;

public class SetupCacheFacade extends LeafFacade {

    private static final long serialVersionUID = 1L;

    private final CacheConfigFacade cacheConfig;

    public SetupCacheFacade(CacheConfigFacade cacheConfig) {
        this.cacheConfig = cacheConfig;
    }

    public CacheConfigFacade getCacheConfig() {
        return cacheConfig;
    }

}
