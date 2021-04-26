package io.onedev.k8shelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.ExplicitException;

public class RunServerStep {

	private static final Logger logger = LoggerFactory.getLogger(RunServerStep.class);
	
	public static void main(String[] args) {
		int exitCode = 0;
		try {
			String serverUrl = System.getenv(KubernetesHelper.ENV_SERVER_URL);
			if (serverUrl == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_SERVER_URL + "' is not defined");
			String jobToken = Preconditions.checkNotNull(System.getenv(KubernetesHelper.ENV_JOB_TOKEN));
			if (jobToken == null)
				throw new RuntimeException("Environment '" + KubernetesHelper.ENV_JOB_TOKEN + "' is not defined");

			if (args.length < 3)
				throw new ExplicitException("Insufficient args to run server step");
			
			KubernetesHelper.runServerStep(serverUrl, jobToken, args[0], args[1], args[2]);
		} catch (Exception e) {
			logger.error("Error executing step", e);
			exitCode = 1;
		} finally {
			logger.info(KubernetesHelper.LOG_END_MESSAGE);
			System.exit(exitCode);
		}
	}
	
}
