package io.onedev.k8shelper;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.SystemUtils;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;

public class CommandFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final String runAs;

	private final Map<String, String> envMap;

	private final List<RegistryLoginFacade> registryLogins;

	private final boolean useTTY;

	private final InterpreterFacade interpreter;

	public CommandFacade(@Nullable String image, @Nullable String runAs, List<RegistryLoginFacade> registryLogins,
						 Map<String, String> envMap, boolean useTTY, InterpreterFacade interpreter) {
		this.image = image;
		this.runAs = runAs;
		this.registryLogins = registryLogins;
		this.envMap = envMap;
		this.useTTY = useTTY;
		this.interpreter = interpreter;
	}

	public CommandFacade(@Nullable String image, @Nullable String runAs, List<RegistryLoginFacade> registryLogins,
		Map<String, String> envMap, boolean useTTY, String commands) {
		this(image, runAs, registryLogins, envMap, useTTY, new DefaultInterpreterFacade(commands));
	}

	@Nullable
	public String getImage() {
		return image;
	}

	@Nullable
	public String getRunAs() {
		return runAs;
	}

	public String getCommands() {
		return interpreter.getCommands();
	}

	public Map<String, String> getEnvMap() {
		return envMap;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isUseTTY() {
		return useTTY;
	}

	public InterpreterFacade getInterpreter() {
		return interpreter;
	}

	public void generatePauseCommand(File buildDir) {
		if (SystemUtils.IS_OS_WINDOWS) {
			FileUtils.writeFile(new File(buildDir, "pause.bat"), ""
					+ "@echo off\r\n"
					+ "if exist \"%ONEDEV_WORKDIR%\\..\\continue\" (\r\n"
					+ "  del \"%ONEDEV_WORKDIR%\\..\\continue\"\r\n"
					+ ")\r\n"
					+ "echo ##onedev[PauseExecution]\r\n"
					+ ":repeat\r\n"
					+ "if exist \"%ONEDEV_WORKDIR%\\..\\continue\" (\r\n"
					+ "  exit /b\r\n"
					+ ") else (\r\n"
					+ "  ping -n 2 127.0.0.1 > nul\r\n"
					+ "  goto :repeat\r\n"
					+ ")\r\n");
		} else { 
			FileUtils.writeFile(new File(buildDir, "pause.sh"), ""
					+ "rm -f $ONEDEV_WORKDIR/../continue\n"
					+ "echo '##onedev[PauseExecution]'\n"
					+ "while [ ! -f $ONEDEV_WORKDIR/../continue ]; do sleep 1; done\n");
		}
		FileUtils.writeFile(new File(buildDir, "pause"), interpreter.getPauseInvokeCommand());
	}
	
	public Commandline buildScriptCmdline() {
		return interpreter.getShellAccessor().buildScriptCmdline();
	}
	
	public String getExecutable() {
		return interpreter.getShellAccessor().getExecutable();
	}

	public String getScriptExtension() {
		return interpreter.getShellAccessor().getScriptExtension();
	}

	public String getEndOfLine() {
		return interpreter.getShellAccessor().getEndOfLine();
	}

	public String normalizeCommands(String commands) {
		return interpreter.getShellAccessor().normalizeCommands(commands);
	}

	public CommandFacade replacePlaceholders(File buildDir) {
		var image = KubernetesHelper.replacePlaceholders(this.image, buildDir);
		return new CommandFacade(image, runAs, registryLogins, envMap, useTTY, interpreter);
	}

}
