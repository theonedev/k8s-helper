package io.onedev.k8shelper;

import java.util.List;

public class RunImagetoolsFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String arguments;

	private final List<RegistryLoginFacade> registryLogins;

	public RunImagetoolsFacade(String arguments, List<RegistryLoginFacade> registryLogins) {
		this.arguments = arguments;
		this.registryLogins = registryLogins;
	}

	public String getArguments() {
		return arguments;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}
}
