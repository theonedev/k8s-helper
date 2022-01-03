package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

import javax.annotation.Nullable;

public class OsExecution implements Serializable {

	private static final long serialVersionUID = 1L;

	private final OsMatcher osMatcher;
	
	private final String image;
	
	private final List<String> commands;

	public OsExecution(OsMatcher osMatcher, @Nullable String image, List<String> commands) {
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

	public List<String> getCommands() {
		return commands;
	}
	
}
