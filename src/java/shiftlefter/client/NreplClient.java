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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShiftLefter warm-path dispatch client (sl-x6r).
 *
 * VERSION-INTERNAL: a private protocol between {@code bin/sl} and the jar it ships
 * with. Not part of the locked 0.5 surface; may change in any release. Lives INSIDE
 * the uberjar so it is always version-matched with the daemon it talks to.
 *
 * <p>Why Java and not babashka: a class with zero Clojure on its classpath never
 * pays Clojure's ~800ms init — bare-JVM boot of one class out of the uberjar is
 * ~30ms, the same order as bb, with no extra install (users already have a JRE).
 *
 * <p>Invocation: {@code java -cp shiftlefter.jar shiftlefter.client.NreplClient
 * <port> <cwd> <argv...>}. Connects to the daemon's nREPL server on
 * {@code 127.0.0.1:<port>}, evaluates a form that runs
 * {@code shiftlefter.daemon/dispatch!} with stdout/stderr CAPTURED into byte buffers
 * (NOT streamed — the bundled nREPL's CallbackBufferedOutputStream throws on any
 * multibyte flush; see sl-x6r / sl-qwf), base64s them into the eval value, then
 * writes those raw bytes to this process's stdout/stderr and exits with the
 * dispatched command's exit code.
 *
 * <p>Exit codes: 0/1/2/3 are the dispatched command's (passed through). 69 means
 * "could not reach the daemon" — {@code bin/sl} treats it as a signal to retry
 * cold (nothing has been written to stdout yet, so a cold retry is safe).
 */
public final class NreplClient {

    /** bin/sl contract: "daemon unreachable, fall back to a cold java -jar run". */
    private static final int EX_DAEMON_UNREACHABLE = 69;

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
            // Connect-refused or any transport failure. Because output is buffered
            // (nothing reaches stdout until the value is decoded at the very end), a
            // failure here means we have written nothing — safe for bin/sl to retry
            // cold. Surface a quiet note to stderr only when it is a real fault, not
            // a plain "no daemon" refusal.
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

            // 2b. normal: eval the capture-and-base64 dispatch form.
            sendDict(out, "op", "eval", "session", session, "id", "2",
                     "code", buildCode(cwd, argv));
            return awaitResult(in);
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
     * Read eval responses until status "done" for our id, writing any stray
     * out/err (defensive — the captured form emits none) and decoding the value.
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
                System.out.write((byte[]) o);
                System.out.flush();
            }
            Object e = m.get("err");
            if (e instanceof byte[]) {
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

    /** Decode the {:exit n :out b64 :err b64} value, write the bytes, return exit. */
    private static int emit(String value) throws IOException {
        if (value == null) {
            System.err.println("sl: warm dispatch returned no value; falling back to cold");
            return EX_DAEMON_UNREACHABLE;
        }
        Matcher exit = Pattern.compile(":exit\\s+(-?\\d+)").matcher(value);
        if (!exit.find()) {
            System.err.println("sl: unparseable warm response; falling back to cold");
            return EX_DAEMON_UNREACHABLE;
        }
        writeBase64(System.out, group(value, ":out\\s+\"([A-Za-z0-9+/=]*)\""));
        writeBase64(System.err, group(value, ":err\\s+\"([A-Za-z0-9+/=]*)\""));
        System.out.flush();
        System.err.flush();
        return Integer.parseInt(exit.group(1));
    }

    private static void writeBase64(OutputStream dst, String b64) throws IOException {
        if (b64 != null && !b64.isEmpty()) {
            dst.write(Base64.getDecoder().decode(b64));
        }
    }

    private static String group(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
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
     * The Clojure form sent to the daemon. Binds out/err to byte buffers so the
     * dispatched command's output never reaches nREPL's (multibyte-broken) output
     * stream, then base64s the captured bytes into the returned map. dispatch!
     * already catches Throwable -> {:exit 3}; the extra try is belt-and-suspenders
     * for the binding/capture scaffolding itself.
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
        return "(let [ob (java.io.ByteArrayOutputStream.)"
             + "      eb (java.io.ByteArrayOutputStream.)"
             + "      ow (java.io.PrintWriter. (java.io.OutputStreamWriter. ob \"UTF-8\") true)"
             + "      ew (java.io.PrintWriter. (java.io.OutputStreamWriter. eb \"UTF-8\") true)"
             + "      ex (binding [*out* ow *err* ew]"
             + "           (let [r (try (shiftlefter.daemon/dispatch! {:argv " + vec
             +                  " :cwd " + cljString(cwd) + "})"
             + "                        (catch Throwable t (.printStackTrace t ew) {:exit 3}))]"
             + "             (.flush ow) (.flush ew) (:exit r)))"
             + "      enc (java.util.Base64/getEncoder)]"
             + "  {:exit ex"
             + "   :out (.encodeToString enc (.toByteArray ob))"
             + "   :err (.encodeToString enc (.toByteArray eb))})";
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
