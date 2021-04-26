package io.onedev.k8shelper;

import java.util.List;

public class CommandExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final String image;
	
	private final List<String> commands;
	
	public CommandExecutable(String image, List<String> commands) {
		this.image = image;
		this.commands = commands;
	}

	public String getImage() {
		return image;
	}

	public List<String> getCommands() {
		return commands;
	}

}
