package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class HttpCloneInfo extends CloneInfo {
	
	private static final long serialVersionUID = 1L;

	private final String accessToken;
	
	public HttpCloneInfo(String cloneUrl, String accessToken) {
		super(cloneUrl);

		this.accessToken = accessToken;
	}

	@Override
	public List<String> setupGitAuth(Commandline git, File resourceDir, String runtimeResourceDirPath,
							  LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		// Use onedev specific authorization header as otherwise it will fail git operations
		// against other git servers in command step
		String extraHeader = KubernetesHelper.AUTHORIZATION + ": " + KubernetesHelper.BEARER + " " + accessToken;
		git.arguments("config", "http.extraHeader", extraHeader);
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();
		return List.of("-c", "http.extraHeader=" + extraHeader);
	}

	@Override
	public String toString() {
		return "http-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(accessToken.getBytes(StandardCharsets.UTF_8));
	}
	
}
