package io.onedev.k8shelper;

import java.util.List;

public class CommandExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final String image;
	
	private final List<String> commands;
	
	private final boolean useTTY;
	
	public CommandExecutable(String image, List<String> commands, boolean useTTY) {
		this.image = image;
		this.commands = commands;
		this.useTTY = useTTY;
	}

	public String getImage() {
		return image;
	}

	public List<String> getCommands() {
		return commands;
	}

	public boolean isUseTTY() {
		return useTTY;
	}

}
