package io.tapstate.cloud.iac.ssh;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * SocketFactory that tunnels every connection through an HTTP proxy using the
 * CONNECT method, so SSHJ can reach nodes when direct egress is blocked.
 * <p>
 * SSHJ creates the socket with the no-arg factory method and then calls
 * {@link Socket#connect(SocketAddress, int)}; at that point this factory opens
 * the proxy connection, asks it to establish a tunnel, and exposes the tunneled
 * stream pair through the returned Socket.
 */
public final class HttpConnectSocketFactory extends SocketFactory {

    private static final int DEFAULT_PROXY_TIMEOUT_MS = 30_000;

    private final String proxyHost;
    private final int proxyPort;

    public HttpConnectSocketFactory(String proxyHost, int proxyPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    @Override
    public Socket createSocket() {
        return new TunnelSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port,
                               InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address.getHostAddress(), port);
    }

    /** Socket whose connect() establishes an HTTP CONNECT tunnel. */
    private final class TunnelSocket extends Socket {

        private Socket delegate;

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, DEFAULT_PROXY_TIMEOUT_MS);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target)) {
                throw new IllegalArgumentException("InetSocketAddress required");
            }

            int connectTimeout = timeout > 0 ? timeout : DEFAULT_PROXY_TIMEOUT_MS;
            delegate = new Socket();
            delegate.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeout);
            delegate.setSoTimeout(connectTimeout);

            String targetName = target.getHostString();
            String request = "CONNECT " + targetName + ":" + target.getPort() + " HTTP/1.1\r\n"
                + "Host: " + targetName + ":" + target.getPort() + "\r\n"
                + "Proxy-Connection: keep-alive\r\n\r\n";
            OutputStream proxyOut = delegate.getOutputStream();
            proxyOut.write(request.getBytes(StandardCharsets.UTF_8));
            proxyOut.flush();

            readTunnelResponse(delegate.getInputStream());
            delegate.setSoTimeout(0);
        }

        /**
         * Parse the proxy response status line and headers. Read one byte at a
         * time so the SSH banner — which may already follow in the same segment —
         * is never buffered away from later readers.
         */
        private void readTunnelResponse(InputStream proxyIn) throws IOException {
            String statusLine = readLineBytewise(proxyIn);
            if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] 200.*")) {
                throw new IOException("HTTP proxy refused CONNECT: " + statusLine);
            }
            while (true) {
                String headerLine = readLineBytewise(proxyIn);
                if (headerLine == null) {
                    throw new IOException("HTTP proxy closed the tunnel during headers");
                }
                if (headerLine.isEmpty()) {
                    return;
                }
            }
        }

        /** Read up to CRLF (or LF), returning the line without the terminator. */
        private String readLineBytewise(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                        line.deleteCharAt(line.length() - 1);
                    }
                    return line.toString();
                }
                line.append((char) b);
            }
            return null;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public boolean isConnected() {
            return delegate != null && delegate.isConnected();
        }

        @Override
        public synchronized void close() throws IOException {
            if (delegate != null) {
                delegate.close();
            }
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate != null ? delegate.getInetAddress() : null;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate != null ? delegate.getRemoteSocketAddress() : null;
        }
    }
}
