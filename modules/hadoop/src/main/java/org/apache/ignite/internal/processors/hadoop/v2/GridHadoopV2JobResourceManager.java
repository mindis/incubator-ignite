/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.hadoop.v2;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.util.*;
import org.apache.ignite.*;
import org.apache.ignite.internal.processors.hadoop.*;
import org.apache.ignite.internal.processors.hadoop.fs.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Provides all resources are needed to the job execution. Downloads the main jar, the configuration and additional
 * files are needed to be placed on local files system.
 */
public class GridHadoopV2JobResourceManager {
    /** Hadoop job context. */
    private final JobContextImpl ctx;

    /** Logger. */
    private final IgniteLogger log;

    /** Job ID. */
    private final GridHadoopJobId jobId;

    /** Class path list. */
    private URL[] clsPath;

    /** Set of local resources. */
    private final Collection<File> rsrcSet = new HashSet<>();

    /** Staging directory to delivery job jar and config to the work nodes. */
    private Path stagingDir;

    /**
     * Creates new instance.
     * @param jobId Job ID.
     * @param ctx Hadoop job context.
     * @param log Logger.
     */
    public GridHadoopV2JobResourceManager(GridHadoopJobId jobId, JobContextImpl ctx, IgniteLogger log) {
        this.jobId = jobId;
        this.ctx = ctx;
        this.log = log.getLogger(GridHadoopV2JobResourceManager.class);
    }

    /**
     * Set working directory in local file system.
     *
     * @throws IOException If fails.
     * @param dir Working directory.
     */
    private void setLocalFSWorkingDirectory(File dir) throws IOException {
        JobConf cfg = ctx.getJobConf();

        Thread.currentThread().setContextClassLoader(cfg.getClassLoader());

        try {
            cfg.set(GridHadoopFileSystemsUtils.LOC_FS_WORK_DIR_PROP, dir.getAbsolutePath());

            if(!cfg.getBoolean("fs.file.impl.disable.cache", false))
                FileSystem.getLocal(cfg).setWorkingDirectory(new Path(dir.getAbsolutePath()));
        }
        finally {
            Thread.currentThread().setContextClassLoader(null);
        }
    }

    /**
     * Prepare job resources. Resolve the classpath list and download it if needed.
     *
     * @param download {@code true} If need to download resources.
     * @param jobLocDir Work directory for the job.
     * @throws IgniteCheckedException If failed.
     */
    public void prepareJobEnvironment(boolean download, File jobLocDir) throws IgniteCheckedException {
        try {
            if (jobLocDir.exists())
                throw new IgniteCheckedException("Local job directory already exists: " + jobLocDir.getAbsolutePath());

            JobConf cfg = ctx.getJobConf();

            String mrDir = cfg.get("mapreduce.job.dir");

            if (mrDir != null) {
                stagingDir = new Path(new URI(mrDir));

                if (download) {
                    FileSystem fs = FileSystem.get(stagingDir.toUri(), cfg);

                    if (!fs.exists(stagingDir))
                        throw new IgniteCheckedException("Failed to find map-reduce submission directory (does not exist): " +
                            stagingDir);

                    if (!FileUtil.copy(fs, stagingDir, jobLocDir, false, cfg))
                        throw new IgniteCheckedException("Failed to copy job submission directory contents to local file system " +
                            "[path=" + stagingDir + ", locDir=" + jobLocDir.getAbsolutePath() + ", jobId=" + jobId + ']');
                }

                File jarJobFile = new File(jobLocDir, "job.jar");

                Collection<URL> clsPathUrls = new ArrayList<>();

                clsPathUrls.add(jarJobFile.toURI().toURL());

                rsrcSet.add(jarJobFile);
                rsrcSet.add(new File(jobLocDir, "job.xml"));

                processFiles(jobLocDir, ctx.getCacheFiles(), download, false, null, MRJobConfig.CACHE_LOCALFILES);
                processFiles(jobLocDir, ctx.getCacheArchives(), download, true, null, MRJobConfig.CACHE_LOCALARCHIVES);
                processFiles(jobLocDir, ctx.getFileClassPaths(), download, false, clsPathUrls, null);
                processFiles(jobLocDir, ctx.getArchiveClassPaths(), download, true, clsPathUrls, null);

                if (!clsPathUrls.isEmpty()) {
                    clsPath = new URL[clsPathUrls.size()];

                    clsPathUrls.toArray(clsPath);
                }
            }
            else if (!jobLocDir.mkdirs())
                throw new IgniteCheckedException("Failed to create local job directory: " + jobLocDir.getAbsolutePath());

            setLocalFSWorkingDirectory(jobLocDir);
        }
        catch (URISyntaxException | IOException e) {
            throw new IgniteCheckedException(e);
        }
    }

