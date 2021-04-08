package io.onedev.k8shelper;

import java.util.List;

public class CommandExecutable implements Executable {

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

	@Override
	public boolean execute(CommandHandler handler, List<Integer> position) {
		return handler.execute(this, position);
	}
	
	@Override
	public void skip(CommandHandler handler, List<Integer> position) {
		handler.skip(this, position);
	}

	@Override
	public <T> T traverse(CommandVisitor<T> visitor, List<Integer> position) {
		return visitor.visit(this, position);
	}
	
}
