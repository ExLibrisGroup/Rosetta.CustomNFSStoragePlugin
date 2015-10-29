package com.exlibris.dps.repository.plugin.storage.nfs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.exlibris.core.infra.common.exceptions.logging.ExLogger;
import com.exlibris.core.infra.common.util.IOUtil;
import com.exlibris.core.infra.svc.api.scriptRunner.ExecExternalProcess;
import com.exlibris.core.sdk.storage.containers.StoredEntityMetaData;
import com.exlibris.core.sdk.storage.handler.AbstractStorageHandler;
import com.exlibris.core.sdk.storage.handler.StorageUtil;
import com.exlibris.core.sdk.strings.StringUtils;
import com.exlibris.core.sdk.utils.FileUtil;
import com.exlibris.digitool.common.storage.Fixity;
import com.exlibris.digitool.common.storage.Fixity.FixityAlgorithm;
import com.exlibris.digitool.infrastructure.utils.Checksummer;

/**
 * An implementation of NFS storage using a plugin
 *
 * @author PeterK
 */
public class CustomNFSStoragePlugin extends AbstractStorageHandler {

	private static final String DIR_PREFIX = "DIR_PREFIX";
	private static final String FILE_PER_DIR = "FILE_PER_DIR";
	private static final String DIR_ROOT = "DIR_ROOT";
	private static final String FILES_HANDLING_METHOD = "FILES_HANDLING_METHOD";
	private static final String MOVE="move";
	private static final String SOFT_LINK="soft_link";
	private static final String HARD_LINK="hard_link";
	private static final String RELATIVE_DIRECTORY_PATH = "relativeDirectoryPath";
	private static final String DEST_FILE_PATH = "destFilePath";
	
	private static final ExLogger log = ExLogger.getExLogger(CustomNFSStoragePlugin.class);

	public CustomNFSStoragePlugin() {
		super();
	}

	@Override
	public boolean deleteEntity(String storedEntityIdentifier) {
		File file = new File(parameters.get(DIR_ROOT) + storedEntityIdentifier);

		try {
			return file.delete();
		} catch(Exception e) {
			log.warn("failed to delete entity with path: " + file.getPath());
		}
		return true;
	}

	@Override
	public InputStream retrieveEntity(String storedEntityIdentifier) throws IOException {
		return retrieveEntity(storedEntityIdentifier, true);
	}

	public InputStream retrieveEntity(String storedEntityIdentifier, boolean isRelative) throws IOException {
		return new FileInputStream((isRelative ? parameters.get(DIR_ROOT) : "")+ storedEntityIdentifier);
	}

	@Override
	public String storeEntity(InputStream is, StoredEntityMetaData storedEntityMetadata) throws Exception {
		
		String existsDescPath = getFilePathInDescIfExists(storedEntityMetadata);

		String destFilePath = null; 

		boolean isCopyFileNeeded = true;
		if (existsDescPath !=null) { 		
			destFilePath = existsDescPath;	
			isCopyFileNeeded = !checkFixity(storedEntityMetadata.getFixities(), destFilePath, false);
		}

		Map<String,String> paths = getStoreEntityIdentifier(storedEntityMetadata, destFilePath);
		String storedEntityIdentifier = paths.get(RELATIVE_DIRECTORY_PATH);
		destFilePath = paths.get(DEST_FILE_PATH);

		if (isCopyFileNeeded) {
			if (canHandleSourcePath(storedEntityMetadata.getCurrentFilePath())) {
				if (is!=null) {
					is.close(); 
				}
				copyStream(storedEntityMetadata, destFilePath);
			}
			
			else {
				log.info("Cannot handle source path: "+storedEntityMetadata.getCurrentFilePath());
				if (is == null) {
					log.warn("InputStream is null");
					return null; 
				}
				FileOutputStream output = null;
				try {
					output = new FileOutputStream(new File(destFilePath));
					IOUtil.copy(is, output);
				} finally {
					IOUtil.closeQuietly(output);
				}
			}

			if(!checkFixity(storedEntityMetadata.getFixities(), storedEntityIdentifier)) {
				return null;
			}

		}

		return storedEntityIdentifier;
	}

	public boolean checkFixity(List<Fixity> fixities, String storedEntityIdentifier) throws Exception {
		return checkFixity(fixities, storedEntityIdentifier, true);
	}

