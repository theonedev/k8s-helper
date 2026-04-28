package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;

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
	public void setupGitAuth(Commandline git, File resourceDir, String runtimeResourceDirPath,
							  LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<String>(git.args());
		// Use onedev specific authorization header as otherwise it will fail git operations
		// against other git servers in command step
		String extraHeader = KubernetesHelper.AUTHORIZATION + ": " + KubernetesHelper.BEARER + " " + jobToken;
		git.args("-c", "safe.directory=*", "config", "http.extraHeader", extraHeader);
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();
		git.args(presetArgs);
		git.addArgs("-c", "http.extraHeader=" + extraHeader);
	}

	@Override
	public String toString() {
		return "default-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(jobToken.getBytes(StandardCharsets.UTF_8));
	}
	
}
