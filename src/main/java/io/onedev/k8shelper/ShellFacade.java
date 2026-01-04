package io.onedev.k8shelper;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.command.Commandline;

public class ShellFacade extends CommandFacade {

	private static final long serialVersionUID = 1L;

	private final String shell;
	
	public ShellFacade(@Nullable String image, @Nullable String runAs, List<RegistryLoginFacade> registryLogins,
					   String shell, String commands, Map<String, String> envMap, boolean useTTY) {
		super(image, runAs, registryLogins, commands, envMap, useTTY);
		this.shell = shell;
	}

	@Override
	public Commandline getScriptInterpreter() {
		return new Commandline(shell);
	}

	@Override
	public String[] getShell(boolean isWindows, String workingDir) {
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
	
	public ShellFacade replacePlaceholders(File buildHome) {
		var image = KubernetesHelper.replacePlaceholders(getImage(), buildHome);
		return new ShellFacade(image, getRunAs(), getRegistryLogins(), shell, getCommands(), getEnvMap(), isUseTTY());
	}

}
