package io.onedev.k8shelper;

import java.io.File;
import java.util.Map;

import javax.annotation.Nullable;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.command.Commandline;

public class CheckoutFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private final int cloneDepth;
	
	private final boolean withLfs;
	
	private final boolean withSubmodules;
	
	private final CloneInfo cloneInfo;
	
	private final String checkoutPath;
	
	public CheckoutFacade(int cloneDepth, boolean withLfs, boolean withSubmodules, 
			CloneInfo cloneInfo, @Nullable String checkoutPath) {
		this.cloneDepth = cloneDepth;
		this.withLfs = withLfs;
		this.withSubmodules = withSubmodules;
		this.cloneInfo = cloneInfo;
		this.checkoutPath = checkoutPath;
	}

	public boolean isWithLfs() {
		return withLfs;
	}

	public boolean isWithSubmodules() {
		return withSubmodules;
	}

	public int getCloneDepth() {
		return cloneDepth;
	}

	public CloneInfo getCloneInfo() {
		return cloneInfo;
	}

	public String getCheckoutPath() {
		return checkoutPath;
	}

	private File getCachedRelative(File cacheHome, Map<CacheInstance, String> cacheAllocations, String relativePath) {
		for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
			if (!new File(entry.getValue()).isAbsolute()) {
				String relativeToCache = PathUtils.parseRelative(relativePath, entry.getValue());
				if (relativeToCache != null)
					return new File(entry.getKey().getDirectory(cacheHome), relativeToCache);
			}
		}
		return null;
	}
	
	public void setupWorkingDir(Commandline git, File workspace, File cacheHome, Map<CacheInstance, String> cacheAllocations) {
		if (getCheckoutPath() != null) {
			File cached = getCachedRelative(cacheHome, cacheAllocations, getCheckoutPath());
			if (cached != null)
				git.workingDir(cached);
			else
				git.workingDir(new File(workspace, getCheckoutPath()));
			FileUtils.createDir(git.workingDir());
		} else {
			git.workingDir(workspace);
		}
		
	}
}
