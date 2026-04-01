package io.onedev.k8shelper;

import java.io.Serializable;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

public abstract class ShellFacility implements Serializable {

	private static final long serialVersionUID = 1L;

	public abstract String getExecutable();

	public abstract String[] getScriptOptions();

	public abstract String getScriptExtension();

	public abstract String getEndOfLine();

	public String normalizeCommands(String commands) {
		var builder = new StringBuilder();
		for (var line : Splitter.on('\n').trimResults(CharMatcher.is('\r')).split(commands))
			builder.append(line).append(getEndOfLine());
		return builder.toString();
	}

}
