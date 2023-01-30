package io.onedev.k8shelper;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.Serializable;
import java.util.Base64;
import java.util.List;

import com.google.common.base.Splitter;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public abstract class CloneInfo implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	private final String cloneUrl;
	
	public CloneInfo(String cloneUrl) {
		this.cloneUrl = cloneUrl;
	}
	
	public String getCloneUrl() {
		return cloneUrl;
	}
	
	public abstract void writeAuthData(File homeDir, Commandline git, boolean forContainer,
									   LineConsumer infoLogger, LineConsumer errorLogger);
	
	public static CloneInfo fromString(String string) {
		List<String> fields = Splitter.on("-").splitToList(string);
		String cloneUrl = new String(Base64.getDecoder().decode(fields.get(1).getBytes(UTF_8)), UTF_8);
		if (fields.get(0).equals("default")) {
			String jobToken = new String(Base64.getDecoder().decode(fields.get(2).getBytes(UTF_8)), UTF_8);
			return new DefaultCloneInfo(cloneUrl, jobToken);
		} else if (fields.get(0).equals("http")) {
			String accessToken = new String(Base64.getDecoder().decode(fields.get(2).getBytes(UTF_8)), UTF_8);
			return new HttpCloneInfo(cloneUrl, accessToken);
		} else {
			String privateKey = new String(Base64.getDecoder().decode(fields.get(2).getBytes(UTF_8)), UTF_8);
			String knownHosts = new String(Base64.getDecoder().decode(fields.get(3).getBytes(UTF_8)), UTF_8);
			return new SshCloneInfo(cloneUrl, privateKey, knownHosts);
		}
	}
	
}
