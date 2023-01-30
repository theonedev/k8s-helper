package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.ws.rs.core.HttpHeaders;

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
	public void writeAuthData(File homeDir, Commandline git, boolean forContainer,
							  LineConsumer infoLogger, LineConsumer errorLogger) {
		String extraHeader = HttpHeaders.AUTHORIZATION + ": " + KubernetesHelper.BEARER + " " + jobToken;
		git.addArgs("config", "--global", "http.extraHeader", extraHeader);
		git.execute(infoLogger, errorLogger).checkReturnCode();
		git.clearArgs();
	}

	@Override
	public String toString() {
		return "default-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(jobToken.getBytes(StandardCharsets.UTF_8));
	}
	
}
