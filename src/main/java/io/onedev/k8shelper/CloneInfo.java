package io.onedev.k8shelper;

import java.io.File;
import java.io.Serializable;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public abstract class CloneInfo implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	private final String cloneUrl;
	
	public CloneInfo(String cloneUrl) {
		this.cloneUrl = cloneUrl;
	}
	
	public String getCloneUrl() {
		return cloneUrl;
	}
	
	public abstract void writeAuthData(File homeDir, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger);
	
}
