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

package org.apache.ignite.internal.igfs.hadoop;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.*;
import org.apache.hadoop.ipc.*;
import org.apache.ignite.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.internal.processors.igfs.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Adapter to use any Hadoop file system {@link org.apache.hadoop.fs.FileSystem} as {@link org.apache.ignite.igfs.Igfs}.
 */
public class IgfsHadoopFileSystemWrapper implements Igfs, AutoCloseable {
    /** Property name for path to Hadoop configuration. */
    public static final String SECONDARY_FS_CONFIG_PATH = "SECONDARY_FS_CONFIG_PATH";

    /** Property name for URI of file system. */
    public static final String SECONDARY_FS_URI = "SECONDARY_FS_URI";

    /** Hadoop file system. */
    private final FileSystem fileSys;

    /** Properties of file system */
    private final Map<String, String> props = new HashMap<>();

    /**
     * Constructor.
     *
     * @param uri URI of file system.
     * @param cfgPath Additional path to Hadoop configuration.
     * @throws IgniteCheckedException In case of error.
     */
    public IgfsHadoopFileSystemWrapper(@Nullable String uri, @Nullable String cfgPath) throws IgniteCheckedException {
        Configuration cfg = new Configuration();

        if (cfgPath != null)
            cfg.addResource(U.resolveIgniteUrl(cfgPath));

        try {
            fileSys = uri == null ? FileSystem.get(cfg) : FileSystem.get(new URI(uri), cfg);
        }
        catch (IOException | URISyntaxException e) {
            throw new IgniteCheckedException(e);
        }

        uri = fileSys.getUri().toString();

        if (!uri.endsWith("/"))
            uri += "/";

        props.put(SECONDARY_FS_CONFIG_PATH, cfgPath);
        props.put(SECONDARY_FS_URI, uri);
    }

    /**
     * Convert IGFS path into Hadoop path.
     *
     * @param path IGFS path.
     * @return Hadoop path.
     */
    private Path convert(IgfsPath path) {
        URI uri = fileSys.getUri();

        return new Path(uri.getScheme(), uri.getAuthority(), path.toString());
    }

    /**
     * Heuristically checks if exception was caused by invalid HDFS version and returns appropriate exception.
     *
     * @param e Exception to check.
     * @param detailMsg Detailed error message.
     * @return Appropriate exception.
     */
    private IgfsException handleSecondaryFsError(IOException e, String detailMsg) {
        boolean wrongVer = X.hasCause(e, RemoteException.class) ||
            (e.getMessage() != null && e.getMessage().contains("Failed on local"));

        IgfsException igfsErr = !wrongVer ? cast(detailMsg, e) :
            new IgfsInvalidHdfsVersionException("HDFS version you are connecting to differs from local " +
                "version.", e);



        return igfsErr;
    }

    /**
     * Cast IO exception to IGFS exception.
     *
     * @param e IO exception.
     * @return IGFS exception.
     */
    public static IgfsException cast(String msg, IOException e) {
        if (e instanceof FileNotFoundException)
            return new IgfsFileNotFoundException(e);
        else if (e instanceof ParentNotDirectoryException)
            return new IgfsParentNotDirectoryException(msg, e);
        else if (e instanceof PathIsNotEmptyDirectoryException)
            return new IgfsDirectoryNotEmptyException(e);
        else if (e instanceof PathExistsException)
            return new IgfsPathAlreadyExistsException(msg, e);
        else
            return new IgfsException(msg, e);
    }

    /**
     * Convert Hadoop FileStatus properties to map.
     *
     * @param status File status.
     * @return IGFS attributes.
     */
    private static Map<String, String> properties(FileStatus status) {
        FsPermission perm = status.getPermission();

        if (perm == null)
            perm = FsPermission.getDefault();

        return F.asMap(PROP_PERMISSION, String.format("%04o", perm.toShort()), PROP_USER_NAME, status.getOwner(),
            PROP_GROUP_NAME, status.getGroup());
    }

