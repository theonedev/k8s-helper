package io.onedev.k8shelper;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.commons.lang3.SystemUtils;
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
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static File getBuildHome() {
		if (isWindows()) 
			return new File("C:\\onedev-build");
		else 
			return new File("/onedev-build");
	}
	
	private static File getTrustCertsHome() {
		if (isWindows()) 
			return new File("C:\\onedev-build\\trust-certs");
		else 
			return new File("/onedev-build/trust-certs");
	}
	
	private static File getCacheHome() {
		if (isWindows())
			return new File("C:\\onedev-build\\cache");
		else
			return new File("/onedev-build/cache");
	}
	
	private static File getWorkspace() {
		return new File(getBuildHome(), "workspace");
	}
	
	public static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("windows");
	}
	
	private static LineConsumer newInfoLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				if (!line.startsWith("Initialized empty Git repository"))
					logger.info(line);
			}
			
		};
	}
	
	private static LineConsumer newErrorLogger() {
		return new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.contains("Submodule") && line.contains("registered for path")
						|| line.startsWith("From ") || line.startsWith(" * branch")
						|| line.startsWith(" +") && line.contains("->")) {
					logger.info(line);
				} else {
					logger.error(line);
				}
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
			File buildHome = getBuildHome();
			if (isWindows()) {
				File setupScriptFile = new File(buildHome, "setup-commands.bat");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\r\n");
				
				File jobScriptFile = new File(buildHome, "job-commands.bat");
				FileUtils.writeLines(jobScriptFile, jobCommands, "\r\n");
				
				File scriptFile = new File(buildHome, "commands.bat");
				List<String> scriptContent = Lists.newArrayList(
						"@echo off",
						"cd " + getWorkspace().getAbsolutePath() 
								+ " && cmd /c " + buildHome.getAbsolutePath() + "\\setup-commands.bat"
								+ " && cmd /c echo Executing job commands..."
								+ " && cmd /c " + buildHome.getAbsolutePath() + "\\job-commands.bat", 
						"set last_exit_code=%errorlevel%",
						"copy nul > " + buildHome.getAbsolutePath() + "\\job-finished",
						"echo " + LOG_END_MESSAGE,
						"exit %last_exit_code%");
				FileUtils.writeLines(scriptFile, scriptContent, "\r\n");
			} else {
				File setupScriptFile = new File(buildHome, "setup-commands.sh");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\n");
				
				File jobScriptFile = new File(buildHome, "job-commands.sh");
				FileUtils.writeLines(jobScriptFile, jobCommands, "\n");
				
				File scriptFile = new File(buildHome, "commands.sh");
				List<String> wrapperScriptContent = Lists.newArrayList(
						"cd " + getWorkspace().getAbsolutePath() 
								+ " && sh " + buildHome.getAbsolutePath() + "/setup-commands.sh"
								+ " && echo Executing job commands..." 
								+ " && sh " + buildHome.getAbsolutePath() + "/job-commands.sh", 
						"lastExitCode=\"$?\"", 
						"touch " + buildHome.getAbsolutePath() + "/job-finished",
						"echo " + LOG_END_MESSAGE,
						"exit $lastExitCode"
						);
				FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void installTrustCerts() {
		File trustCertsHome = getTrustCertsHome();
		if (trustCertsHome.exists()) {
			String keystore = System.getProperty("java.home") + "/lib/security/cacerts";
			for (File each: trustCertsHome.listFiles()) {
				if (each.isFile()) {
					Commandline keytool = new Commandline("keytool");
					keytool.addArgs("-import", "-alias", each.getName(), "-file", each.getAbsolutePath(), 
							"-keystore", keystore, "-storePass", "changeit", "-noprompt");
					
					keytool.execute(newInfoLogger(), new LineConsumer() {

						@Override
						public void consume(String line) {
							if (!line.contains("Warning: use -cacerts option to access cacerts keystore"))
								logger.error(line);
						}
						
					}).checkReturnCode();
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void init(String serverUrl, String jobToken, boolean test) {
		installTrustCerts();
		
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
				FileUtils.createDir(getWorkspace());
				if (isWindows())
					generateCommandScript(Lists.newArrayList(), Lists.newArrayList("@echo off", "echo hello from container"));
				else
					generateCommandScript(Lists.newArrayList(), Lists.newArrayList("echo hello from container"));
			} else {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				
				logger.info("Retrieving job context from {}...", serverUrl);
				
				Map<String, Object> jobContext;
				Response response = checkStatus(builder.get());
				try {
					jobContext = SerializationUtils.deserialize(response.readEntity(byte[].class));
				} finally {
					response.close();
				}
				
				File workspace = getWorkspace();
				File workspaceCache = null;
				
				logger.info("Allocating job caches from {}...", serverUrl);
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
					logger.info("Retrieving source code from {}...", projectUrl);

					LineConsumer infoLogger = newInfoLogger();
					LineConsumer errorLogger = newErrorLogger();
					Commandline git = new Commandline("git");
					git.workingDir(workspace);
					
					git.addArgs("config", "--global", "credential.modalprompt", "false");
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					// clear credential.helper list to remove possible Windows credential manager
					git.clearArgs();
					if (SystemUtils.IS_OS_WINDOWS)
						git.addArgs("config", "--global", "credential.helper", "\"\"");
					else
						git.addArgs("config", "--global", "credential.helper", "");
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("config", "--global", "--add", "credential.helper", "store");
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("config", "--global", "credential.useHttpPath", "true");
					git.execute(infoLogger, errorLogger).checkReturnCode();

					git.clearArgs();
					String extraHeader = KubernetesHelper.JOB_TOKEN_HTTP_HEADER + ": " + jobToken;
					git.addArgs("config", "--global", "http.extraHeader", extraHeader);
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					File trustCertsHome = getTrustCertsHome();
					if (trustCertsHome.exists()) {
						List<String> trustCertContent = new ArrayList<>();
						for (File file: trustCertsHome.listFiles()) {
							if (file.isFile()) 
								trustCertContent.addAll(FileUtils.readLines(file, Charset.defaultCharset()));
						}
						
						File trustCertFile = new File(getBuildHome(), "trust-cert.pem");
						FileUtils.writeLines(trustCertFile, trustCertContent, "\n");
						git.clearArgs();
						git.addArgs("config", "--global", "http.sslCAInfo", trustCertFile.getAbsolutePath());
						git.execute(infoLogger, errorLogger).checkReturnCode();
					}
					
					List<String> submoduleCredentials = new ArrayList<>();
					for (Map<String, String> map: (List<Map<String, String>>)jobContext.get("submoduleCredentials")) {
						String url = map.get("url");
						String userName = URLEncoder.encode(map.get("userName"), StandardCharsets.UTF_8.name());
						String password = URLEncoder.encode(map.get("password"), StandardCharsets.UTF_8.name());
						if (url.startsWith("http://")) {
							submoduleCredentials.add("http://" + userName + ":" + password 
									+ "@" + url.substring("http://".length()).replace(":", "%3a"));
						} else {
							submoduleCredentials.add("https://" + userName + ":" + password 
									+ "@" + url.substring("https://".length()).replace(":", "%3a"));
						}
					}
					
					File credentialsFile;
					if (SystemUtils.IS_OS_WINDOWS)
						credentialsFile = new File("C:\\Users\\ContainerAdministrator\\.git-credentials");
					else
						credentialsFile = new File("/root/.git-credentials");
					FileUtils.writeLines(credentialsFile, submoduleCredentials, "\n");
					
					git.clearArgs();
					if (!new File(workspace, ".git").exists()) {
						git.addArgs("init", ".");
						git.execute(infoLogger, errorLogger).checkReturnCode();
						git.clearArgs();
					}								
					
					Integer cloneDepth = (Integer) jobContext.get("cloneDepth");
					
					git.addArgs("fetch", projectUrl, "--force", "--quiet");
					if (cloneDepth != null)
						git.addArgs("--depth=" + cloneDepth);
					git.addArgs(commitHash);
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("checkout", "--quiet", commitHash);
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					// deinit submodules in case submodule url is changed
					git.clearArgs();
					git.addArgs("submodule", "deinit", "--all", "--force", "--quiet");
					git.execute(infoLogger, new LineConsumer() {

						@Override
						public void consume(String line) {
							if (!line.contains("error: could not lock config file") && 
									!line.contains("warning: Could not unset core.worktree setting in submodule")) {
								errorLogger.consume(line);
							}
						}
						
					}).checkReturnCode();
					
					git.clearArgs();
					git.addArgs("submodule", "update", "--init", "--recursive", "--force", "--quiet");
					if (cloneDepth != null)
						git.addArgs("--depth=" + cloneDepth);						
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					FileUtils.deleteFile(credentialsFile);
				}	

				List<String> setupCommands = new ArrayList<>();
				for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
					if (!PathUtils.isCurrent(entry.getValue())) {
						String link = PathUtils.resolve(workspace.getAbsolutePath(), entry.getValue());
						File linkTarget = entry.getKey().getDirectory(cacheHome);
						if (isWindows()) {
							setupCommands.add("@echo off");							
							// create possible missing parent directories
							setupCommands.add(String.format("if not exist \"%s\" mkdir \"%s\"", link, link)); 
							setupCommands.add(String.format("rmdir /q /s \"%s\"", link));							
							setupCommands.add(String.format("mklink /D \"%s\" \"%s\"", link, linkTarget.getAbsolutePath()));
						} else {
							// create possible missing parent directories
							setupCommands.add(String.format("mkdir -p \"%s\"", link)); 
							setupCommands.add(String.format("rm -rf \"%s\"", link));
							setupCommands.add(String.format("ln -s \"%s\" \"%s\"", linkTarget.getAbsolutePath(), link));
						}
					}
				}
				
				List<String> jobCommands = (List<String>) jobContext.get("commands");
				
				generateCommandScript(setupCommands, jobCommands);
				
				logger.info("Downloading job dependencies from {}...", serverUrl);
				
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
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			client.close();
		}
	}
	
	private static Response checkStatus(Response response) {
		int status = response.getStatus();
		if (status != 200 && status != 204) {
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
		installTrustCerts();
		
		File finishedFile = new File(getBuildHome(), "job-finished");
		while (!finishedFile.exists()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		
		if (!test) {
			Client client = ClientBuilder.newClient();
			client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
			try {
				logger.info("Uploading job outcomes to '{}'...", serverUrl);
				
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
				
				logger.info("Reporting job caches to '{}'...", serverUrl);
				target = client.target(serverUrl).path("rest/k8s/report-job-caches");
				builder =  target.request();
				builder.header(KubernetesHelper.JOB_TOKEN_HTTP_HEADER, jobToken);
				Collection<CacheInstance> cacheInstances = new HashSet<>(getCacheInstances(getCacheHome()).keySet());
				byte[] cacheInstanceBytes = SerializationUtils.serialize((Serializable) cacheInstances);
				try {
					checkStatus(builder.post(Entity.entity(cacheInstanceBytes, MediaType.APPLICATION_OCTET_STREAM)));
				} finally {
					response.close();
				}
			} finally {
				client.close();
			}
		}
	}
	
}
