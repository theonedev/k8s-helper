package io.onedev.k8shelper;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkspaceSidecar {

	private static final Logger logger = LoggerFactory.getLogger(WorkspaceSidecar.class);

	public static void main(String[] args) {
		try {
			String serverUrl = KubernetesHelper.requireServerUrl();
			String workspaceToken = checkNotNull(System.getenv(WorkspaceHelper.ENV_WORKSPACE_TOKEN));
			WorkspaceHelper.sidecar(serverUrl, workspaceToken);
			System.exit(0);
		} catch (Throwable e) {
			KubernetesHelper.logFailure(logger, e);
			System.exit(1);
		}
	}

}
