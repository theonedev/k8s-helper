package io.onedev.k8shelper;

import java.util.List;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SystemUtils;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;

public class CommandExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final List<OsExecution> executions;
	
	private final boolean useTTY;
	
	public CommandExecutable(List<OsExecution> executions, boolean useTTY) {
		this.executions = executions;
		this.useTTY = useTTY;
	}

	public CommandExecutable(@Nullable String image, List<String> commands, boolean useTTY) {
		this(Lists.newArrayList(new OsExecution(OsMatcher.ALL, image, commands)), useTTY);
	}
	
	public List<OsExecution> getExecutions() {
		return executions;
	}
	
	public OsExecution getExecution(OsInfo osInfo) {
		for (OsExecution execution: getExecutions()) {
			if (execution.getOsMatcher().match(osInfo))
				return execution;
		}
		throw new ExplicitException("This step is not applicable for os: " + osInfo);
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
