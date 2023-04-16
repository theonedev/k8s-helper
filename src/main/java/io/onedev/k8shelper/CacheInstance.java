package io.onedev.k8shelper;

import java.io.File;
import java.io.Serializable;

public class CacheInstance implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String cacheKey;

	private final String cacheUUID;

	public CacheInstance(String cacheKey, String cacheUUID) {
		this.cacheKey = cacheKey;
		this.cacheUUID = cacheUUID;
	}

	public String getCacheKey() {
		return cacheKey;
	}

	public String getCacheUUID() {
		return cacheUUID;
	}

	public File getDirectory(File cacheHome) {
		return new File(new File(cacheHome, cacheKey), cacheUUID);
	}

	@Override
	public String toString() {
		return cacheKey + "," + cacheUUID;
	}

	public static CacheInstance fromString(String string) {
		int index = string.indexOf(',');
		return new CacheInstance(string.substring(0, index), string.substring(index+1));
	}
	
}
