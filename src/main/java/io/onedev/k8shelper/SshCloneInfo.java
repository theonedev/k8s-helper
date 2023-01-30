package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
	public void writeAuthData(File homeDir, Commandline git, boolean forContainer,
							  LineConsumer infoLogger, LineConsumer errorLogger) {
		File sshDir = new File(homeDir, ".ssh");
		FileUtils.createDir(sshDir);
		
		File privateKeyFile = new File(sshDir, "id_rsa");
		if (privateKeyFile.exists() && !SystemUtils.IS_OS_WINDOWS) {
			Commandline chmod = new Commandline("chmod");
			chmod.workingDir(sshDir).addArgs("600", "id_rsa");
			chmod.execute(infoLogger, errorLogger).checkReturnCode();
		}
		
		FileUtils.writeFile(privateKeyFile, privateKey);
		
		File knownHostsFile = new File(sshDir, "known_hosts");
		FileUtils.writeFile(knownHostsFile, knownHosts);
		if (!SystemUtils.IS_OS_WINDOWS) {
			Commandline chmod = new Commandline("chmod");
			chmod.workingDir(sshDir).addArgs("400", "id_rsa");
			chmod.execute(infoLogger, errorLogger).checkReturnCode();

			String sshCommand = "ssh -i \"" + privateKeyFile.getAbsolutePath() + "\" -o UserKnownHostsFile=\"" + knownHostsFile.getAbsolutePath() + "\" -F /dev/null";
			if (!forContainer) {
				git.addArgs("config", "--global", "core.sshCommand", sshCommand);
				git.execute(infoLogger, errorLogger).checkReturnCode();
				git.clearArgs();
			}
			git.addArgs("-c", "core.sshCommand=" + sshCommand);
		}
	}

	@Override
	public String toString() {
		return "ssh-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(privateKey.getBytes(StandardCharsets.UTF_8))
				+ "-" + Base64.getEncoder().encodeToString(knownHosts.getBytes(StandardCharsets.UTF_8));
	}
	
}
