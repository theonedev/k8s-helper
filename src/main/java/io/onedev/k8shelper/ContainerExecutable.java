package io.onedev.k8shelper;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExplicitException;

public class ContainerExecutable extends LeafExecutable {

	private static final long serialVersionUID = 1L;

	private final List<OsContainer> containers;
	
	private final boolean useTTY;
	
	public ContainerExecutable(List<OsContainer> containers, boolean useTTY) {
		this.containers = containers;
		this.useTTY = useTTY;
	}

	public ContainerExecutable(String image, @Nullable String args, Map<String, String> envMap, 
			@Nullable String workingDir, boolean useTTY) {
		this(Lists.newArrayList(new OsContainer(OsMatcher.ALL, image, args, envMap, workingDir)), useTTY);
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
	
}
