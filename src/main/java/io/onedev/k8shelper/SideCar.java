package io.onedev.k8shelper;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.StringUtils;

public class SideCar {

	private static final Logger logger = LoggerFactory.getLogger(SideCar.class);
	
	public static void main(String[] args) {
		try {
			String serverUrl = System.getenv(KubernetesHelper.ENV_SERVER_URL);
			if (serverUrl == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_SERVER_URL + "' is not defined");
			serverUrl = StringUtils.stripEnd(serverUrl, "/");
			String jobId = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_ID));
			if (jobId == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_JOB_ID + "' is not defined");
			
			File workspace = KubernetesHelper.getWorkspace();
			
			KubernetesHelper.sidecar(serverUrl, jobId, workspace);
		} catch (Exception e) {
			logger.error("Error executing sidecard logic", e);
			System.exit(1);
		}
	}
	
}
