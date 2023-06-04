package io.onedev.k8shelper;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExplicitException;

public class RunContainerFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final List<OsContainer> containers;
	
	private final boolean useTTY;

	private final boolean kaniko;
	
	public RunContainerFacade(List<OsContainer> containers, boolean useTTY, boolean kaniko) {
		this.containers = containers;
		this.useTTY = useTTY;
		this.kaniko = kaniko;
	}

	public RunContainerFacade(String image, @Nullable String args, Map<String, String> envMap, 
			@Nullable String workingDir, Map<String, String> volumeMounts, boolean useTTY, boolean kaniko) {
		this(Lists.newArrayList(new OsContainer(OsMatcher.ALL, image, args, envMap, workingDir, volumeMounts)), useTTY, kaniko);
	}
	
	public List<OsContainer> getContainers() {
		return containers;
	}
	
	public OsContainer getContainer(OsInfo osInfo) {
		for (OsContainer container: getContainers()) {
			if (container.getOsMatcher().match(osInfo))
				return container;
		}
		throw new ExplicitException("This step is not applicable for os: " + osInfo);
	}
	
	public boolean isUseTTY() {
		return useTTY;
	}

	public boolean isKaniko() {
		return kaniko;
	}
}
