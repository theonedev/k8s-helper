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

	private final List<CacheConfigFacade> cacheConfigs;

	private final List<UserDataFacade> userDatas;

	private final List<ConfigFileFacade> configFiles;

	@Nullable
	private final SetupScriptConfig setupScriptConfig;

	public KubernetesWorkspaceData(String userName, String userEmail,
							CloneInfo cloneInfo, String commitHash, @Nullable String branch, 
							boolean retrieveLfs, List<CacheConfigFacade> cacheConfigs, 
							List<UserDataFacade> userDatas, List<ConfigFileFacade> configFiles, 
							@Nullable SetupScriptConfig setupScriptConfig) {
		this.userName = userName;
		this.userEmail = userEmail;
		this.cloneInfo = cloneInfo;
		this.commitHash = commitHash;
		this.branch = branch;
		this.retrieveLfs = retrieveLfs;
		this.cacheConfigs = cacheConfigs;
		this.userDatas = userDatas;
		this.configFiles = configFiles;
		this.setupScriptConfig = setupScriptConfig;
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

	public List<CacheConfigFacade> getCacheConfigs() {
		return cacheConfigs;
	}

	public List<UserDataFacade> getUserDatas() {
		return userDatas;
	}

	public List<ConfigFileFacade> getConfigFiles() {
		return configFiles;
	}

	@Nullable
	public SetupScriptConfig getSetupScriptConfig() {
		return setupScriptConfig;
	}

}
