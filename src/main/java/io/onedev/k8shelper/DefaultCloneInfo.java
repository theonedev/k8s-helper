package io.onedev.k8shelper;

import java.io.File;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class DefaultCloneInfo extends CloneInfo {
	
	private static final long serialVersionUID = 1L;

	private final String jobToken;
	
	public DefaultCloneInfo(String cloneUrl, String jobToken) {
		super(cloneUrl);
		this.jobToken = jobToken;
	}

	@Override
	public void writeAuthData(File homeDir, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		git.clearArgs();
		String extraHeader = KubernetesHelper.JOB_TOKEN_HTTP_HEADER + ": " + jobToken;
		git.addArgs("config", "--global", "http.extraHeader", extraHeader);
		git.execute(infoLogger, errorLogger).checkReturnCode();
	}

}
