package io.onedev.k8shelper;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

public class CacheEntryFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String path;

	@Nullable
	private final String excludes;

	public CacheEntryFacade(String path, @Nullable String excludes) {
		this.path = path;
		this.excludes = excludes;
	}

	public String getPath() {
		return path;
	}

	@Nullable
	public String getExcludes() {
		return excludes;
	}

}
