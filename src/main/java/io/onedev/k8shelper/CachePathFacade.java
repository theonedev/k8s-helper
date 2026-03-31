package io.onedev.k8shelper;

import java.io.File;
import java.io.Serializable;

import org.apache.commons.io.FilenameUtils;

public class CachePathFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean relativeToHomeIfNotAbsolute;

	private final String path;

	public CachePathFacade(boolean relativeToHomeIfNotAbsolute, String path) {
		this.relativeToHomeIfNotAbsolute = relativeToHomeIfNotAbsolute;
		this.path = path;
	}

	public boolean isRelativeToHomeIfNotAbsolute() {
		return relativeToHomeIfNotAbsolute;
	}

	public String getPath() {
		return path;
	}

	public boolean isAbsolute() {
		return FilenameUtils.getPrefixLength(path) > 0;
	}

	public File resolveAgainst(File baseDir) {
		if (isAbsolute())
			return new File(path);
		else
			return baseDir.toPath().resolve(getPath()).toFile();
	}

	@Override
	public String toString() {
		return relativeToHomeIfNotAbsolute + ":" + path;
	}

}
