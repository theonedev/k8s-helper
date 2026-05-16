package io.onedev.k8shelper;

import java.io.Serializable;

public class ConfigFileFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String path;

	private final String content;

	public ConfigFileFacade(String path, String content) {
		this.path = path;
		this.content = content;
	}

	public String getPath() {
		return path;
	}

	public String getContent() {
		return content;
	}

}