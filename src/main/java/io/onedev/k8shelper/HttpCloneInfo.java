package io.onedev.k8shelper;

import java.io.File;
import java.util.Base64;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class HttpCloneInfo extends CloneInfo {
	
	private static final long serialVersionUID = 1L;

	private final String userName;
	
	private final String password;
	
	public HttpCloneInfo(String cloneUrl, String userName, String password) {
		super(cloneUrl);
		
		this.userName = userName;
		this.password = password;
	}

	@Override
	public void writeAuthData(File homeDir, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		git.clearArgs();
		String base64 = Base64.getEncoder().encodeToString((userName + ":" + password).getBytes());
		String extraHeader = "Authorization: Basic " + base64;
		git.addArgs("config", "--global", "http.extraHeader", extraHeader);
		git.execute(infoLogger, errorLogger).checkReturnCode();
	}

}
