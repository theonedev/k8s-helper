package io.onedev.k8shelper;

import org.apache.commons.lang3.SystemUtils;

public class DefaultShellFacility extends ShellFacility {

	private static final long serialVersionUID = 1L;

	@Override
	public String getExecutable() {
		if (SystemUtils.IS_OS_WINDOWS)
			return "cmd";
		else
			return "sh";
	}

	@Override
	public String[] getScriptOptions() {
		if (SystemUtils.IS_OS_WINDOWS)
			return new String[] {"/c"};
		else
			return new String[0];
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
