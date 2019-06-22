package io.onedev.k8shelper;

import java.io.File;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.StringUtils;

public class SideCar {

	public static void main(String[] args) {
		String serverUrl = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_SERVER_URL));
		serverUrl = StringUtils.stripEnd(serverUrl, "/");
		String jobId = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_ID));
		File workspace = KubernetesHelper.getWorkspace();
		
		KubernetesHelper.sidecar(serverUrl, jobId, workspace);
	}
	
}
