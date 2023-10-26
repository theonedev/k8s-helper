package io.onedev.k8shelper;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;

public class ServiceFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String name;
	
	private final String image;
	
	private final String arguments;
	
	private final Map<String, String> envs;
	
	private final String readinessCheckCommand;

	private final String builtInRegistryAccessToken;

	public ServiceFacade(String name, String image, @Nullable String arguments, Map<String, String> envs,
						 String readinessCheckCommand, @Nullable String builtInRegistryAccessToken) {
		this.name = name;
		this.image = image;
		this.arguments = arguments;
		this.envs = envs;
		this.readinessCheckCommand = readinessCheckCommand;
		this.builtInRegistryAccessToken = builtInRegistryAccessToken;
	}

	public String getName() {
		return name;
	}

	public String getImage() {
		return image;
	}

	@Nullable
	public String getArguments() {
		return arguments;
	}

	public Map<String, String> getEnvs() {
		return envs;
	}

	public String getReadinessCheckCommand() {
		return readinessCheckCommand;
	}

	@Nullable
	public String getBuiltInRegistryAccessToken() {
		return builtInRegistryAccessToken;
	}
}
