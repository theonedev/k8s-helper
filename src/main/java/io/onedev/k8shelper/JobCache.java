package io.onedev.k8shelper;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;

public abstract class JobCache {

	private static final Object cacheHomeCreationLock = new Object();
	
	private final File home;
	
	private Map<CacheInstance, String> allocations;
	
	public JobCache(File home) {
		this.home = home;
	}
	
	public void init(boolean forShellExecutor) {
		Map<CacheInstance, Date> instances = new HashMap<>();
		
		FileFilter filter = new FileFilter() {

			@Override
			public boolean accept(File pathname) {
				return pathname.isDirectory();
			}
			
		}; 
		if (home.exists()) {
			for (File keyDir: home.listFiles(filter)) {
				for (File instanceDir: keyDir.listFiles(filter)) {
					instances.put(
							new CacheInstance(keyDir.getName(), instanceDir.getName()),
							new Date(instanceDir.lastModified()));
				}
			}
		}
		
		allocations = allocate(new CacheAllocationRequest(new Date(), instances));
		if (!allocations.isEmpty() && !home.exists()) synchronized (cacheHomeCreationLock) {
			FileUtils.createDir(home);
		}
		 
		for (Iterator<Map.Entry<CacheInstance, String>> it = allocations.entrySet().iterator(); it.hasNext();) {
			Map.Entry<CacheInstance, String> entry = it.next();
			File cacheDirectory = entry.getKey().getDirectory(home);
			if (entry.getValue() != null) {
				if (PathUtils.isCurrent(entry.getValue()))
					throw new ExplicitException("Invalid cache path: " + entry.getValue());
				else if (forShellExecutor && new File(entry.getValue()).isAbsolute())
					throw new ExplicitException("Shell executor does not support absolute cache path: " + entry.getValue());
				
				if (!cacheDirectory.exists()) 
					FileUtils.createDir(cacheDirectory);
				File tempFile = null;
				try {
					tempFile = File.createTempFile("update-cache-last-modified", null, cacheDirectory);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (tempFile != null)
						tempFile.delete();
				}
			} else {
				if (cacheDirectory.exists()) {
					delete(cacheDirectory);
				}
				it.remove();
			}
		}
	}
	
	public void installSymbolinks(File workspace) {
		for (Map.Entry<CacheInstance, String> entry: allocations.entrySet()) {
			if (!new File(entry.getValue()).isAbsolute()) {
				File target = entry.getKey().getDirectory(home);
				File link = new File(workspace, entry.getValue());
				if (link.exists())
					FileUtils.deleteDir(link);
				else
					FileUtils.createDir(link.getParentFile());
			    try {
					Files.createSymbolicLink(link.toPath(), target.toPath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}		
			}
		}
	}
	
	public void uninstallSymbolinks(File workspace) {
		for (Map.Entry<CacheInstance, String> entry: allocations.entrySet()) {
			if (!new File(entry.getValue()).isAbsolute()) 
				FileUtils.deleteDir(new File(workspace, entry.getValue()));
		}
	}

	public Map<CacheInstance, String> getAllocations() {
		return Preconditions.checkNotNull(allocations, "Cache not setup yet");
	}

	protected abstract Map<CacheInstance, String> allocate(CacheAllocationRequest request);
	
	protected abstract void delete(File cacheDir);
	
}
