package io.onedev.k8shelper;

import javax.annotation.Nullable;

public class BuildImageFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String buildPath;
	
	private final String dockerfile;
	
	private final String tags;
	
	private final boolean publish;
	
	public BuildImageFacade(@Nullable String buildPath, @Nullable String dockerFile, String tags, boolean publish) {
		this.buildPath = buildPath;
		this.dockerfile = dockerFile;
		this.tags = tags;
		this.publish = publish;
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

	public boolean isPublish() {
		return publish;
	}

}
