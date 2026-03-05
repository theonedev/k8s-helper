package io.onedev.k8shelper;

import java.io.File;
import java.io.Serializable;

import org.apache.commons.io.FilenameUtils;

public class CachePathFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean relativeToHomeIfNotAbsolute;

	private final String pathValue;

	public CachePathFacade(boolean relativeToHomeIfNotAbsolute, String pathValue) {
		this.relativeToHomeIfNotAbsolute = relativeToHomeIfNotAbsolute;
		this.pathValue = pathValue;
	}

	public boolean isRelativeToHomeIfNotAbsolute() {
		return relativeToHomeIfNotAbsolute;
	}

	public String getPathValue() {
		return pathValue;
	}

	public boolean isAbsolute() {
		return FilenameUtils.getPrefixLength(pathValue) > 0;
	}

	public File resolveAgainst(File baseDir) {
		if (isAbsolute())
			return new File(pathValue);
		else
			return baseDir.toPath().resolve(getPathValue()).toFile();
	}

	@Override
	public String toString() {
		return relativeToHomeIfNotAbsolute + ":" + pathValue;
	}

}
