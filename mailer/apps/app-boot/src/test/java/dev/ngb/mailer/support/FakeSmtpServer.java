package dev.ngb.mailer.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal SMTP server for the sender's integration test.
 *
 * <p>It exists instead of a Mailpit container because the transport is what is under test here, not Mailpit: this
 * answers the four commands JavaMail actually issues, records the DATA payload, and never depends on container
 * networking. The full local pipeline — Mailpit, compose, the broker — is proved by {@code ops/smoke}, which needs a
 * running stack; this proves the adapter and the ledger deterministically.
 */
public final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    private FakeSmtpServer(ServerSocket server) {
        this.server = server;
        this.acceptor = Thread.ofVirtual().name("fake-smtp").start(this::acceptLoop);
    }

    public static FakeSmtpServer start() {
        try {
            return new FakeSmtpServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException failure) {
            throw new IllegalStateException("could not start the fake SMTP server", failure);
        }
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** How many messages completed their DATA phase. */
    public int messageCount() {
        return messages.size();
    }

    /** The raw payload of the last completed message, headers included. */
    public String lastMessage() {
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
    }

    public void reset() {
        messages.clear();
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing twice is not a failure.
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream()) {
            reply(out, "220 fake ESMTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase(java.util.Locale.ROOT);
                if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                    reply(out, "250-fake");
                    reply(out, "250 8BITMIME");
                } else if (command.startsWith("MAIL FROM") || command.startsWith("RCPT TO") || command.startsWith("RSET")) {
                    reply(out, "250 OK");
                } else if (command.startsWith("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    StringBuilder body = new StringBuilder();
                    String data;
                    while ((data = in.readLine()) != null && !data.equals(".")) {
                        body.append(data).append("\r\n");
                    }
                    messages.add(body.toString());
                    reply(out, "250 OK queued");
                } else if (command.startsWith("QUIT")) {
                    reply(out, "221 Bye");
                    return;
                } else {
                    reply(out, "250 OK");
                }
            }
        } catch (IOException ignored) {
            // A client that hangs up early is the test's business, not this server's.
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
