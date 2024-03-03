package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.io.Serializable;

public class OsExecution implements Serializable {

	private static final long serialVersionUID = 1L;

	private final OsMatcher osMatcher;
	
	private final String image;

	private final String runAs;

	private final String commands;

	public OsExecution(OsMatcher osMatcher, @Nullable String image, @Nullable String runAs, String commands) {
		this.osMatcher = osMatcher;
		this.image = image;
		this.runAs = runAs;
		this.commands = commands;
	}

	public OsMatcher getOsMatcher() {
		return osMatcher;
	}

	@Nullable
	public String getImage() {
		return image;
	}

	@Nullable
	public String getRunAs() {
		return runAs;
	}

	public String getCommands() {
		return commands;
	}
	
}
