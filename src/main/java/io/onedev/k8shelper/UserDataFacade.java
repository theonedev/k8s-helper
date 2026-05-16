package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

import org.jspecify.annotations.Nullable;

public class UserDataFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String key;

	private final List<String> paths;

	@Nullable
	private final String changeDetectionExcludes;

	public UserDataFacade(String key, List<String> paths, @Nullable String changeDetectionExcludes) {
		this.key = key;
		this.paths = paths;
		this.changeDetectionExcludes = changeDetectionExcludes;
	}

	public String getKey() {
		return key;
	}

	public List<String> getPaths() {
		return paths;
	}

	@Nullable
	public String getChangeDetectionExcludes() {
		return changeDetectionExcludes;
	}

}