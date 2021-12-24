package io.onedev.k8shelper;

import java.util.List;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SystemUtils;

import io.onedev.commons.utils.command.Commandline;

public class CommandExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final String image;
	
	private final List<String> commands;
	
	private final boolean useTTY;
	
	public CommandExecutable(@Nullable String image, List<String> commands, boolean useTTY) {
		this.image = image;
		this.commands = commands;
		this.useTTY = useTTY;
	}

	@Nullable
	public String getImage() {
		return image;
	}

	public List<String> getCommands() {
		return commands;
	}

	public boolean isUseTTY() {
		return useTTY;
	}
	
	public Commandline getInterpreter() {
		if (SystemUtils.IS_OS_WINDOWS) 
			return new Commandline("cmd").addArgs("/c");
		else
			return new Commandline("sh");
	}

	public String getScriptExtension() {
		if (SystemUtils.IS_OS_WINDOWS)
			return ".bat";
		else
			return ".sh";
	}

	public String getEndOfLine() {
		if (SystemUtils.IS_OS_WINDOWS)
			return "\r\n";
		else
			return "\n";
	}
	
}