	public boolean checkFixity(List<Fixity> fixities, String storedEntityIdentifier, boolean isRelativePath) throws Exception {
		boolean result = true;
		if(fixities != null) {

			boolean calcMD5 = false;
			boolean calcSHA1 = false;
			boolean calcCRC32 = false;

			for (Fixity fixity : fixities) {
				fixity.setResult(null);

				if (FixityAlgorithm.MD5.toString().equals(fixity.getAlgorithm())) {
					calcMD5 = true;
				}
				else if (FixityAlgorithm.SHA1.toString().equals(fixity.getAlgorithm())) {
					calcSHA1 = true;
				}
				else if (FixityAlgorithm.CRC32.toString().equals(fixity.getAlgorithm())) {
					calcCRC32 = true;
				}

				else {
					String oldValue = fixity.getValue();
					fixity.setValue(getChecksumUsingPlugin(isRelativePath?getLocalFilePath(storedEntityIdentifier):storedEntityIdentifier, fixity.getPluginName(), oldValue));
					fixity.setResult((oldValue == null) || (oldValue.equals(fixity.getValue())));
					result &= fixity.getResult();
				}
			}

			if (calcMD5 || calcSHA1 || calcCRC32) {

				InputStream is = null;
				try {
					is = retrieveEntity(storedEntityIdentifier, isRelativePath);
					Checksummer checksummer = new Checksummer(is, calcMD5, calcSHA1, calcCRC32);

					for (Fixity fixity : fixities) {
						int checksummerAlgorithmIndex = getChecksummerAlgorithmIndex(fixity.getAlgorithm());
						if (checksummerAlgorithmIndex != -1) { 
							String oldValue = fixity.getValue();
							fixity.setValue(checksummer.getChecksum(fixity.getAlgorithm())); 
							fixity.setResult((oldValue == null) || (oldValue.equalsIgnoreCase(fixity.getValue())));
							result &= fixity.getResult();
						}
					}
				}
				finally {
					if (is != null) {
						is.close();
					}
				}
			}
		}
		return result;
	}

	
	/**
	 * @param algorithm
	 * @return the index of the string in the FixityAlgorithm enum.
	 * If the string isn't part of the enum - returns -1.
	 */
	private int getChecksummerAlgorithmIndex(String algorithm) {
		try {
			FixityAlgorithm fixityAlgorithm = FixityAlgorithm.valueOf(algorithm); 
			return fixityAlgorithm.ordinal();
		}
		catch (Exception e) {
			return -1;	
		}
	}

	private String getStreamRelativePath(String destFilePath) {

		StringBuffer relativeDirectoryPath = new StringBuffer();

		String year = null, month = null, day = null;
		if (destFilePath == null) { 
			Date date = new Date();
			year = new SimpleDateFormat("yyyy").format(date);
			month = new SimpleDateFormat("MM").format(date);
			day = new SimpleDateFormat("dd").format(date);
		} else { 
			String nextDir = getNextDir(destFilePath); 
			String[] splitted = destFilePath.split(nextDir);
			splitted = StringUtils.splitValueDelimited(splitted[0],File.separator); 
			day = splitted[splitted.length-1];
			month = splitted[splitted.length-2];
			year = splitted[splitted.length-3];

		}
		relativeDirectoryPath.append(File.separator)
			.append(year)
			.append(File.separator)
			.append(month)
			.append(File.separator)
			.append(day)
			.append(File.separator);
		return relativeDirectoryPath.toString();
	}

	private File getStreamDirectory(String path, String fileName) {

		String directoryPrefix = "fileset_";
		int maxFilesPerDir     = 100;

		if (!StringUtils.isEmptyString(parameters.get(DIR_PREFIX))) {
			directoryPrefix = parameters.get(DIR_PREFIX);
		}

		if (!StringUtils.isEmptyString(parameters.get(FILE_PER_DIR))) {
			maxFilesPerDir = Integer.valueOf(parameters.get(FILE_PER_DIR));
		}

		File newDir = new File(parameters.get(DIR_ROOT) + File.separator + path);
		newDir.mkdirs();
		File destDir = FileUtil.getNextDirectory(newDir, directoryPrefix, maxFilesPerDir);

		return new File(destDir.getAbsolutePath() + File.separator + fileName);
	}

	private String getNextDir(String fullPath) {
		String[] dirs = fullPath.split("\\" + File.separator);
		return dirs[dirs.length - 2];
	}

	/**
	 * @param srcPath
	 * @return true if srcPath is a readable NFS file 
	 */
	private boolean canHandleSourcePath(String srcPath) {
		try {
			File file = new File(srcPath);
			return file.canRead();
		}
		catch (Exception e) {
			return false;
		}
	}

