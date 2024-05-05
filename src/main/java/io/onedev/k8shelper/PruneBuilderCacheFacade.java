package io.onedev.k8shelper;

import javax.annotation.Nullable;

public class PruneBuilderCacheFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;
	private final String options;

	public PruneBuilderCacheFacade(@Nullable String options) {
		this.options = options;
	}

	@Nullable
	public String getOptions() {
		return options;
	}

}
