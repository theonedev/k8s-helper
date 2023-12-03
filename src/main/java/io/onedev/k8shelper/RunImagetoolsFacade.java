package io.onedev.k8shelper;

import javax.annotation.Nullable;

public class RunImagetoolsFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String arguments;

	private final String builtInRegistryAccessToken;

	public RunImagetoolsFacade(String arguments, @Nullable String builtInRegistryAccessToken) {
		this.arguments = arguments;
		this.builtInRegistryAccessToken = builtInRegistryAccessToken;
	}

	public String getArguments() {
		return arguments;
	}

	@Nullable
	public String getBuiltInRegistryAccessToken() {
		return builtInRegistryAccessToken;
	}

}
