package io.onedev.k8shelper;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;
import org.apache.commons.lang3.SystemUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

public class CommandFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;
	
	private final List<OsExecution> executions;

	private final String builtInRegistryAccessToken;
	
	private final boolean useTTY;
	
	public CommandFacade(List<OsExecution> executions, @Nullable String builtInRegistryAccessToken, boolean useTTY) {
		this.executions = executions;
		this.builtInRegistryAccessToken = builtInRegistryAccessToken;
		this.useTTY = useTTY;
	}

	public CommandFacade(@Nullable String image, @Nullable String builtInRegistryAccessToken, String commands, boolean useTTY) {
		this(Lists.newArrayList(new OsExecution(OsMatcher.ALL, image, commands)), builtInRegistryAccessToken, useTTY);
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

	@Nullable
	public String getBuiltInRegistryAccessToken() {
		return builtInRegistryAccessToken;
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
	
	public Commandline getScriptInterpreter() {
		if (SystemUtils.IS_OS_WINDOWS) 
			return new Commandline("cmd").addArgs("/c");
		else
			return new Commandline("sh");
	}
	
	public String[] getShell(boolean isWindows, @Nullable String workingDir) {
		if (workingDir != null) {
			if (isWindows)
				return new String[]{"cmd", "/c", String.format("cd '%s' && cmd", workingDir)};
			else
				return new String[]{"sh", "-c", String.format("cd '%s' && sh", workingDir)};
		} else if (isWindows) {
			return new String[]{"cmd"};
		} else {
			return new String[]{"sh"};
		}
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

	public String convertCommands(String commands) {
		return Joiner.on(getEndOfLine()).join(Splitter.on("\n").trimResults(CharMatcher.is('\r')).split(commands));
	}

}
