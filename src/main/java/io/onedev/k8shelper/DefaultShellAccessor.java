package io.onedev.k8shelper;

import org.apache.commons.lang3.SystemUtils;

import io.onedev.commons.utils.command.Commandline;

public class DefaultShellAccessor extends ShellAccessor {

	private static final long serialVersionUID = 1L;

	@Override
	public String getExecutable() {
		if (SystemUtils.IS_OS_WINDOWS)
			return "cmd";
		else
			return "sh";
	}

	@Override
	public Commandline buildScriptCmdline() {
		if (SystemUtils.IS_OS_WINDOWS)
			return new Commandline("cmd").addArgs("/c");
		else
			return new Commandline("sh");
	}

	@Override
	public String getScriptExtension() {
		if (SystemUtils.IS_OS_WINDOWS)
			return ".bat";
		else
			return ".sh";
	}

	@Override
	public String getEndOfLine() {
		if (SystemUtils.IS_OS_WINDOWS)
			return "\r\n";
		else
			return "\n";
	}

}
