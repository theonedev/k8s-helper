package io.onedev.k8shelper;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SystemUtils;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;

public class CommandFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;
	
	private final List<OsExecution> executions;
	
	private final boolean useTTY;
	
	public CommandFacade(List<OsExecution> executions, boolean useTTY) {
		this.executions = executions;
		this.useTTY = useTTY;
	}

	public CommandFacade(@Nullable String image, List<String> commands, boolean useTTY) {
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
	
	public void generatePauseCommand(File buildHome) {
		if (SystemUtils.IS_OS_WINDOWS) {
			FileUtils.writeFile(new File(buildHome, "pause.bat"), ""
					+ "@echo off\r\n"
					+ "if exist \"%ONEDEV_WORKSPACE%\\..\\continue\" (\r\n"
					+ "  del \"%ONEDEV_WORKSPACE%\\..\\continue\"\r\n"
					+ ")\r\n"
					+ "echo ##onedev[PauseExecution]\r\n"
					+ ":repeat\r\n"
					+ "if exist \"%ONEDEV_WORKSPACE%\\..\\continue\" (\r\n"
					+ "  exit /b\r\n"
					+ ") else (\r\n"
					+ "  ping -n 2 127.0.0.1 > nul\r\n"
					+ "  goto :repeat\r\n"
					+ ")\r\n");			
		} else { 
			FileUtils.writeFile(new File(buildHome, "pause.sh"), ""
					+ "rm -f $ONEDEV_WORKSPACE/../continue\n"
					+ "echo '##onedev[PauseExecution]'\n"
					+ "while [ ! -f $ONEDEV_WORKSPACE/../continue ]; do sleep 1; done\n");
		}
		FileUtils.writeFile(new File(buildHome, "pause"), getPauseInvokeCommand());
	}
	
	protected String getPauseInvokeCommand() {
		if (SystemUtils.IS_OS_WINDOWS) 
			return "cmd /c %ONEDEV_WORKSPACE%\\..\\pause.bat";
		else 
			return "sh $ONEDEV_WORKSPACE/../pause.sh";
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
