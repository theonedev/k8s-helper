package io.onedev.k8shelper;

import javax.annotation.Nullable;

public class BuildImageFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final String buildPath;
	
	private final String dockerfile;
	
	private final String tags;

	private final boolean publish;

	private final boolean removeDanglingImages;

	private final String builtInRegistryAccessToken;

	private final String moreOptions;

	public BuildImageFacade(@Nullable String buildPath, @Nullable String dockerFile, String tags,
							boolean publish, boolean removeDanglingImages,
							@Nullable String builtInRegistryAccessToken, @Nullable String moreOptions) {
		this.buildPath = buildPath;
		this.dockerfile = dockerFile;
		this.tags = tags;
		this.publish = publish;
		this.removeDanglingImages = removeDanglingImages;
		this.builtInRegistryAccessToken = builtInRegistryAccessToken;
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

	public boolean isPublish() {
		return publish;
	}

	public boolean isRemoveDanglingImages() {
		return removeDanglingImages;
	}

	@Nullable
	public String getBuiltInRegistryAccessToken() {
		return builtInRegistryAccessToken;
	}

	@Nullable
	public String getMoreOptions() {
		return moreOptions;
	}

}
