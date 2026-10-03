package io.onedev.k8shelper;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;

import org.jspecify.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static io.onedev.k8shelper.KubernetesHelper.hasSymbolLinks;
import static io.onedev.k8shelper.KubernetesHelper.readPlaceholderValues;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;

public class ServerSideFacade extends LeafFacade {

	private static final long serialVersionUID = 1L;

	private transient Object step;
	
	private final String sourcePath;
	
	private final Set<String> includeFiles;
	
	private final Set<String> excludeFiles;
	
	private final Collection<String> placeholders;
	
	public ServerSideFacade(Object step, @Nullable String sourcePath, 
			Set<String> includeFiles, Set<String> excludeFiles,  Collection<String> placeholders) {
		this.step = step;
		this.sourcePath = sourcePath;
		this.includeFiles = includeFiles;
		this.excludeFiles = excludeFiles;
		this.placeholders = placeholders;
	}

	public Object getStep() {
		return step;
	}

	public String getSourcePath() {
		return sourcePath;
	}

	public Set<String> getIncludeFiles() {
		return includeFiles;
	}

	public Set<String> getExcludeFiles() {
		return excludeFiles;
	}

	public Collection<String> getPlaceholders() {
		return placeholders;
	}

	File getSourceDir(File buildDir, Map<String, String> placeholderValues) {
		File sourceDir = new File(buildDir, "work");
		if (getSourcePath() != null) {
			String sourcePath = replacePlaceholders(getSourcePath(), placeholderValues);
			if (sourcePath.contains(".."))
				throw new ExplicitException("Source path should not contain '..'");
			sourceDir = new File(sourceDir, sourcePath);
		}
		if (hasSymbolLinks(buildDir, sourceDir))
			throw new ExplicitException("Source directory does not allow symbolic links: " + sourceDir);
		return sourceDir;
	}

	public boolean execute(File buildDir, Runner runner) {
		File filesDir = FileUtils.createTempDir();
		try {
			Collection<String> placeholders = getPlaceholders();
			Map<String, String> placeholderValues = readPlaceholderValues(buildDir, placeholders);
			
			File sourceDir = getSourceDir(buildDir, placeholderValues);
			
			Collection<String> includeFiles = replacePlaceholders(getIncludeFiles(), placeholderValues);
			Collection<String> excludeFiles = replacePlaceholders(getExcludeFiles(), placeholderValues);

			for (File file: FileUtils.listFiles(sourceDir, includeFiles, excludeFiles, false)) {
				String scanned = sourceDir.toPath().relativize(file.toPath()).toString();
				try {
					FileUtils.copyFile(file, new File(filesDir, scanned));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			var result = runner.run(filesDir, placeholderValues);
			
			for (Map.Entry<String, byte[]> entry: result.getOutputFiles().entrySet()) {
				FileUtils.writeByteArrayToFile(
						new File(buildDir, entry.getKey()),
						entry.getValue());
			}
			return result.isSuccessful();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deletePath(filesDir);
		}
	}
	
	public interface Runner {
		
		ServerStepResult run(File inputDir, Map<String, String> placeholderValues);
		
	}
	
}
