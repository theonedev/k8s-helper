package io.onedev.k8shelper;

import com.google.common.collect.Lists;
import io.onedev.commons.utils.ExplicitException;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

public class RunContainerFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final List<OsContainer> containers;
	
	private final boolean useTTY;

	public RunContainerFacade(List<OsContainer> containers, boolean useTTY) {
		this.containers = containers;
		this.useTTY = useTTY;
	}

	public RunContainerFacade(String image, @Nullable String opts, @Nullable String args,
							  Map<String, String> envMap, @Nullable String workingDir,
							  Map<String, String> volumeMounts, boolean useTTY) {
		this(Lists.newArrayList(new OsContainer(OsMatcher.ALL, image, opts, args, envMap,
				workingDir, volumeMounts)), useTTY);
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