	/**
	 * @param srcPath
	 * @param destPath
	 * @throws IOException
	 */
	protected void copyStream(StoredEntityMetaData storedEntityMetadata, String destPath) throws IOException {
		String filesHandlingMethod = parameters.get(FILES_HANDLING_METHOD);
		String srcPath = storedEntityMetadata.getCurrentFilePath();

		String pid = storedEntityMetadata.getEntityPid();
		String iePid = storedEntityMetadata.getIePid();

		if (MOVE.equalsIgnoreCase(filesHandlingMethod)) {
			File canonicalSrcFile = getCanonicalFile(srcPath); 
			FileUtil.moveFile(canonicalSrcFile, new File(destPath));
			saveDestPathsTmpFile(iePid, pid, destPath);
		} else if (SOFT_LINK.equalsIgnoreCase(filesHandlingMethod)) { 
			FileUtil.createSoftLink(srcPath, destPath);
		} else if (HARD_LINK.equalsIgnoreCase(filesHandlingMethod)) {
			createHardLink(srcPath, destPath);
		} else {
			FileUtil.copyFile(srcPath, destPath); 
			saveDestPathsTmpFile(iePid, pid, destPath);
		}
	}

	
	private void saveDestPathsTmpFile(String folder, String key, String path) {
		if (folder==null)
			return;

		String tmpFilePath = getTempStorageDirectory(false) + StorageUtil.DEST_PATH_FOLDER;

		File destPathDir = new File(getTempStorageDirectory(false) + StorageUtil.DEST_PATH_FOLDER + File.separator);
		if (!destPathDir.exists())
			destPathDir.mkdirs();

		StorageUtil.saveDestPathToTmpFile(folder, tmpFilePath, key, path);
	}

	/**
	 * @param storedEntityMetadata
	 * @return file path if the file already exists in the file system, otherwise returns null
	 */
	protected String getFilePathInDescIfExists(StoredEntityMetaData storedEntityMetadata) {
		String tmpFilePath = getTempStorageDirectory(false) + StorageUtil.DEST_PATH_FOLDER;
		if (storedEntityMetadata.getIePid() == null)
			return null;
		String existsDescPath = StorageUtil.readDestPathFromTmpFile(storedEntityMetadata.getIePid(), tmpFilePath,storedEntityMetadata.getEntityPid());
		return existsDescPath;
	}

	private File getCanonicalFile(String srcPath) {
		String fileName = srcPath.split("\\" + File.separator)[srcPath.split("\\" + File.separator).length-1];
		File canonicalSrcDir = null;
		try {
			canonicalSrcDir = new File(srcPath).getParentFile().getCanonicalFile();
			File canonicalSrcFile = new File(canonicalSrcDir, fileName).getCanonicalFile();
			return canonicalSrcFile;
		} catch (IOException e) {
			return null;
		}
	}
	
	private void createHardLink(String srcPath, String destPath) throws IOException {
		String command = "ln";
		ExecExternalProcess proc = new ExecExternalProcess();
		List<String> args = new LinkedList<String>();
		args.add(srcPath);
		args.add(destPath);
		int retValue = proc.execExternalProcess(command, args);
		if (retValue != 0) {
			throw new IOException("ln " + srcPath + " " + destPath + " failed " + proc.getErrorStream() + proc.getInputStream());
		}
	}

	@Override
	public String getFullFilePath(String storedEntityIdentifier) {
		return parameters.get(DIR_ROOT) + storedEntityIdentifier;
	}

	@Override
	public String getLocalFilePath(String storedEntityIdentifier) {
		return getFullFilePath(storedEntityIdentifier); 
	}

	public boolean isAvailable() {

		try {
			File file = new File(parameters.get(DIR_ROOT));
			if (!file.exists() ) {
				if(!file.mkdirs()) {
					log.error("No access to folder" + parameters.get(DIR_ROOT));
					return false;
				}

			}
			if (!file.canRead()) {
				log.error("No read access to folder:  " + parameters.get(DIR_ROOT));
				return false;
			} else if (!file.canWrite()) {
				log.error("No write access to folder:  " + parameters.get(DIR_ROOT));
				return false;
			}

		} catch(Exception e) {
			log.error("isAvailable method fell for storage: "+ getStorageId(), e);
			return false;
		}

		return true;
	}

	@Override
	public byte[] retrieveEntityByRange(String storedEntityIdentifier,
			long start, long end) throws Exception {

		byte[] bytes = new byte[(int)(end-start+1)];
		RandomAccessFile file = null;
		try {
			file = new RandomAccessFile(parameters.get(DIR_ROOT) + storedEntityIdentifier, "r");
			file.seek(start);
			file.readFully(bytes, 0, (int)(end-start+1));
		} finally {
			if (file != null) {
				try {
					file.close();
				} catch (Exception e) {
					log.warn("Failed closing file");
				}
			}
		}
		return bytes;
	}

	private Map<String,String> getStoreEntityIdentifier(StoredEntityMetaData storedEntityMetadata, String destFilePath) {
		Map<String,String> paths = new HashMap<String, String>();

		String fileName = createFileName(storedEntityMetadata); 
		String relativeDirectoryPath = getStreamRelativePath(destFilePath);
		if (destFilePath==null) {
			File destFile = getStreamDirectory(relativeDirectoryPath, fileName);
			destFilePath = destFile.getAbsolutePath();
		}
		paths.put(DEST_FILE_PATH,destFilePath);
		paths.put(RELATIVE_DIRECTORY_PATH, relativeDirectoryPath + getNextDir(destFilePath) + File.separator + fileName);
		return paths;
	}

}
