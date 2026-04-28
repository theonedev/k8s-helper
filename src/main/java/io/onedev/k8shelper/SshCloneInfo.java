package io.onedev.k8shelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
	public void setupGitAuth(Commandline git, File resourceDir, String runtimeResourceDirPath,
							  LineConsumer stdoutLogger, LineConsumer stderrLogger) {
		var presetArgs = new ArrayList<String>(git.args());

		File sshDir = new File(resourceDir, ".ssh");
		FileUtils.createDir(sshDir);
		
		File privateKeyFile = new File(sshDir, "id_rsa");
		if (privateKeyFile.exists() && !SystemUtils.IS_OS_WINDOWS) {
			Commandline chmod = new Commandline("chmod");
			chmod.workingDir(sshDir).addArgs("600", "id_rsa");
			chmod.execute(stdoutLogger, stderrLogger).checkReturnCode();
		}
		
		FileUtils.writeFile(privateKeyFile, privateKey);
		
		File knownHostsFile = new File(sshDir, "known_hosts");
		FileUtils.writeFile(knownHostsFile, knownHosts);
		if (!SystemUtils.IS_OS_WINDOWS) {
			Commandline chmod = new Commandline("chmod");
			chmod.workingDir(sshDir).addArgs("400", "id_rsa");
			chmod.execute(stdoutLogger, stderrLogger).checkReturnCode();
		}
		
		var runtimePrivateKeyFilePath = runtimeResourceDirPath + "/.ssh/" + privateKeyFile.getName();
		var runtimeKnownHostsFilePath = runtimeResourceDirPath + "/.ssh/" + knownHostsFile.getName();
		var privateKeyFilePath = privateKeyFile.getAbsolutePath();
		var knownHostsFilePath = knownHostsFile.getAbsolutePath();

		runtimePrivateKeyFilePath = runtimePrivateKeyFilePath.replace('\\', '/');
		runtimeKnownHostsFilePath = runtimeKnownHostsFilePath.replace('\\', '/'); 
		privateKeyFilePath = privateKeyFilePath.replace('\\', '/');
		knownHostsFilePath = knownHostsFilePath.replace('\\', '/');

		var sshCommand = "ssh -i \"" + runtimePrivateKeyFilePath + "\" -o UserKnownHostsFile=\"" + runtimeKnownHostsFilePath + "\" -F /dev/null";
		git.args("-c", "safe.directory=*", "config", "core.sshCommand", sshCommand);
		git.execute(stdoutLogger, stderrLogger).checkReturnCode();

		git.args(presetArgs);
		git.addArgs("-c", "core.sshCommand=ssh -i \"" + privateKeyFilePath + "\" -o UserKnownHostsFile=\"" + knownHostsFilePath + "\" -F /dev/null");
	}

	@Override
	public String toString() {
		return "ssh-" 
				+ Base64.getEncoder().encodeToString(getCloneUrl().getBytes(StandardCharsets.UTF_8)) 
				+ "-" + Base64.getEncoder().encodeToString(privateKey.getBytes(StandardCharsets.UTF_8))
				+ "-" + Base64.getEncoder().encodeToString(knownHosts.getBytes(StandardCharsets.UTF_8));
	}
	
}
