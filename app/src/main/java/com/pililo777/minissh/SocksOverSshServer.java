package com.pililo777.minissh;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.Session;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SocksOverSshServer implements AutoCloseable {
    private final Session session;
    private final int port;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public SocksOverSshServer(Session session, int port) {
        this.session = session;
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        running = true;
        workers.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clients.add(socket);
                workers.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    // The VPN service monitors the SSH session and will fail open if needed.
                }
            }
        }
    }

    private void handle(Socket socket) {
        ChannelDirectTCPIP channel = null;
        try (Socket client = socket) {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            int version = readByte(in);
            if (version != 5) return;
            int methods = readByte(in);
            byte[] methodBytes = readExact(in, methods);
            boolean noAuth = false;
            for (byte method : methodBytes) {
                if ((method & 0xff) == 0) noAuth = true;
            }
            if (!noAuth) {
                out.write(new byte[]{5, (byte) 0xff});
                out.flush();
                return;
            }
            out.write(new byte[]{5, 0});
            out.flush();

            if (readByte(in) != 5) return;
            int command = readByte(in);
            readByte(in); // RSV
            int addressType = readByte(in);

            String host;
            if (addressType == 1) {
                host = InetAddress.getByAddress(readExact(in, 4)).getHostAddress();
            } else if (addressType == 3) {
                int length = readByte(in);
                host = new String(readExact(in, length), StandardCharsets.UTF_8);
            } else if (addressType == 4) {
                host = InetAddress.getByAddress(readExact(in, 16)).getHostAddress();
            } else {
                sendReply(out, 8);
                return;
            }

            int destinationPort = (readByte(in) << 8) | readByte(in);
            if (command != 1) {
                sendReply(out, 7); // Only CONNECT is supported in v0.4.
                return;
            }
            if (!session.isConnected()) {
                sendReply(out, 1);
                return;
            }

            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            channel.setHost(host);
            channel.setPort(destinationPort);
            channel.setOrgIPAddress(client.getInetAddress().getHostAddress());
            channel.setOrgPort(client.getPort());

            InputStream remoteIn = channel.getInputStream();
            OutputStream remoteOut = channel.getOutputStream();
            channel.connect(10_000);
            sendReply(out, 0);

            final ChannelDirectTCPIP activeChannel = channel;
            workers.execute(() -> {
                try {
                    copy(in, remoteOut);
                } catch (IOException ignored) {
                } finally {
                    try { remoteOut.close(); } catch (IOException ignored) { }
                    if (activeChannel.isConnected()) activeChannel.disconnect();
                }
            });

            copy(remoteIn, out);
        } catch (Exception e) {
            try {
                OutputStream out = socket.getOutputStream();
                sendReply(out, 1);
            } catch (Exception ignored) { }
        } finally {
            clients.remove(socket);
            if (channel != null && channel.isConnected()) channel.disconnect();
        }
    }

    private static void sendReply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return data;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count == 0) continue;
            out.write(buffer, 0, count);
            out.flush();
        }
    }

    @Override
    public void close() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (Socket client : clients) {
            try { client.close(); } catch (IOException ignored) { }
        }
        clients.clear();
        workers.shutdownNow();
    }
}
