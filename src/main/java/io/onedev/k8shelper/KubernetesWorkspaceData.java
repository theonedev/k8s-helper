package io.onedev.k8shelper;

import java.io.Serializable;
import java.util.List;

import org.jspecify.annotations.Nullable;

public class KubernetesWorkspaceData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String userName;

	private final String userEmail;

	private final CloneInfo cloneInfo;

	@Nullable
	private final String branch;

	private final String commitHash;

	private final boolean retrieveLfs;

	private final boolean retrieveSubmodules;

	private final List<CacheConfigFacade> cacheConfigs;

	private final List<UserDataFacade> userDatas;

	private final List<ConfigFileFacade> configFiles;

	private final ScriptConfig scriptConfig;

	public KubernetesWorkspaceData(String userName, String userEmail,
							CloneInfo cloneInfo, String commitHash, @Nullable String branch, 
							boolean retrieveLfs, boolean retrieveSubmodules,
							List<CacheConfigFacade> cacheConfigs,
							List<UserDataFacade> userDatas, List<ConfigFileFacade> configFiles, 
							ScriptConfig scriptConfig) {
		this.userName = userName;
		this.userEmail = userEmail;
		this.cloneInfo = cloneInfo;
		this.commitHash = commitHash;
		this.branch = branch;
		this.retrieveLfs = retrieveLfs;
		this.retrieveSubmodules = retrieveSubmodules;
		this.cacheConfigs = cacheConfigs;
		this.userDatas = userDatas;
		this.configFiles = configFiles;
		this.scriptConfig = scriptConfig;
	}

	public String getUserName() {
		return userName;
	}

	public String getUserEmail() {
		return userEmail;
	}

	public CloneInfo getCloneInfo() {
		return cloneInfo;
	}

	public String getCommitHash() {
		return commitHash;
	}

	@Nullable
	public String getBranch() {
		return branch;
	}

	public boolean isRetrieveLfs() {
		return retrieveLfs;
	}

	public boolean isRetrieveSubmodules() {
		return retrieveSubmodules;
	}

	public List<CacheConfigFacade> getCacheConfigs() {
		return cacheConfigs;
	}

	public List<UserDataFacade> getUserDatas() {
		return userDatas;
	}

	public List<ConfigFileFacade> getConfigFiles() {
		return configFiles;
	}

	public ScriptConfig getScriptConfig() {
		return scriptConfig;
	}

}