    /** {@inheritDoc} */
    @Override public boolean exists(IgfsPath path) {
        try {
            return fileSys.exists(convert(path));
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to check file existence [path=" + path + "]");
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgfsFile update(IgfsPath path, Map<String, String> props) {
        IgfsHadoopFSProperties props0 = new IgfsHadoopFSProperties(props);

        try {
            if (props0.userName() != null || props0.groupName() != null)
                fileSys.setOwner(convert(path), props0.userName(), props0.groupName());

            if (props0.permission() != null)
                fileSys.setPermission(convert(path), props0.permission());
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to update file properties [path=" + path + "]");
        }

        //Result is not used in case of secondary FS.
        return null;
    }

    /** {@inheritDoc} */
    @Override public void rename(IgfsPath src, IgfsPath dest) {
        // Delegate to the secondary file system.
        try {
            if (!fileSys.rename(convert(src), convert(dest)))
                throw new IgfsException("Failed to rename (secondary file system returned false) " +
                    "[src=" + src + ", dest=" + dest + ']');
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to rename file [src=" + src + ", dest=" + dest + ']');
        }
    }

    /** {@inheritDoc} */
    @Override public boolean delete(IgfsPath path, boolean recursive) {
        try {
            return fileSys.delete(convert(path), recursive);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to delete file [path=" + path + ", recursive=" + recursive + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path) {
        try {
            if (!fileSys.mkdirs(convert(path)))
                throw new IgniteException("Failed to make directories [path=" + path + "]");
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to make directories [path=" + path + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path, @Nullable Map<String, String> props) {
        try {
            if (!fileSys.mkdirs(convert(path), new IgfsHadoopFSProperties(props).permission()))
                throw new IgniteException("Failed to make directories [path=" + path + ", props=" + props + "]");
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to make directories [path=" + path + ", props=" + props + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsPath> listPaths(IgfsPath path) {
        try {
            FileStatus[] statuses = fileSys.listStatus(convert(path));

            if (statuses == null)
                throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);

            Collection<IgfsPath> res = new ArrayList<>(statuses.length);

            for (FileStatus status : statuses)
                res.add(new IgfsPath(path, status.getPath().getName()));

            return res;
        }
        catch (FileNotFoundException ignored) {
            throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to list statuses due to secondary file system exception: " + path);
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsFile> listFiles(IgfsPath path) {
        try {
            FileStatus[] statuses = fileSys.listStatus(convert(path));

            if (statuses == null)
                throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);

            Collection<IgfsFile> res = new ArrayList<>(statuses.length);

            for (FileStatus status : statuses) {
                IgfsFileInfo fsInfo = status.isDirectory() ? new IgfsFileInfo(true, properties(status)) :
                    new IgfsFileInfo((int)status.getBlockSize(), status.getLen(), null, null, false,
                    properties(status));

                res.add(new IgfsFileImpl(new IgfsPath(path, status.getPath().getName()), fsInfo, 1));
            }

            return res;
        }
        catch (FileNotFoundException ignored) {
            throw new IgfsFileNotFoundException("Failed to list files (path not found): " + path);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to list statuses due to secondary file system exception: " + path);
        }
    }

    /** {@inheritDoc} */
    @Override public IgfsReader open(IgfsPath path, int bufSize) {
        return new IgfsHadoopReader(fileSys, convert(path), bufSize);
    }

    /** {@inheritDoc} */
    @Override public OutputStream create(IgfsPath path, boolean overwrite) {
        try {
            return fileSys.create(convert(path), overwrite);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to create file [path=" + path + ", overwrite=" + overwrite + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public OutputStream create(IgfsPath path, int bufSize, boolean overwrite, int replication,
        long blockSize, @Nullable Map<String, String> props) {
        IgfsHadoopFSProperties props0 =
            new IgfsHadoopFSProperties(props != null ? props : Collections.<String, String>emptyMap());

        try {
            return fileSys.create(convert(path), props0.permission(), overwrite, bufSize, (short)replication, blockSize,
                null);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to create file [path=" + path + ", props=" + props +
                ", overwrite=" + overwrite + ", bufSize=" + bufSize + ", replication=" + replication +
                ", blockSize=" + blockSize + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public OutputStream append(IgfsPath path, int bufSize, boolean create,
        @Nullable Map<String, String> props) {
        try {
            return fileSys.append(convert(path), bufSize);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to append file [path=" + path + ", bufSize=" + bufSize + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public IgfsFile info(final IgfsPath path) {
        try {
            final FileStatus status = fileSys.getFileStatus(convert(path));

            if (status == null)
                return null;

            final Map<String, String> props = properties(status);

            return new IgfsFile() {
                @Override public IgfsPath path() {
                    return path;
                }

                @Override public boolean isFile() {
                    return status.isFile();
                }

                @Override public boolean isDirectory() {
                    return status.isDirectory();
                }

                @Override public int blockSize() {
                    return (int)status.getBlockSize();
                }

                @Override public long groupBlockSize() {
                    return status.getBlockSize();
                }

                @Override public long accessTime() {
                    return status.getAccessTime();
                }

                @Override public long modificationTime() {
                    return status.getModificationTime();
                }

                @Override public String property(String name) throws IllegalArgumentException {
                    String val = props.get(name);

                    if (val ==  null)
                        throw new IllegalArgumentException("File property not found [path=" + path + ", name=" + name + ']');

                    return val;
                }

                @Nullable @Override public String property(String name, @Nullable String dfltVal) {
                    String val = props.get(name);

                    return val == null ? dfltVal : val;
                }

                @Override public long length() {
                    return status.getLen();
                }

                /** {@inheritDoc} */
                @Override public Map<String, String> properties() {
                    return props;
                }
            };

        }
        catch (FileNotFoundException ignore) {
            return null;
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to get file status [path=" + path + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public long usedSpaceSize() {
        try {
            return fileSys.getContentSummary(new Path(fileSys.getUri())).getSpaceConsumed();
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to get used space size of file system.");
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public Map<String, String> properties() {
        return props;
    }

    /** {@inheritDoc} */
    @Override public void close() throws IgniteCheckedException {
        try {
            fileSys.close();
        }
        catch (IOException e) {
            throw new IgniteCheckedException(e);
        }
    }
}
