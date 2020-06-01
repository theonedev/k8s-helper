package io.onedev.k8shelper;

import java.io.File;

import org.apache.commons.lang3.SystemUtils;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class SshCloneInfo extends CloneInfo {

	private static final long serialVersionUID = 1L;

	private final String privateKey;
	
	private final String knownHosts;
	
	public SshCloneInfo(String cloneUrl, String privateKey, String knownHosts) {
		super(cloneUrl);
		
		this.privateKey = privateKey;
		this.knownHosts = knownHosts;
	}

	@Override
	public void writeAuthData(File homeDir, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		File sshDir = new File(homeDir, ".ssh");
		FileUtils.createDir(sshDir);
		FileUtils.writeFile(new File(sshDir, "id_rsa"), privateKey);
		if (!SystemUtils.IS_OS_WINDOWS) {
			Commandline chmod = new Commandline("chmod");
			chmod.workingDir(sshDir).addArgs("400", "id_rsa");
			chmod.execute(infoLogger, errorLogger).checkReturnCode();
		}
		FileUtils.writeFile(new File(sshDir, "known_hosts"), knownHosts);
	}

}
