package io.onedev.k8shelper;

import java.io.File;
import java.io.Serializable;

public class CacheInstance implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	
	private final String cacheKey;
	
	public CacheInstance(String name, String cacheKey) {
		this.name = name;
		this.cacheKey = cacheKey;
	}

	public String getName() {
		return name;
	}

	public String getCacheKey() {
		return cacheKey;
	}
	
	public File getDirectory(File cacheHome) {
		return new File(new File(cacheHome, cacheKey), name);
	}

	@Override
	public String toString() {
		return name + "," + cacheKey;
	}

	public static CacheInstance fromString(String string) {
		int index = string.indexOf(',');
		return new CacheInstance(string.substring(0, index), string.substring(index+1));
	}
	
}
