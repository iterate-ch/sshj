/*
 * Copyright (C)2009 - SSHJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.schmizz.sshj.sftp;

import com.hierynomus.sshj.common.ThreadNameProvider;
import net.schmizz.concurrent.Promise;
import net.schmizz.sshj.common.LoggerFactory;
import net.schmizz.sshj.common.SSHException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.SessionFactory;
import org.slf4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SFTPEngine
        implements Requester, Closeable {

    public static final int MAX_SUPPORTED_VERSION = 3;
    public static final int DEFAULT_TIMEOUT_MS = 30 * 1000; // way too long, but it was the original default

    /** Logger */
    protected final LoggerFactory loggerFactory;
    protected final Logger log;

    protected volatile int timeoutMs = DEFAULT_TIMEOUT_MS;

    protected final PathHelper pathHelper;

    private final Session session;
    protected final Session.Subsystem sub;
    protected final PacketReader reader;
    protected final OutputStream out;

    protected long reqID;
    protected int operativeVersion;
    protected final Map<String, String> serverExtensions = new HashMap<String, String>();

    public SFTPEngine(SessionFactory ssh)
            throws SSHException {
        this(ssh, PathHelper.DEFAULT_PATH_SEPARATOR);
    }

    public SFTPEngine(SessionFactory ssh, String pathSep)
            throws SSHException {
        this(ssh, "sftp", pathSep);
    }

    public SFTPEngine(SessionFactory ssh, String subsystemName, String pathSep)
            throws SSHException {
        session = ssh.startSession();
        loggerFactory = session.getLoggerFactory();
        log = loggerFactory.getLogger(getClass());
        sub = session.startSubsystem(subsystemName);
        out = sub.getOutputStream();
        reader = new PacketReader(this);
        ThreadNameProvider.setThreadName(reader, ssh);
        pathHelper = new PathHelper(new PathHelper.Canonicalizer() {
            @Override
            public String canonicalize(String path)
                    throws IOException {
                return SFTPEngine.this.canonicalize(path);
            }
        }, pathSep);
    }

    public SFTPEngine init()
            throws IOException {
        return init(MAX_SUPPORTED_VERSION);
    }

    /**
     * Introduced for internal use by testcases.
     * @param requestedVersion
     * @throws IOException
     */
    protected SFTPEngine init(int requestedVersion)
            throws IOException {
        if (requestedVersion > MAX_SUPPORTED_VERSION)
            throw new SFTPException("You requested an unsupported protocol version: " + requestedVersion + " (requested) > " + MAX_SUPPORTED_VERSION + " (supported)");

        if (requestedVersion < MAX_SUPPORTED_VERSION)
            log.debug("Client version {} is smaller than MAX_SUPPORTED_VERSION {}", requestedVersion, MAX_SUPPORTED_VERSION);

        transmit(new SFTPPacket<Request>(PacketType.INIT).putUInt32(requestedVersion));

        final SFTPPacket<Response> response = reader.readPacket();

        final PacketType type = response.readType();
        if (type != PacketType.VERSION)
            throw new SFTPException("Expected INIT packet, received: " + type);

        operativeVersion = response.readUInt32AsInt();
        log.debug("Server version {}", operativeVersion);
        if (requestedVersion < operativeVersion)
            throw new SFTPException("Server reported incompatible protocol version: " + operativeVersion);

        while (response.available() > 0)
            serverExtensions.put(response.readString(), response.readString());

        // Start reader thread
        reader.start();
        return this;
    }

    public Session.Subsystem getSubsystem() {
        return sub;
    }

    public int getOperativeProtocolVersion() {
        return operativeVersion;
    }

    public boolean supportsServerExtension(final String extension, final String domain) {
        return serverExtensions.containsKey(extension + "@" + domain);
    }

    public String getServerExtensionData(final String extension, final String domain) {
        return serverExtensions.get(extension + "@" + domain);
    }

    public Request newExtendedRequest(String reqName) {
        return newRequest(PacketType.EXTENDED).putString(reqName);
    }

    @Override
    public PathHelper getPathHelper() {
        return pathHelper;
    }

    @Override
    public synchronized Request newRequest(PacketType type) {
        return new Request(type, reqID = reqID + 1 & 0xffffffffL);
    }

    @Override
    public Promise<Response, SFTPException> request(Request req)
            throws IOException {
        final Promise<Response, SFTPException> promise = reader.expectResponseTo(req.getRequestID());
        log.debug("Sending {}", req);
        transmit(req);
        return promise;
    }

    private Response doRequest(Request req)
            throws IOException {
        return request(req).retrieve(getTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    public RemoteFile open(String path, Set<OpenMode> modes, FileAttributes fa)
            throws IOException {
        final byte[] handle = doRequest(
                newRequest(PacketType.OPEN).putString(path, sub.getRemoteCharset()).putUInt32(OpenMode.toMask(modes)).putFileAttributes(fa)
        ).ensurePacketTypeIs(PacketType.HANDLE).readBytes();
        return new RemoteFile(this, path, handle);
    }

    public RemoteFile open(String filename, Set<OpenMode> modes)
            throws IOException {
        return open(filename, modes, FileAttributes.EMPTY);
    }

    public RemoteFile open(String filename)
            throws IOException {
        return open(filename, EnumSet.of(OpenMode.READ));
    }

    public RemoteDirectory openDir(String path)
            throws IOException {
        final byte[] handle = doRequest(
                newRequest(PacketType.OPENDIR).putString(path, sub.getRemoteCharset())
        ).ensurePacketTypeIs(PacketType.HANDLE).readBytes();
        return new RemoteDirectory(this, path, handle);
    }

    public void setAttributes(String path, FileAttributes attrs)
            throws IOException {
        doRequest(
                newRequest(PacketType.SETSTAT).putString(path, sub.getRemoteCharset()).putFileAttributes(attrs)
        ).ensureStatusPacketIsOK();
    }

    public String readLink(String path)
            throws IOException {
        if (operativeVersion < 3)
            throw new SFTPException("READLINK is not supported in SFTPv" + operativeVersion);
        return readSingleName(
                doRequest(
                        newRequest(PacketType.READLINK).putString(path, sub.getRemoteCharset())
                ), sub.getRemoteCharset());
    }

    public void makeDir(String path, FileAttributes attrs)
            throws IOException {
        doRequest(newRequest(PacketType.MKDIR).putString(path, sub.getRemoteCharset()).putFileAttributes(attrs)).ensureStatusPacketIsOK();
    }

    public void makeDir(String path)
            throws IOException {
        makeDir(path, FileAttributes.EMPTY);
    }

    public void symlink(String linkpath, String targetpath)
            throws IOException {
        if (operativeVersion < 3)
            throw new SFTPException("SYMLINK is not supported in SFTPv" + operativeVersion);
        doRequest(
                newRequest(PacketType.SYMLINK).putString(linkpath, sub.getRemoteCharset()).putString(targetpath, sub.getRemoteCharset())
        ).ensureStatusPacketIsOK();
    }

    public void remove(String filename)
            throws IOException {
        doRequest(
                newRequest(PacketType.REMOVE).putString(filename, sub.getRemoteCharset())
        ).ensureStatusPacketIsOK();
    }

    public void removeDir(String path)
            throws IOException {
        doRequest(
                newRequest(PacketType.RMDIR).putString(path, sub.getRemoteCharset())
        ).ensureStatusIs(Response.StatusCode.OK);
    }

    public FileAttributes stat(String path)
            throws IOException {
        return stat(PacketType.STAT, path);
    }

    public FileAttributes lstat(String path)
            throws IOException {
        return stat(PacketType.LSTAT, path);
    }

    public void rename(String oldPath, String newPath, Set<RenameFlags> flags)
            throws IOException {
        if (operativeVersion < 1) {
            throw new SFTPException("RENAME is not supported in SFTPv" + operativeVersion);
        }

        // request variables to be determined
        PacketType type = PacketType.RENAME; // Default
        long renameFlagMask = 0L;
        String serverExtension = null;

        if (!flags.isEmpty()) {
            // SFTP Version 5 introduced rename flags according to Section 6.5 of the specification
            if (operativeVersion >= 5) {
                for (RenameFlags flag : flags) {
                    renameFlagMask = renameFlagMask | flag.longValue();
                }
            }
            // Try to find a fallback solution if flags are not supported by the server.

            // "posix-rename@openssh.com" provides ATOMIC and OVERWRITE behaviour.
            //  From the SFTP-spec, Section 6.5:
            // "If SSH_FXP_RENAME_OVERWRITE is specified, the server MAY perform an atomic rename even if it is
            // not requested."
            // So, if overwrite is allowed we can always use the posix-rename as a fallback.
            else if (flags.contains(RenameFlags.OVERWRITE) &&
                    supportsServerExtension("posix-rename","openssh.com")) {

                type = PacketType.EXTENDED;
                serverExtension = "posix-rename@openssh.com";
            }

            // Because the OVERWRITE flag changes the behaviour in a possibly unintended way, it has to be
            // explicitly requested for the above fallback to be applicable.
            // Tell this to the developer if ATOMIC is requested without OVERWRITE.
            else if (flags.contains(RenameFlags.ATOMIC) &&
                    !flags.contains(RenameFlags.OVERWRITE) &&
                    !flags.contains(RenameFlags.NATIVE) && // see next case below
                    supportsServerExtension("posix-rename","openssh.com")) {
                throw new SFTPException("RENAME-FLAGS are not supported in SFTPv" + operativeVersion + " but " +
                            "the \"posix-rename@openssh.com\" extension could be used as fallback if OVERWRITE " +
                            "behaviour is acceptable (needs to be activated via RenameFlags.OVERWRITE).");
            }

            // From the SFTP-spec, Section 6.5:
            // "If flags includes SSH_FXP_RENAME_NATIVE, the server is free to do the rename operation in whatever
            // fashion it deems appropriate. Other flag values are considered hints as to desired behavior, but not
            // requirements."
            else if (flags.contains(RenameFlags.NATIVE)) {
                log.debug("Flags are not supported but NATIVE-flag allows to ignore other requested flags: " +
                        flags.toString());
            }

            // finally: let the user know that the server does not support what was asked
            else {
                throw new SFTPException("RENAME-FLAGS are not supported in SFTPv" + operativeVersion + " and no " +
                        "supported server extension could be found to achieve a similar result.");
            }
        }

        // build and send request
        final Request request = newRequest(type);

        if (serverExtension != null) {
            request.putString(serverExtension);
        }

        request.putString(oldPath, sub.getRemoteCharset())
                .putString(newPath, sub.getRemoteCharset());

        if (renameFlagMask != 0L) {
            request.putUInt32(renameFlagMask);
        }

        doRequest(request).ensureStatusPacketIsOK();
    }

    public String canonicalize(String path)
            throws IOException {
        return readSingleName(
                doRequest(
                        newRequest(PacketType.REALPATH).putString(path, sub.getRemoteCharset())
                ), sub.getRemoteCharset());
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public void close()
            throws IOException {
        sub.close();
        reader.interrupt();
        session.close();
    }

    protected LoggerFactory getLoggerFactory() {
	return loggerFactory;
    }

    protected FileAttributes stat(PacketType pt, String path)
            throws IOException {
        return doRequest(newRequest(pt).putString(path, sub.getRemoteCharset()))
                .ensurePacketTypeIs(PacketType.ATTRS)
                .readFileAttributes();
    }

    private static byte[] readSingleNameAsBytes(Response res)
            throws IOException {
        res.ensurePacketTypeIs(PacketType.NAME);
        if (res.readUInt32AsInt() == 1)
            return res.readStringAsBytes();
        else
            throw new SFTPException("Unexpected data in " + res.getType() + " packet");
    }

    /** Using UTF-8 */
    protected static String readSingleName(Response res)
        throws IOException {
        return readSingleName(res, StandardCharsets.UTF_8);
    }

    /** Using any character set */
    protected static String readSingleName(Response res, Charset charset)
        throws IOException {
        return new String(readSingleNameAsBytes(res), charset);
    }

    protected synchronized void transmit(SFTPPacket<Request> payload)
            throws IOException {
        final int len = payload.available();
        out.write((len >>> 24) & 0xff);
        out.write((len >>> 16) & 0xff);
        out.write((len >>> 8) & 0xff);
        out.write(len & 0xff);
        out.write(payload.array(), payload.rpos(), len);
        out.flush();
    }

}
