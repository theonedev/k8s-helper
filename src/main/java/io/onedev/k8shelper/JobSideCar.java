package io.onedev.k8shelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public class JobSideCar {

	private static final Logger logger = LoggerFactory.getLogger(JobSideCar.class);

	public static void main(String[] args) {
		try {
			String serverUrl = KubernetesHelper.requireServerUrl();
			String jobToken = checkNotNull(System.getenv(JobHelper.ENV_JOB_TOKEN));

			JobHelper.logEndMessage(logger);
			if (JobHelper.sidecar(serverUrl, jobToken))
				System.exit(0);
			else
				System.exit(1);
		} catch (Exception e) {
			KubernetesHelper.logFailure(logger, e);
			JobHelper.logEndMessage(logger);
			System.exit(1);
		}
	}

}
