package io.onedev.k8shelper;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class KubernetesHelper {

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
	
	public static final String JOB_TOKEN_HTTP_HEADER = "X-ONEDEV-JOB-TOKEN";
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static File getCIHome() {
		if (isWindows()) 
			return new File("C:\\onedev-ci");
		else 
			return new File("/onedev-ci");
	}
	
	private static File getCacheHome() {
		if (isWindows())
			return new File("C:\\onedev-cache");
		else
			return new File("/onedev-cache");
	}
	
	private static File getWorkspace() {
		return new File(getCIHome(), "workspace");
	}
	
	public static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("windows");
	}
	
	private static LineConsumer newInfoLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.info(line);
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
	
	public static Map<CacheInstance, Date> getCacheInstances(File cacheHome) {
		Map<CacheInstance, Date> instances = new HashMap<>();
		
		FileFilter dirFilter = new FileFilter() {

			@Override
			public boolean accept(File pathname) {
				return pathname.isDirectory();
			}
			
		}; 
		for (File keyDir: cacheHome.listFiles(dirFilter)) {
			for (File instanceDir: keyDir.listFiles(dirFilter)) 
				instances.put(
						new CacheInstance(instanceDir.getName(), keyDir.getName()), 
						new Date(instanceDir.lastModified()));
		}
		
		return instances;
	}
	
	public static void preprocess(File cacheHome, Map<CacheInstance, String> cacheAllocations, Consumer<File> cacheCleaner) {
		for (Iterator<Map.Entry<CacheInstance, String>> it = cacheAllocations.entrySet().iterator(); it.hasNext();) {
			Map.Entry<CacheInstance, String> entry = it.next();
			File cacheDirectory = entry.getKey().getDirectory(cacheHome);
			if (entry.getValue() != null) {
				if (!cacheDirectory.exists())
					FileUtils.createDir(cacheDirectory);
				File tempFile = null;
				try {
					tempFile = File.createTempFile("update-cache-last-modified", null, cacheDirectory);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (tempFile != null)
						tempFile.delete();
				}
			} else {
				if (cacheDirectory.exists()) {
					cacheCleaner.accept(cacheDirectory);
					FileUtils.deleteDir(cacheDirectory);
				}
				it.remove();
			}
		}
	}
	
	private static void generateCommandScript(List<String> setupCommands, List<String> jobCommands) {
		try {
			File ciHome = getCIHome();
			if (isWindows()) {
				File setupScriptFile = new File(ciHome, "setup-commands.bat");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\r\n");
				
				File jobScriptFile = new File(ciHome, "job-commands.bat");
				FileUtils.writeLines(jobScriptFile, jobCommands, "\r\n");
				
				File scriptFile = new File(ciHome, "commands.bat");
				List<String> scriptContent = Lists.newArrayList(
						"@echo off",
						"cmd /c " + ciHome.getAbsolutePath() + "\\setup-commands.bat "
								+ "&& cmd /c " + ciHome.getAbsolutePath() + "\\job-commands.bat", 
						"set last_exit_code=%errorlevel%",
						"copy nul " + ciHome.getAbsolutePath() + "\\job-finished", 
						"exit %last_exit_code%");
				FileUtils.writeLines(scriptFile, scriptContent, "\r\n");
			} else {
				File setupScriptFile = new File(ciHome, "setup-commands.sh");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\n");
				
				File jobScriptFile = new File(ciHome, "job-commands.sh");
				FileUtils.writeLines(jobScriptFile, jobCommands, "\n");
				
				File scriptFile = new File(ciHome, "commands.sh");
				List<String> wrapperScriptContent = Lists.newArrayList(
						"sh " + ciHome.getAbsolutePath() + "/setup-commands.sh "
								+ "&& sh " + ciHome.getAbsolutePath() + "/job-commands.sh", 
						"lastExitCode=\"$?\"", 
						"touch " + ciHome.getAbsolutePath() + "/job-finished",
						"exit $lastExitCode"
						);
				FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void init(String serverUrl, String jobToken, boolean test) {
		Client client = ClientBuilder.newClient();
		try {
			File cacheHome = getCacheHome();
			if (test) {
				logger.info("Testing server connectivity with '{}'...", serverUrl);
				WebTarget target = client.target(serverUrl).path("rest/k8s/test");
				Invocation.Builder builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				checkStatus(builder.get());
				File tempFile = null;
				try {
					tempFile = File.createTempFile("test", null, cacheHome);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (tempFile != null)
						tempFile.delete();
				}
				generateCommandScript(Lists.newArrayList(), Lists.newArrayList("echo hello from container"));
			} else {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				
				logger.info("Retrieving job context from '{}'...", serverUrl);
				
				Map<String, Object> jobContext;
				Response response = checkStatus(builder.get());
				try {
					jobContext = SerializationUtils.deserialize(response.readEntity(byte[].class));
				} finally {
					response.close();
				}
				
				File workspace = getWorkspace();
				File workspaceCache = null;
				
				logger.info("Allocating job caches from '{}'...", serverUrl);
				target = client.target(serverUrl).path("rest/k8s/allocate-job-caches");
				builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				Map<CacheInstance, Date> cacheInstances = getCacheInstances(cacheHome);
				byte[] cacheAllocationRequestBytes = SerializationUtils.serialize(
						new CacheAllocationRequest(new Date(), cacheInstances));
				Map<CacheInstance, String> cacheAllocations;
				try {
					response = builder.post(Entity.entity(cacheAllocationRequestBytes, MediaType.APPLICATION_OCTET_STREAM));
					checkStatus(response);
					cacheAllocations = SerializationUtils.deserialize(response.readEntity(byte[].class));
				} finally {
					response.close();
				}
				
				preprocess(cacheHome, cacheAllocations, new Consumer<File>() {

					@Override
					public void accept(File dir) {
						FileUtils.cleanDir(dir);
					}
					
				});
				for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
					if (PathUtils.isCurrent(entry.getValue())) {
						workspaceCache = entry.getKey().getDirectory(getCacheHome());
						break;
					}
				}
				if (workspaceCache != null) {
					try {
						Files.createSymbolicLink(workspace.toPath(), workspaceCache.toPath());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				} else {
					FileUtils.createDir(workspace);
				}
				boolean retrieveSource = (boolean) jobContext.get("retrieveSource");
				if (retrieveSource) {
					String projectName = (String) jobContext.get("projectName");
					String commitHash = (String) jobContext.get("commitHash");
					
					String projectUrl = serverUrl + "/projects/" + projectName;
					logger.info("Retrieving source code from '{}'...", projectUrl);

					Commandline git = new Commandline("git");
					if (!new File(workspace, ".git").exists()) {
						git.addArgs("init", ".");
						git.workingDir(workspace);
						git.execute(newInfoLogger(), newErrorLogger()).checkReturnCode();
						git.clearArgs();
					}								
					String extraHeader = KubernetesHelper.JOB_TOKEN_HTTP_HEADER + ": " + jobToken;
					git.addArgs("-c", "http.extraHeader=" + extraHeader, "fetch", projectUrl, "--force", "--quiet", 
							/*"--depth=1", */commitHash);
					git.workingDir(workspace);
					git.execute(newInfoLogger(), newErrorLogger()).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("checkout", "--quiet", commitHash);
					git.workingDir(workspace);
					git.execute(newInfoLogger(), newErrorLogger()).checkReturnCode();
				}	

				List<String> setupCommands = new ArrayList<>();
				for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
					if (!PathUtils.isCurrent(entry.getValue())) {
						String link = PathUtils.resolve(workspace.getAbsolutePath(), entry.getValue());
						File linkTarget = entry.getKey().getDirectory(cacheHome);
						if (isWindows()) {
							setupCommands.add(String.format("rmdir /q /s \"%s\"", link));							
							setupCommands.add(String.format("mklink /D \"%s\" \"%s\"", link, linkTarget.getAbsolutePath()));
						} else {
							setupCommands.add(String.format("rm -rf \"%s\"", link));
							setupCommands.add(String.format("ln -s \"%s\" \"%s\"", linkTarget.getAbsolutePath(), link));
						}
					}
				}
				
				List<String> jobCommands = (List<String>) jobContext.get("commands");
				
				generateCommandScript(setupCommands, jobCommands);
				
				logger.info("Downloading job dependencies from '{}'...", serverUrl);
				
				target = client.target(serverUrl).path("rest/k8s/download-dependencies");
				builder =  target.request();
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

	@SuppressWarnings("unchecked")
	public static void sidecar(String serverUrl, String jobToken, boolean test) {
		File finishedFile = new File(getCIHome(), "job-finished");
		while (!finishedFile.exists()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		
		if (!test) {
			logger.info("Uploading job outcomes to '{}'...", serverUrl);
			
			Client client = ClientBuilder.newClient();
			client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
			try {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				
				Map<String, Object> jobContext;
				Response response = checkStatus(builder.get());
				try {
					jobContext = SerializationUtils.deserialize(response.readEntity(byte[].class));
				} finally {
					response.close();
				}
				
				Set<String> includes = (Set<String>) jobContext.get("collectFiles.includes");
				Set<String> excludes = (Set<String>) jobContext.get("collectFiles.excludes");
				
				target = client.target(serverUrl).path("rest/k8s/upload-outcomes");

				StreamingOutput os = new StreamingOutput() {

					@Override
				   public void write(OutputStream os) throws IOException {
						TarUtils.tar(getWorkspace(), includes, excludes, os);
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
