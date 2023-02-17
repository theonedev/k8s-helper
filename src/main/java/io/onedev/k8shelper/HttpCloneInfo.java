package io.onedev.k8shelper;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class HttpCloneInfo extends CloneInfo {
	
	private static final long serialVersionUID = 1L;

	private final String accessToken;
	
	public HttpCloneInfo(String cloneUrl, String accessToken) {
		super(cloneUrl);

		this.accessToken = accessToken;
	}

	@Override
	public void writeAuthData(File homeDir, Commandline git, boolean forContainer,
							  LineConsumer infoLogger, LineConsumer errorLogger) {
		// Use onedev specific authorization header as otherwise it will fail git operations
		// against other git servers in command step
		String extraHeader = KubernetesHelper.AUTHORIZATION + ": " + KubernetesHelper.BEARER + " " + accessToken;
		git.addArgs("config", "--global", "http.extraHeader", extraHeader);
		git.execute(infoLogger, errorLogger).checkReturnCode();
		git.clearArgs();
	}

	@Override
	public String toString() {
		return "http-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(accessToken.getBytes(StandardCharsets.UTF_8));
	}
	
}
