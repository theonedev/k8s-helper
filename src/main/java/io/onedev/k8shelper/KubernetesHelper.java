package io.onedev.k8shelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class KubernetesHelper {

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
	
	public static final String JOB_TOKEN_HTTP_HEADER = "X-ONEDEV-JOB-TOKEN";
	
	public static final String JOB_FINISH_LINE = "--ONEDEV-JOB-HAS-NOW-FINISHED--";
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
	
	public static File getWorkspace() {
		if (isWindows()) 
			return new File("C:\\onedev-workspace");
		else 
			return new File("/onedev-workspace");
	}
	
	public static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("windows");
	}
	
	private static LineConsumer newDebugLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}
			
		};
	}
	
	private static LineConsumer newErrorLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(line);
			}
			
		};
	}
	
	private static void generateCommandScript(File workspace, List<String> commands) {
		try {
			FileUtils.createDir(new File(workspace, ".onedev"));
			if (isWindows()) {
				File scriptFile = new File(workspace, ".onedev\\job-commands.bat");
				FileUtils.writeLines(scriptFile, commands, "\r\n");
				File wrapperScriptFile = new File(workspace, ".onedev\\job-commands-wrapper.bat");
				List<String> wrapperScriptContent = Lists.newArrayList(
						"@echo off",
						"cmd /c .onedev\\job-commands.bat>.onedev\\job-stdout 2>.onedev\\job-stderr", 
						"set last_exit_code=%errorlevel%",
						"echo " + JOB_FINISH_LINE + ">>.onedev\\job-stdout", 
						"echo " + JOB_FINISH_LINE + ">>.onedev\\job-stderr", 
						"exit %last_exit_code%");
				FileUtils.writeLines(wrapperScriptFile, wrapperScriptContent, "\r\n");
			} else {
				File scriptFile = new File(workspace, ".onedev/job-commands.sh");
				FileUtils.writeLines(scriptFile, commands, "\n");
				File wrapperScriptFile = new File(workspace, ".onedev/job-commands-wrapper.sh");
				List<String> wrapperScriptContent = Lists.newArrayList(
						"sh .onedev/job-commands.sh>.onedev/job-stdout 2>.onedev/job-stderr", 
						"lastExitCode=\"$?\"", 
						"echo " + JOB_FINISH_LINE + ">>.onedev/job-stdout",
						"echo " + JOB_FINISH_LINE + ">>.onedev/job-stderr",
						"exit $lastExitCode"
						);
				FileUtils.writeLines(wrapperScriptFile, wrapperScriptContent, "\n");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void init(String serverUrl, String jobToken, File workspace, boolean test) {
		Client client = ClientBuilder.newClient();
		try {
			if (test) {
				logger.info("Testing server connectivity with '{}'...", serverUrl);
				WebTarget target = client.target(serverUrl).path("rest/k8s/test");
				Invocation.Builder builder =  target.request(MediaType.APPLICATION_JSON);
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				checkStatus(builder.get());
				generateCommandScript(workspace, Lists.newArrayList("echo hello from container"));
			} else {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request(MediaType.APPLICATION_JSON);
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				
				logger.debug("Retrieving job context from '{}'...", serverUrl);
				
				Map<String, Object> jobContext;
				Response response = checkStatus(builder.get());
				try {
					String json = response.readEntity(String.class);
					jobContext = new ObjectMapper().readValue(json, Map.class);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					response.close();
				}
				
				boolean cloneSource = (boolean) jobContext.get("cloneSource");
				if (cloneSource) {
					String projectName = (String) jobContext.get("projectName");
					String commitHash = (String) jobContext.get("commitHash");
					
					String projectUrl = serverUrl + "/projects/" + projectName;
					logger.info("Cloning source code from '{}'...", projectUrl);
					
					Commandline git = new Commandline("git");
					git.addArgs("init", ".");
					git.workingDir(workspace);
					git.execute(newDebugLogger(), newErrorLogger()).checkReturnCode();
					
					git.clearArgs();
					String extraHeader = KubernetesHelper.JOB_TOKEN_HTTP_HEADER + ": " + jobToken;
					git.addArgs("-c", "http.extraHeader=" + extraHeader, "fetch", projectUrl, "--force", "--quiet", 
							/*"--depth=1",*/ commitHash);
					git.workingDir(workspace);
					git.execute(newDebugLogger(), newErrorLogger()).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("checkout", "--quiet", commitHash);
					git.workingDir(workspace);
					git.execute(newDebugLogger(), newErrorLogger()).checkReturnCode();
				}	
				
				List<String> commands = (List<String>) ((List<?>)jobContext.get("commands")).get(1);
				
				generateCommandScript(workspace, commands);
				
				logger.info("Downloading job dependencies from '{}'...", serverUrl);
				
				target = client.target(serverUrl).path("rest/k8s/download-dependencies");
				builder =  target.request(MediaType.APPLICATION_OCTET_STREAM);
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				response = checkStatus(builder.get());
				try {
					InputStream is = response.readEntity(InputStream.class);
					try {
						TarUtils.untar(is, workspace);
					} finally {
						try {
							is.close();
						} catch (IOException e) {
						}
					}
				} finally {
					response.close();
				}
				logger.info("Job workspace initialized");
			}
		} finally {
			client.close();
		}
	}
	
	private static Response checkStatus(Response response) {
		int status = response.getStatus();
		if (status != 200) {
			String errorMessage = response.readEntity(String.class);
			if (StringUtils.isNotBlank(errorMessage)) {
				throw new RuntimeException(String.format("Http request failed (status code: %d, error message: %s)", 
						status, errorMessage));
			} else {
				throw new RuntimeException("Http request failed with status " + status 
						+ ", check server log for detaiils");
			}
		} else {
			return response;
		}
	}

	private static Future<?> tailJobLog(File workspace, boolean isStdout) {
		return EXECUTOR_SERVICE.submit(new Runnable() {

			@Override
			public void run() {
				File logFile;
				if (isStdout)
					logFile = new File(workspace, ".onedev/job-stdout");
				else
					logFile = new File(workspace, ".onedev/job-stderr");
				while (!logFile.exists()) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				
				Thread thread = Thread.currentThread();
				Commandline tail = new Commandline("tail");
				tail.addArgs("-n", "+1", "-f", logFile.getAbsolutePath());

				AtomicBoolean interrupted = new AtomicBoolean(false);
				try {
					tail.execute(new LineConsumer() {
			
						@Override
						public void consume(String line) {
							if (line.equals(JOB_FINISH_LINE)) { 
								thread.interrupt();
								interrupted.set(true);
							} else if (isStdout) {
								logger.info(line);
							} else {
								logger.error(line);
							}
						}
						
					}, newErrorLogger()).checkReturnCode();
					
					throw new RuntimeException("Kubernetes: unexpected end of job log watching");
				} catch (Exception e) {
					if (!interrupted.get())
						throw e;
				}
			}
			
		});
	}
	
	@SuppressWarnings("unchecked")
	public static void sidecar(String serverUrl, String jobToken, File workspace, boolean test) {
		Future<?> stdoutLogFuture = tailJobLog(workspace, true);
		Future<?> stderrLogFuture = tailJobLog(workspace, false);
		
		try {
			while (true) {
				if (stdoutLogFuture.isDone() && stderrLogFuture.isDone()) {
					stdoutLogFuture.get();
					stderrLogFuture.get();
					break;
				} else {
					Thread.sleep(1000);
				}
			}
		} catch (InterruptedException|ExecutionException e) {
			throw new RuntimeException(e);
		} finally {
			stdoutLogFuture.cancel(true);
			stderrLogFuture.cancel(true);
		}
		
		if (!test) {
			logger.info("Uploading job outcomes to '{}'...", serverUrl);
			
			Client client = ClientBuilder.newClient();
			client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
			try {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request(MediaType.APPLICATION_JSON);
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				
				Map<String, Object> jobContext;
				Response response = checkStatus(builder.get());
				try {
					String json = response.readEntity(String.class);
					jobContext = new ObjectMapper().readValue(json, Map.class);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					response.close();
				}
				
				List<String> includes = (List<String>) ((List<?>)jobContext.get("collectFiles.includes")).get(1);
				List<String> excludes = (List<String>) ((List<?>)jobContext.get("collectFiles.excludes")).get(1);
				
				target = client.target(serverUrl).path("rest/k8s/upload-outcomes");

				StreamingOutput os = new StreamingOutput() {

					@Override
				   public void write(OutputStream os) throws IOException {
						TarUtils.tar(workspace, includes, excludes, os);
						os.flush();
				   }				   
				   
				};
				builder = target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				try {
					response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM_TYPE));
					checkStatus(response);
				} finally {
					response.close();
				}
			} finally {
				client.close();
			}
		}
	}
	
}
