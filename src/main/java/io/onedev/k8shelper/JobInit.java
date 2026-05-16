package io.onedev.k8shelper;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobInit {

	private static final Logger logger = LoggerFactory.getLogger(JobInit.class);
	
	public static void main(String[] args) {
		try {
			String serverUrl = KubernetesHelper.requireServerUrl();
			String jobToken = checkNotNull(System.getenv(JobHelper.ENV_JOB_TOKEN));

			JobHelper.init(serverUrl, jobToken);
			JobHelper.logEndMessage(logger);
			System.exit(0);
		} catch (Throwable e) {
			KubernetesHelper.logFailure(logger, e);
			JobHelper.logEndMessage(logger);
			System.exit(1);
		}
	}
	
}
