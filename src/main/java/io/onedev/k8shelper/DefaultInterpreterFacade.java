package io.onedev.k8shelper;

import org.apache.commons.lang3.SystemUtils;

public class DefaultInterpreterFacade extends InterpreterFacade {

	private static final long serialVersionUID = 1L;

	public DefaultInterpreterFacade(String commands) {
		super(commands);
	}

	@Override
	public ShellAccessor getShellAccessor() {
		return new DefaultShellAccessor();
	}

	@Override
	protected String getPauseInvokeCommand() {
		if (SystemUtils.IS_OS_WINDOWS)
			return "cmd /c %ONEDEV_WORKDIR%\\..\\pause.bat";
		else
			return "sh $ONEDEV_WORKDIR/../pause.sh";
	}

}
