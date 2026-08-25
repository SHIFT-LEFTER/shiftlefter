package shiftlefter.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShiftLefter warm-path dispatch client (sl-x6r; streaming restored sl-qwf).
 *
 * VERSION-INTERNAL: a private protocol between {@code bin/sl} and the jar it ships
 * with. Not part of the locked 0.5 surface; may change in any release. Lives INSIDE
 * the uberjar so it is always version-matched with the daemon it talks to — and
 * that match is MECHANICAL, not aspirational: {@code bin/sl}'s {@code read_daemon}
 * bounces a daemon whose recorded jar-size differs from the jar this client loads
 * from, or whose breadcrumb predates a jar rebuild (daemon suite test 8 pins it),
 * so client and daemon always run the same jar. Invoking this class directly
 * against a foreign daemon is outside the contract.
 *
 * <p>Why Java and not babashka: a class with zero Clojure on its classpath never
 * pays Clojure's ~800ms init — bare-JVM boot of one class out of the uberjar is
 * ~30ms, the same order as bb, with no extra install (users already have a JRE).
 *
 * <p>Invocation: {@code java -cp shiftlefter.jar shiftlefter.client.NreplClient
 * <port> <cwd> <argv...>}. Connects to the daemon's nREPL server on
 * {@code 127.0.0.1:<port>}, evaluates a form that runs
 * {@code shiftlefter.daemon/dispatch!} under the nREPL session's own
 * {@code *out*}/{@code *err*}, so output STREAMS to this process's
 * stdout/stderr as {@code :out}/{@code :err} messages while the command runs
 * (raw byte passthrough — multibyte chars split across messages concatenate
 * correctly). The eval value carries only {@code {:exit n}}. Historical: sl-x6r
 * shipped this path capture+buffered because nREPL 1.5.0's
 * CallbackBufferedOutputStream crashed on multibyte flushes; nREPL 1.7.0
 * (sl-ddi6) fixed that, and sl-qwf restored streaming.
 *
 * <p>Exit codes: 0/1/2/3/4 are the dispatched command's (passed through). 69 means
 * "could not reach the daemon" — {@code bin/sl} treats it as a signal to retry
 * cold. 69 is only returned while NOTHING has been streamed yet (connect/clone
 * phase); once any output byte has been written, a transport failure exits 3
 * (crash) instead — a cold retry would duplicate the already-streamed output.
 */
public final class NreplClient {

    /** bin/sl contract: "daemon unreachable, fall back to a cold java -jar run". */
    private static final int EX_DAEMON_UNREACHABLE = 69;

    /** Crash exit (matches the daemon/runner verdict contract's code 3). */
    private static final int EX_CRASH = 3;

    /**
     * True once any out/err byte has been written to this process's streams.
     * Gate for the 69-vs-3 decision on transport failure: cold retry is only
     * safe while nothing has been streamed.
     */
    private static volatile boolean streamedAnything = false;