    /**
     * Process list of resources.
     *
     * @param jobLocDir Job working directory.
     * @param files Array of {@link java.net.URI} or {@link org.apache.hadoop.fs.Path} to process resources.
     * @param download {@code true}, if need to download. Process class path only else.
     * @param extract {@code true}, if need to extract archive.
     * @param clsPathUrls Collection to add resource as classpath resource.
     * @param rsrcNameProp Property for resource name array setting.
     * @throws IOException If failed.
     */
    private void processFiles(File jobLocDir, @Nullable Object[] files, boolean download, boolean extract,
        @Nullable Collection<URL> clsPathUrls, @Nullable String rsrcNameProp) throws IOException {
        if (F.isEmptyOrNulls(files))
            return;

        Collection<String> res = new ArrayList<>();

        for (Object pathObj : files) {
            String locName = null;
            Path srcPath;

            if (pathObj instanceof URI) {
                URI uri = (URI)pathObj;

                locName = uri.getFragment();

                srcPath = new Path(uri);
            }
            else
                srcPath = (Path)pathObj;

            if (locName == null)
                locName = srcPath.getName();

            File dstPath = new File(jobLocDir.getAbsolutePath(), locName);

            res.add(locName);

            rsrcSet.add(dstPath);

            if (clsPathUrls != null)
                clsPathUrls.add(dstPath.toURI().toURL());

            if (!download)
                continue;

            JobConf cfg = ctx.getJobConf();

            FileSystem dstFs = FileSystem.getLocal(cfg);

            FileSystem srcFs = srcPath.getFileSystem(cfg);

            if (extract) {
                File archivesPath = new File(jobLocDir.getAbsolutePath(), ".cached-archives");

                if (!archivesPath.exists() && !archivesPath.mkdir())
                    throw new IOException("Failed to create directory " +
                         "[path=" + archivesPath + ", jobId=" + jobId + ']');

                File archiveFile = new File(archivesPath, locName);

                FileUtil.copy(srcFs, srcPath, dstFs, new Path(archiveFile.toString()), false, cfg);

                String archiveNameLC = archiveFile.getName().toLowerCase();

                if (archiveNameLC.endsWith(".jar"))
                    RunJar.unJar(archiveFile, dstPath);
                else if (archiveNameLC.endsWith(".zip"))
                    FileUtil.unZip(archiveFile, dstPath);
                else if (archiveNameLC.endsWith(".tar.gz") ||
                    archiveNameLC.endsWith(".tgz") ||
                    archiveNameLC.endsWith(".tar"))
                    FileUtil.unTar(archiveFile, dstPath);
                else
                    throw new IOException("Cannot unpack archive [path=" + srcPath + ", jobId=" + jobId + ']');
            }
            else
                FileUtil.copy(srcFs, srcPath, dstFs, new Path(dstPath.toString()), false, cfg);
        }

        if (!res.isEmpty() && rsrcNameProp != null)
            ctx.getJobConf().setStrings(rsrcNameProp, res.toArray(new String[res.size()]));
    }

    /**
     * Prepares working directory for the task.
     *
     * <ul>
     *     <li>Creates working directory.</li>
     *     <li>Creates symbolic links to all job resources in working directory.</li>
     * </ul>
     *
     * @param path Path to working directory of the task.
     * @throws IgniteCheckedException If fails.
     */
    public void prepareTaskWorkDir(File path) throws IgniteCheckedException {
        try {
            if (path.exists())
                throw new IOException("Task local directory already exists: " + path);

            if (!path.mkdir())
                throw new IOException("Failed to create directory: " + path);

            for (File resource : rsrcSet) {
                File symLink = new File(path, resource.getName());

                try {
                    Files.createSymbolicLink(symLink.toPath(), resource.toPath());
                }
                catch (IOException e) {
                    String msg = "Unable to create symlink \"" + symLink + "\" to \"" + resource + "\".";

                    if (U.isWindows() && e instanceof FileSystemException)
                        msg += "\n\nAbility to create symbolic links is required!\n" +
                                "On Windows platform you have to grant permission 'Create symbolic links'\n" +
                                "to your user or run the Accelerator as Administrator.\n";

                    throw new IOException(msg, e);
                }
            }
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Unable to prepare local working directory for the task " +
                 "[jobId=" + jobId + ", path=" + path+ ']', e);
        }
    }

    /**
     * Cleans up job staging directory.
     */
    public void cleanupStagingDirectory() {
        try {
            if (stagingDir != null)
                stagingDir.getFileSystem(ctx.getJobConf()).delete(stagingDir, true);
        }
        catch (Exception e) {
            log.error("Failed to remove job staging directory [path=" + stagingDir + ", jobId=" + jobId + ']' , e);
        }
    }

    /**
     * Returns array of class path for current job.
     *
     * @return Class path collection.
     */
    @Nullable public URL[] classPath() {
        return clsPath;
    }
}
