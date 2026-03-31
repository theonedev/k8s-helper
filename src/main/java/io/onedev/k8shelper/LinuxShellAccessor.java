package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;

public class LinuxShellAccessor extends ShellAccessor {

	private static final long serialVersionUID = 1L;

	private final String shell;

	public LinuxShellAccessor(String shell) {
		this.shell = shell;
	}

	@Override
	public String getExecutable() {
		return shell;
	}

	@Override
	public Commandline buildScriptCmdline() {
		return new Commandline(shell);
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