    private static final int CONNECT_TIMEOUT_MS = 2000;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: NreplClient <port> <cwd> [argv...]");
            System.exit(EX_DAEMON_UNREACHABLE);
        }
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("NreplClient: bad port " + args[0]);
            System.exit(EX_DAEMON_UNREACHABLE);
            return;
        }
        String cwd = args[1];
        String[] argv = new String[args.length - 2];
        System.arraycopy(args, 2, argv, 0, argv.length);

        try {
            System.exit(dispatch(port, cwd, argv));
        } catch (IOException e) {
            // Transport failure. Before any output has streamed (connect/clone,
            // or eval dying without producing a byte) nothing has been written,
            // so bin/sl may safely retry cold (69). Once output HAS streamed, a
            // cold retry would duplicate it — report and exit 3 (crash) instead.
            if (streamedAnything) {
                System.err.println("sl: warm dispatch lost the daemon mid-stream ("
                                   + e.getMessage() + ")");
                System.exit(EX_CRASH);
            }
            if (!(e instanceof ConnectException)) {
                System.err.println("sl: warm dispatch failed (" + e.getMessage()
                                   + "); falling back to cold");
            }
            System.exit(EX_DAEMON_UNREACHABLE);
        }
    }

    /**
     * Internal sentinel: {@code sl daemon stop} routes here. Not a user command —
     * the wrapper passes it as the sole argv to request a graceful daemon shutdown
     * (eval {@code (shiftlefter.daemon/stop!)}) rather than a dispatch.
     */
    private static final String STOP_SENTINEL = "--sl-internal-stop";

    /**
     * Internal sentinel: the custody half of {@code sl daemon status} routes here
     * (sl-s35y). A READ of the daemon's live-costume registry — deliberately NOT
     * the generic dispatch path, whose dispatch! resets the stepdef/intent
     * registries per its per-dispatch contract; a status query must not mutate
     * daemon state.
     */
    private static final String STATUS_SENTINEL = "--sl-internal-status";

    private static final String STATUS_CODE =
        "(let [r (try (do (shiftlefter.daemon/custody-status!) {:exit 0})"
        + "          (catch Throwable t (.printStackTrace t *err*) {:exit 3}))]"
        + "  (flush) (.flush ^java.io.Writer *err*) {:exit (:exit r)})";

    private static int dispatch(int port, String cwd, String[] argv) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            OutputStream out = sock.getOutputStream();
            PushbackInputStream in =
                new PushbackInputStream(new BufferedInputStream(sock.getInputStream()), 1);

            // 1. clone a session (nREPL's session middleware expects one for eval).
            sendDict(out, "op", "clone", "id", "1");
            String session = awaitSession(in);

            // 2a. graceful stop: eval (stop!), tolerate the server dropping the
            //     connection as it shuts itself down.
            if (argv.length == 1 && STOP_SENTINEL.equals(argv[0])) {
                sendDict(out, "op", "eval", "session", session, "id", "2",
                         "code", "(shiftlefter.daemon/stop!)");
                return awaitStop(in);
            }

            // 2a'. custody read for `sl daemon status` (sl-s35y): plain eval +
            //      the W5 close — no dispatch!, no state reset.
            if (argv.length == 1 && STATUS_SENTINEL.equals(argv[0])) {
                sendDict(out, "op", "eval", "session", session, "id", "2",
                         "code", STATUS_CODE);
                int statusRc = awaitResult(in);
                sendDict(out, "op", "close", "session", session, "id", "3");
                return statusRc;
            }

            // 2b. normal: eval the streaming dispatch form.
            sendDict(out, "op", "eval", "session", session, "id", "2",
                     "code", buildCode(cwd, argv));
            int rc = awaitResult(in);
            // 3. Release the session (sl-xjx0, Warden W5): nREPL retains every
            //    cloned session (each with a parked SessionThread) until an
            //    explicit close — one leaked session + thread per warm `sl`
            //    invocation otherwise, with no reaper short of the 60-min idle
            //    shutdown. One buffered close message, no ack-wait (the server
            //    processes it after we disconnect; zero added latency). The
            //    stop path skips this (daemon is shutting down) and exception
            //    paths fall through to the socket close as before.
            sendDict(out, "op", "close", "session", session, "id", "3");
            return rc;
        }
    }

    /** Read until done OR the server closes the socket — both mean stop! succeeded. */
    private static int awaitStop(PushbackInputStream in) throws IOException {
        try {
            while (true) {
                Object msg = readBencode(in);
                if (msg instanceof Map && statusContains(((Map<?, ?>) msg).get("status"), "done")) {
                    return 0;
                }
            }
        } catch (EOFException e) {
            return 0;
        }
    }

    /** Read clone responses until the new-session id appears. */
    private static String awaitSession(PushbackInputStream in) throws IOException {
        while (true) {
            Object msg = readBencode(in);
            if (msg instanceof Map) {
                Object s = ((Map<?, ?>) msg).get("new-session");
                if (s instanceof byte[]) {
                    return new String((byte[]) s, StandardCharsets.UTF_8);
                }
            }
        }
    }

    /**
     * Read eval responses until status "done" for our id, STREAMING out/err
     * bytes to this process's stdout/stderr as they arrive (raw byte
     * passthrough — a multibyte char split across two messages concatenates
     * correctly), then decoding the {:exit n} value.
     */
    private static int awaitResult(PushbackInputStream in) throws IOException {
        String value = null;
        while (true) {
            Object msg = readBencode(in);
            if (!(msg instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) msg;
            Object o = m.get("out");
            if (o instanceof byte[]) {
                streamedAnything = true;
                System.out.write((byte[]) o);
                System.out.flush();
            }
            Object e = m.get("err");
            if (e instanceof byte[]) {
                streamedAnything = true;
                System.err.write((byte[]) e);
                System.err.flush();
            }
            Object v = m.get("value");
            if (v instanceof byte[]) {
                value = new String((byte[]) v, StandardCharsets.UTF_8);
            }
            if (statusContains(m.get("status"), "done")) {
                return emit(value);
            }
        }
    }

    /**
     * Parse the {:exit n} value, return the exit code. The "no value /
     * unparseable" fallbacks return 69 only while nothing has streamed
     * (main()'s IOException gate can't see these paths, so they gate
     * themselves): after streamed output a cold retry would duplicate it.
     */
    private static int emit(String value) {
        Matcher exit = value == null
            ? null
            : Pattern.compile(":exit\\s+(-?\\d+)").matcher(value);
        if (exit == null || !exit.find()) {
            System.err.println(value == null
                               ? "sl: warm dispatch returned no value"
                               : "sl: unparseable warm response");
            return streamedAnything ? EX_CRASH : EX_DAEMON_UNREACHABLE;
        }
        return Integer.parseInt(exit.group(1));
    }

    private static boolean statusContains(Object status, String want) {
        if (status instanceof List) {
            for (Object x : (List<?>) status) {
                if (x instanceof byte[]
                    && want.equals(new String((byte[]) x, StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Clojure form sent to the daemon. Runs dispatch! under the nREPL
     * session's own out and err writers so output streams back as :out/:err messages
     * while the command executes (sl-qwf; the capture+buffer dodge died with
     * nREPL 1.7.0, sl-ddi6). The explicit flushes push any trailing partial
     * line BEFORE the value returns — nREPL forwards session writes
     * synchronously on the eval thread, so every output message precedes
     * {:exit n} on the wire. dispatch! already catches Throwable -> {:exit 3};
     * the extra try is belt-and-suspenders for the scaffolding itself.
     */
    private static String buildCode(String cwd, String[] argv) {
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) {
                vec.append(' ');
            }
            vec.append(cljString(argv[i]));
        }
        vec.append(']');
        return "(let [r (try (shiftlefter.daemon/dispatch! {:argv " + vec
             +              " :cwd " + cljString(cwd) + "})"
             + "             (catch Throwable t (.printStackTrace t *err*) {:exit 3}))]"
             + "  (flush)"
             + "  (.flush ^java.io.Writer *err*)"
             + "  {:exit (:exit r)})";
    }

    /** A Clojure string literal: wrap in quotes, escape backslash and quote. */
    private static String cljString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('"').toString();
    }

    // --- minimal bencode reader: byte[] | Long | List | Map<String,Object> -------

    private static Object readBencode(PushbackInputStream in) throws IOException {
        int c = in.read();
        if (c == -1) {
            throw new EOFException("nREPL stream closed");
        }
        switch (c) {
            case 'i':
                return readIntUntil(in);
            case 'l':
                return readList(in);
            case 'd':
                return readDict(in);
            default:
                return readString(in, c);
        }
    }

    private static Long readIntUntil(PushbackInputStream in) throws IOException {
        StringBuilder n = new StringBuilder();
        int d;
        while ((d = in.read()) != 'e') {
            if (d == -1) {
                throw new EOFException();
            }
            n.append((char) d);
        }
        return Long.valueOf(n.toString());
    }

    private static List<Object> readList(PushbackInputStream in) throws IOException {
        List<Object> l = new ArrayList<>();
        while (true) {
            int p = in.read();
            if (p == 'e') {
                return l;
            }
            if (p == -1) {
                throw new EOFException();
            }
            in.unread(p);
            l.add(readBencode(in));
        }
    }

    private static Map<String, Object> readDict(PushbackInputStream in) throws IOException {
        Map<String, Object> m = new TreeMap<>();
        while (true) {
            int p = in.read();
            if (p == 'e') {
                return m;
            }
            if (p == -1) {
                throw new EOFException();
            }
            in.unread(p);
            byte[] key = (byte[]) readBencode(in);
            m.put(new String(key, StandardCharsets.UTF_8), readBencode(in));
        }
    }

    private static byte[] readString(PushbackInputStream in, int first) throws IOException {
        StringBuilder len = new StringBuilder();
        int d = first;
        while (d != ':') {
            if (d == -1) {
                throw new EOFException();
            }
            len.append((char) d);
            d = in.read();
        }
        int n = Integer.parseInt(len.toString());
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) {
                throw new EOFException();
            }
            off += r;
        }
        return buf;
    }

    // --- minimal bencode writer (string keys/values only) ------------------------

    private static void sendDict(OutputStream out, String... kv) throws IOException {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sorted.put(kv[i], kv[i + 1]);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('d');
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            writeBString(b, e.getKey());
            writeBString(b, e.getValue());
        }
        b.write('e');
        out.write(b.toByteArray());
        out.flush();
    }

    private static void writeBString(ByteArrayOutputStream b, String s) throws IOException {
        byte[] by = s.getBytes(StandardCharsets.UTF_8);
        b.write(Integer.toString(by.length).getBytes(StandardCharsets.US_ASCII));
        b.write(':');
        b.write(by);
    }

    private NreplClient() {
    }
}
