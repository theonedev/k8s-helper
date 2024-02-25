package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

import javax.annotation.Nullable;
import java.util.List;

public class ShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	private final String shell;
	
	public ShellFacade(@Nullable String image, @Nullable String builtInRegistryAccessToken,
					   String shell, String commands, boolean useTTY) {
		super(image, builtInRegistryAccessToken, commands, useTTY);
		this.shell = shell;
	}

	@Override
	public Commandline getScriptInterpreter() {
		return new Commandline(shell);
	}

	@Override
	public String[] getShell(boolean isLinux, String workingDir) {
		if (workingDir != null)
			return new String[]{shell, "-c", String.format("cd '%s' && '%s'", workingDir, shell)};
		else
			return new String[]{shell};
	}

	@Override
	public String getScriptExtension() {
		return ".sh";
	}

	@Override
	public String getEndOfLine() {
		return "\n";
	}
	
}
