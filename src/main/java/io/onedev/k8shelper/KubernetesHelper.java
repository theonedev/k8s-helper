package io.onedev.k8shelper;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class KubernetesHelper {

	public static final String ENV_SERVER_URL = "ONEDEV_SERVER_URL";
	
	public static final String ENV_JOB_TOKEN = "ONEDEV_JOB_TOKEN";
	
	public static final String BEARER = "Bearer";
	
	public static final String LOG_END_MESSAGE = "===== End of OneDev K8s Helper Log =====";
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesHelper.class);
	
	private static File getBuildHome() {
		if (isWindows()) 
			return new File("C:\\onedev-build");
		else 
			return new File("/onedev-build");
	}
	
	private static File getJobContextFile() {
		return new File(getBuildHome(), "job-context");
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
	
	private static File getCommandHome() {
		return new File(getBuildHome(), "command");
	}
	
	private static File getMarkHome() {
		return new File(getBuildHome(), "mark");
	}
	
	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("windows");
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
	
	private static void generateCommandScript(String commandPosition, List<String> setupCommands, List<String> stepCommands) {
		try {
			File commandHome = getCommandHome();
			if (isWindows()) {
				File setupScriptFile = new File(commandHome, "setup-" + commandPosition + ".bat");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\r\n");
				
				File stepScriptFile = new File(commandHome, "step-" + commandPosition + ".bat");
				FileUtils.writeLines(stepScriptFile, stepCommands, "\r\n");
				
				File scriptFile = new File(commandHome, commandPosition + ".bat");
				String markPrefix = getMarkHome().getAbsolutePath() + "\\" + commandPosition;
				List<String> scriptContent = Lists.newArrayList(
						"@echo off",
						":wait",
						"if exist \"" + markPrefix + ".skip\" (",
						"  echo Skipping step #" + commandPosition + "...",
						"  echo " + LOG_END_MESSAGE,
						"  goto :eof",
						")",
						"if exist \"" + markPrefix + ".start\" goto start",
						"ping 127.0.0.1 -n 2 > nul",
						"goto wait",
						":start",
						"cd " + getWorkspace().getAbsolutePath() 
								+ " && cmd /c " + setupScriptFile.getAbsolutePath()
								+ " && cmd /c echo Running step #" + commandPosition + "..."
								+ " && cmd /c " + stepScriptFile.getAbsolutePath(), 
						"set exit_code=%errorlevel%",
						"if \"%exit_code%\"==\"0\" (",
						"	copy /y nul " + markPrefix + ".successful > nul",
						") else (",
						"	copy /y nul " + markPrefix + ".failed > nul",
						")",
						"echo " + LOG_END_MESSAGE,
						"exit %exit_code%");
				FileUtils.writeLines(scriptFile, scriptContent, "\r\n");
			} else {
				File setupScriptFile = new File(commandHome, "setup-" + commandPosition + ".sh");
				FileUtils.writeLines(setupScriptFile, setupCommands, "\n");
				
				File stepScriptFile = new File(commandHome, "step-" + commandPosition + ".sh");
				FileUtils.writeLines(stepScriptFile, stepCommands, "\n");
				
				File scriptFile = new File(commandHome, commandPosition + ".sh");
				String markPrefix = getMarkHome().getAbsolutePath() + "/" + commandPosition;
				List<String> wrapperScriptContent = Lists.newArrayList(
						"while [ ! -f " + markPrefix + ".start ] && [ ! -f " + markPrefix + ".skip ]",
						"do",
						"  sleep 0.1",
						"done",
						"if [ -f " + markPrefix + ".skip ]",
						"then",
						"  echo \"Skipping step #" + commandPosition + "...\"",
						"  echo " + LOG_END_MESSAGE,
						"  exit 0",
						"fi",
						"cd " + getWorkspace().getAbsolutePath() 
								+ " && sh " + setupScriptFile.getAbsolutePath()
								+ " && echo \"Running step #" + commandPosition + "...\"" 
								+ " && sh " + stepScriptFile.getAbsolutePath(), 
						"exitCode=\"$?\"", 
						"if [ $exitCode -eq 0 ]",
						"then",
						"  touch " + markPrefix + ".successful",
						"else",
						"  touch " + markPrefix + ".failed",
						"fi",						
						"echo " + LOG_END_MESSAGE,
						"exit $exitCode");
				FileUtils.writeLines(scriptFile, wrapperScriptContent, "\n");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void installJVMCert() {
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
	
	public static String encodeCommandArg(Collection<String> decoded) {
		Collection<String> base64 = new ArrayList<>();
		for (String each: decoded) 
			base64.add(Base64.getEncoder().encodeToString(each.getBytes(StandardCharsets.UTF_8)));
		String encoded = StringUtils.join(base64, "-");
		if (encoded.length() == 0)
			encoded = "-";
		return encoded;
	}
	
	public static Collection<String> decodeCommandArg(String encoded) {
		Collection<String> decoded = new ArrayList<>();
		for (String each: Splitter.on('-').trimResults().omitEmptyStrings().split(encoded)) 
			decoded.add(new String(Base64.getDecoder().decode(each), StandardCharsets.UTF_8));
		return decoded;
	}
	
	public static void installGitCert(File certFile, List<String> certLines, 
			Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		try {
			FileUtils.writeLines(certFile, certLines, "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		git.clearArgs();
		git.addArgs("config", "--global", "http.sslCAInfo", certFile.getAbsolutePath());
		git.execute(infoLogger, errorLogger).checkReturnCode();
	}
	
	@SuppressWarnings("unchecked")
	public static void init(String serverUrl, String jobToken, boolean test) {
		installJVMCert();
		
		Client client = ClientBuilder.newClient();
		try {
			File cacheHome = getCacheHome();
			FileUtils.createDir(getCommandHome());
			FileUtils.createDir(getMarkHome());
			if (test) {
				logger.info("Testing server connectivity with '{}'...", serverUrl);
				WebTarget target = client.target(serverUrl).path("rest/k8s/test");
				Invocation.Builder builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				try (Response response = builder.get()) {
					checkStatus(response);
				} 
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
					generateCommandScript("1", Lists.newArrayList(), Lists.newArrayList("@echo off", "echo hello from container"));
				else
					generateCommandScript("1", Lists.newArrayList(), Lists.newArrayList("echo hello from container"));
			} else {
				WebTarget target = client.target(serverUrl).path("rest/k8s/job-context");
				Invocation.Builder builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				
				logger.info("Retrieving job context from {}...", serverUrl);
				
				Map<String, Object> jobContext;
				byte[] jobContextBytes;
				try (Response response = builder.post(
						Entity.entity(getWorkspace().getAbsolutePath(), MediaType.APPLICATION_OCTET_STREAM))) {
					checkStatus(response);
					jobContextBytes = response.readEntity(byte[].class);
				}
				
				FileUtils.writeByteArrayToFile(getJobContextFile(), jobContextBytes);
				jobContext = SerializationUtils.deserialize(jobContextBytes);
				
				File workspace = getWorkspace();
				File workspaceCache = null;
				
				logger.info("Allocating job caches from {}...", serverUrl);
				target = client.target(serverUrl).path("rest/k8s/allocate-job-caches");
				builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				Map<CacheInstance, String> cacheAllocations;
				try (Response response = builder.post(Entity.entity(
						new CacheAllocationRequest(new Date(), getCacheInstances(cacheHome)).toString(),
						MediaType.APPLICATION_OCTET_STREAM))) {
					checkStatus(response);
					cacheAllocations = SerializationUtils.deserialize(response.readEntity(byte[].class));
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
					String commitHash = (String) jobContext.get("commitHash");
					CloneInfo cloneInfo = (CloneInfo) jobContext.get("cloneInfo");
					
					logger.info("Retrieving source code from {}...", cloneInfo.getCloneUrl());

					LineConsumer infoLogger = newInfoLogger();
					LineConsumer errorLogger = newErrorLogger();
					
					File userHome;
					if (SystemUtils.IS_OS_WINDOWS)
						userHome = new File(System.getProperty("user.home"));
					else
						userHome = new File("/root");
					
					Commandline git = new Commandline("git").workingDir(workspace);
					
					// Populate auth data here in order to clone source in init container
					cloneInfo.writeAuthData(userHome, git, infoLogger, errorLogger);
					
					File mountedUserHome = new File(userHome, "onedev");
					Commandline gitOfMountedUserHome = new Commandline("git");
					gitOfMountedUserHome.environments().put("HOME", mountedUserHome.getAbsolutePath());
					
					// Populate auth data here in case user want to do additional pull/push in main container 
					cloneInfo.writeAuthData(mountedUserHome, gitOfMountedUserHome, infoLogger, errorLogger);
					
					File trustCertsHome = getTrustCertsHome();
					if (trustCertsHome.exists()) {
						List<String> trustCertContent = new ArrayList<>();
						for (File file: trustCertsHome.listFiles()) {
							if (file.isFile()) 
								trustCertContent.addAll(FileUtils.readLines(file, Charset.defaultCharset()));
						}
						installGitCert(new File(getBuildHome(), "trust-cert.pem"), trustCertContent, git, 
								infoLogger, errorLogger);
					}
					
					Integer cloneDepth = (Integer) jobContext.get("cloneDepth");
					clone(workspace, cloneInfo.getCloneUrl(), commitHash, cloneDepth, git, infoLogger, errorLogger);
					
					git.clearArgs();
					git.addArgs("remote", "add", "origin", cloneInfo.getCloneUrl());
					git.execute(infoLogger, errorLogger).checkReturnCode();
					
					if (new File(workspace, ".gitmodules").exists()) {
						logger.info("Retrieving submodules...");
						
						git.clearArgs();
						git.addArgs("submodule", "update", "--init", "--recursive", "--force", "--quiet");
						if (cloneDepth != null)
							git.addArgs("--depth=" + cloneDepth);						
						git.execute(infoLogger, new LineConsumer() {

							@Override
							public void consume(String line) {
								if (line.contains("Submodule") && line.contains("registered for path")
										|| line.startsWith("From ") || line.startsWith(" * branch")
										|| line.startsWith(" +") && line.contains("->")) {
									infoLogger.consume(line);
								} else {
									errorLogger.consume(line);
								}
							}
							
						}).checkReturnCode();
					}
					
				}	
				
				logger.info("Generating command scripts...");
				
				List<Action> actions = (List<Action>) jobContext.get("actions");
				new CompositeExecutable(actions).traverse(new LeafVisitor<Void>() {

					@Override
					public Void visit(LeafExecutable executable, List<Integer> position) {
						List<String> setupCommands = new ArrayList<>();
						if (isWindows()) {
							setupCommands.add("@echo off");							
							setupCommands.add("xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\onedev\\* C:\\Users\\%USERNAME% > nul");
						} else { 
							setupCommands.add("cp -r -f -p /root/onedev/. /root");
						}
						
						for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
							if (!PathUtils.isCurrent(entry.getValue())) {
								String link = PathUtils.resolve(workspace.getAbsolutePath(), entry.getValue());
								File linkTarget = entry.getKey().getDirectory(cacheHome);
								// create possible missing parent directories
								if (isWindows()) {
									setupCommands.add(String.format("if not exist \"%s\" mkdir \"%s\"", link, link)); 
									setupCommands.add(String.format("rmdir /q /s \"%s\"", link));							
									setupCommands.add(String.format("mklink /D \"%s\" \"%s\"", link, linkTarget.getAbsolutePath()));
								} else {
									setupCommands.add(String.format("mkdir -p \"%s\"", link)); 
									setupCommands.add(String.format("rm -rf \"%s\"", link));
									setupCommands.add(String.format("ln -s \"%s\" \"%s\"", linkTarget.getAbsolutePath(), link));
								}
							}
						}
						
						String positionStr = stringifyPosition(position);

						List<String> commands;
						
						if (executable instanceof CommandExecutable) {
							commands = ((CommandExecutable) executable).getCommands();
						} else {
							ServerExecutable serverExecutable = (ServerExecutable) executable;
							
							String classPath;
							if (SystemUtils.IS_OS_LINUX) 
								classPath = "/k8s-helper/*";
							else 
								classPath = "C:\\k8s-helper\\*";
							
							String includeFiles = encodeCommandArg(serverExecutable.getIncludeFiles());
							String excludeFiles = encodeCommandArg(serverExecutable.getExcludeFiles());
							String command = String.format("java -classpath \"%s\" io.onedev.k8shelper.RunServerStep %s %s %s", 
									classPath, positionStr, includeFiles, excludeFiles);
							if (SystemUtils.IS_OS_LINUX)
								commands = Lists.newArrayList(command);
							else
								commands = Lists.newArrayList("@echo off", command);
						}
						
						generateCommandScript(positionStr, setupCommands, commands);
						
						return null;
					}
					
				}, new ArrayList<>());
				
				logger.info("Downloading job dependencies from {}...", serverUrl);
				
				target = client.target(serverUrl).path("rest/k8s/download-dependencies");
				builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				
				try (Response response = builder.get()){
					checkStatus(response);
					try (InputStream is = response.readEntity(InputStream.class)) {
						TarUtils.untar(is, workspace);
					} 
				}
				logger.info("Job workspace initialized");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			client.close();
		}
	}
	
	public static String stringifyPosition(List<Integer> position) {
		return StringUtils.join(position, ".");
	}
	
	public static List<Integer> parsePosition(String position) {
		return Splitter.on('.').splitToList(position)
				.stream()
				.map(it->Integer.parseInt(it))
				.collect(Collectors.toList());
	}
	
	private static void checkStatus(Response response) {
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
		} 
	}
	
	public static void clone(File workspace, String cloneUrl, String commitHash, 
			Integer cloneDepth, Commandline git, LineConsumer infoLogger, LineConsumer errorLogger) {
		git.clearArgs();
		if (!new File(workspace, ".git").exists()) {
			git.addArgs("init", ".");
			git.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (!line.startsWith("Initialized empty Git repository"))
						infoLogger.consume(line);
				}
				
			}, errorLogger).checkReturnCode();
		}								
		
		git.clearArgs();
		git.addArgs("fetch", cloneUrl, "--force", "--quiet");
		if (cloneDepth != null)
			git.addArgs("--depth=" + cloneDepth);
		git.addArgs(commitHash);
		git.execute(infoLogger, errorLogger).checkReturnCode();
		
		git.clearArgs();
		git.addArgs("checkout", "--quiet", commitHash);
		git.execute(infoLogger, errorLogger).checkReturnCode();
		
		if (new File(workspace, ".gitmodules").exists()) {
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
		}
	}

	@SuppressWarnings("unchecked")
	public static void sidecar(String serverUrl, String jobToken, boolean test) {
		LeafHandler commandHandler = new LeafHandler() {

			@Override
			public boolean execute(LeafExecutable executable, List<Integer> position) {
				String positionStr = stringifyPosition(position);
				
				File file = new File(getMarkHome(), positionStr + ".start");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			
				File successfulFile = new File(getMarkHome(), positionStr + ".successful");
				File failedFile = new File(getMarkHome(), positionStr + ".failed");
				while (!successfulFile.exists() && !failedFile.exists()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				return successfulFile.exists();
			}

			@Override
			public void skip(LeafExecutable executable, List<Integer> position) {
				File file = new File(getMarkHome(), stringifyPosition(position) + ".skip");
				try {
					if (!file.createNewFile()) 
						throw new RuntimeException("Failed to create file: " + file.getAbsolutePath());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
		};
		
		if (test) {
			CommandExecutable executable = new CommandExecutable(
					"this does not matter", Lists.newArrayList("this does not matter"));
			executable.execute(commandHandler, Lists.newArrayList(1));
		} else {
			byte[] jobContextBytes;
			try {
				jobContextBytes = FileUtils.readFileToByteArray(getJobContextFile());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			Map<String, Object> jobContext = SerializationUtils.deserialize(jobContextBytes);
			
			List<Action> actions = (List<Action>) jobContext.get("actions");
			
			new CompositeExecutable(actions).execute(commandHandler, new ArrayList<>());
			
			installJVMCert();
			
			Client client = ClientBuilder.newClient();
			try {
				logger.info("Reporting job caches to '{}'...", serverUrl);
				WebTarget target = client.target(serverUrl).path("rest/k8s/report-job-caches");
				Invocation.Builder builder = target.request();
				builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);
				StringBuilder toStringBuilder = new StringBuilder();
				for (CacheInstance instance: getCacheInstances(getCacheHome()).keySet()) 
					toStringBuilder.append(instance.toString()).append(";");
				Response response = builder.post(Entity.entity(toStringBuilder.toString(), MediaType.APPLICATION_OCTET_STREAM));
				try {
					checkStatus(response);
				} finally {
					response.close();
				}
			} finally {
				client.close();
			}
		} 
	}
	
	public static void runServerStep(String serverUrl, String jobToken, String positionStr, 
			String encodedIncludeFiles, String encodedExcludeFiles) {
		installJVMCert();
		
		Client client = ClientBuilder.newClient();
		client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		try {
			WebTarget target = client.target(serverUrl).path("rest/k8s/run-server-step");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobToken);

			List<Integer> position = parsePosition(positionStr);
			Collection<String> includeFiles = decodeCommandArg(encodedIncludeFiles);
			Collection<String> excludeFiles = decodeCommandArg(encodedExcludeFiles);
			
			StreamingOutput os = new StreamingOutput() {

				@Override
			   public void write(OutputStream os) throws IOException {
					os.write(ByteBuffer.allocate(4).putInt(position.size()).array());
					for (int each: position) {
						os.write(ByteBuffer.allocate(4).putInt(each).array());
					}
					TarUtils.tar(getWorkspace(), includeFiles, excludeFiles, os);
			   }				   
			   
			};
			
			try (Response response = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				byte[] logBytes = response.readEntity(byte[].class);
				List<String> logMessages = SerializationUtils.deserialize(logBytes);
				for (String logMessage: logMessages)
					logger.info(logMessage);
			}
		} finally {
			client.close();
		}
	}
		
}
