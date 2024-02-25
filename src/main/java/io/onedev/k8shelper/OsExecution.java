package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.io.Serializable;

public class OsExecution implements Serializable {

	private static final long serialVersionUID = 1L;

	private final OsMatcher osMatcher;
	
	private final String image;

	private final String commands;

	public OsExecution(OsMatcher osMatcher, @Nullable String image, String commands) {
		this.osMatcher = osMatcher;
		this.image = image;
		this.commands = commands;
	}

	public OsMatcher getOsMatcher() {
		return osMatcher;
	}

	@Nullable
	public String getImage() {
		return image;
	}

	public String getCommands() {
		return commands;
	}
	
}
