package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.ws.rs.core.HttpHeaders;

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
	public void writeAuthData(File homeDir, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		git.clearArgs();
		String extraHeader = HttpHeaders.AUTHORIZATION + ": " + KubernetesHelper.BEARER + " " + accessToken;
		git.addArgs("config", "--global", "http.extraHeader", extraHeader);
		git.execute(infoLogger, errorLogger).checkReturnCode();
	}

	@Override
	public String toString() {
		return "http-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(accessToken.getBytes(StandardCharsets.UTF_8));
	}
	
}
