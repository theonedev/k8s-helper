package io.onedev.k8shelper;

import javax.annotation.Nullable;

public class BuildImageFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String buildPath;
	
	private final String dockerfile;
	
	private final String tags;

	private final String moreOptions;

	public BuildImageFacade(@Nullable String buildPath, @Nullable String dockerFile, String tags,
							@Nullable String moreOptions) {
		this.buildPath = buildPath;
		this.dockerfile = dockerFile;
		this.tags = tags;
		this.moreOptions = moreOptions;
	}

	@Nullable
	public String getBuildPath() {
		return buildPath;
	}

	@Nullable
	public String getDockerfile() {
		return dockerfile;
	}

	public String getTags() {
		return tags;
	}

	@Nullable
	public String getMoreOptions() {
		return moreOptions;
	}

}
