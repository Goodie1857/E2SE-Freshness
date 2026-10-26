package edu.sydney.e2se4j.pm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// Persistence for the key server K (thesis extension; design/0002 item 6; sprint W4). One file per user under kstate/,
// in the folder K is started from (E2se4j/): kstate/<sha256(uid) in hex>.properties, holding
//   uid               the user id in clear (the FILE NAME is a hash of it, so an odd uid cannot become a path and names stay short)
//   t                 the login token from Register                         (Base64)
//   tao, ct           the tag and the locked master key from Give/Deposit   (Base64; absent until the deposit)
//   headN, headHash   the version head                                      (absent until the first store)
// Every change rewrites the whole file: write kstate/<name>.properties.tmp, then rename it over the old file in one step,
// BEFORE the reply byte leaves K. So a crash never leaves a half-written record, and K never confirms what it has not saved.
// No fsync: a power cut in the same instant can still lose the last write (the thesis says so). Loaded once at start-up.
// These files hold K's secrets (t is a verifier for the passphrase): kstate/ is in .gitignore and must stay out of git.
public final class KState {
    static final String DIR = "kstate";

    /** One user's saved record, as read from its file. */
    public static final class Record {
        public final String uid;
        public final byte[] t;
        public final byte[] tao, ct;      // null until the deposit
        public final int headN;           // 0 = no head yet
        public final byte[] headHash;     // null while headN = 0

        Record(String uid, byte[] t, byte[] tao, byte[] ct, int headN, byte[] headHash) {
            this.uid = uid; this.t = t; this.tao = tao; this.ct = ct; this.headN = headN; this.headHash = headHash;
        }
    }

    /** Mirror of the files, keyed by uid, so a partial update (deposit, head) can rewrite the whole record. */
    private static final Map<String, Record> RECORDS = new HashMap<String, Record>();

    private KState() {}

    /** Called once when K starts: read every file under kstate/, fill the mirror, put the heads back into Head's table, and return the records so AuthServer can refill its two maps. */
    public static List<Record> loadAll() throws IOException {
        Path dir = Paths.get(DIR);
        List<Record> out = new ArrayList<Record>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.properties")) {
                for (Path p : files) {
                    Properties props = new Properties();
                    try (InputStream in = Files.newInputStream(p)) {
                        props.load(in);
                    }
                    Record r = new Record(props.getProperty("uid"), b64(props.getProperty("t")), b64(props.getProperty("tao")), b64(props.getProperty("ct")),
                            Integer.parseInt(props.getProperty("headN", "0")), b64(props.getProperty("headHash")));
                    RECORDS.put(r.uid, r);
                    if (r.headN > 0) Head.restore(r.uid, new Head(r.headN, r.headHash));
                    out.add(r);
                }
            }
        }
        System.out.println("kstate: " + out.size() + " user record(s) loaded from " + dir.toAbsolutePath());
        return out;
    }

    /** Register accepted: a new record with the login token. */
    public static void saveRegister(String uid, byte[] t) throws IOException {
        write(new Record(uid, t, null, null, 0, null));
    }

    /** Deposit accepted: add the tag and the locked master key to the user's record. */
    public static void saveDeposit(String uid, byte[] tao, byte[] ct) throws IOException {
        Record r = existing(uid);
        write(new Record(uid, r.t, tao, ct, r.headN, r.headHash));
    }

    /** Head moved: record the new (n, h). */
    public static void saveHead(String uid, int n, byte[] h) throws IOException {
        Record r = existing(uid);
        write(new Record(uid, r.t, r.tao, r.ct, n, h));
    }

    private static Record existing(String uid) {
        Record r = RECORDS.get(uid);
        if (r == null) {                                                                 // cannot happen after a clean start (DEPOSIT and HEAD_* are refused for unregistered users); never crash K over it
            System.out.println("kstate: no saved record for " + uid + " (registered before persistence existed?); saving what is known.");
            r = new Record(uid, new byte[0], null, null, 0, null);
        }
        return r;
    }

    private static void write(Record r) throws IOException {
        RECORDS.put(r.uid, r);
        Properties props = new Properties();
        props.setProperty("uid", r.uid);
        props.setProperty("t", b64(r.t));
        if (r.tao != null) {
            props.setProperty("tao", b64(r.tao));
            props.setProperty("ct", b64(r.ct));
        }
        if (r.headN > 0) {
            props.setProperty("headN", Integer.toString(r.headN));
            props.setProperty("headHash", b64(r.headHash));
        }
        Path dir = Paths.get(DIR);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName(r.uid));
        Path tmp = dir.resolve(fileName(r.uid) + ".tmp");                                 // "*.properties" does not match the .tmp name, so a leftover tmp is never loaded
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "E2SE key server record for one user; stop K before editing");
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); // the one-step swap
    }

    static String fileName(String uid) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString() + ".properties";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                                          // SHA-256 is in every JDK
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] b64(String s) { return s == null ? null : Base64.getDecoder().decode(s); }
}
