package io.onedev.k8shelper;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkspaceInit {

	private static final Logger logger = LoggerFactory.getLogger(WorkspaceInit.class);

	public static void main(String[] args) {
		try {
			String serverUrl = KubernetesHelper.requireServerUrl();
			String workspaceToken = checkNotNull(System.getenv(WorkspaceHelper.ENV_WORKSPACE_TOKEN));
			String runAs = checkNotNull(System.getenv(WorkspaceHelper.ENV_RUNAS));
			WorkspaceHelper.init(serverUrl, workspaceToken, runAs);
			System.exit(0);
		} catch (Throwable e) {
			KubernetesHelper.logFailure(logger, e);
			System.exit(1);
		}
	}

}
