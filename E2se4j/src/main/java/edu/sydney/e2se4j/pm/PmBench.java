package edu.sydney.e2se4j.pm;

import edu.sydney.e2se4j.Client;
import edu.sydney.e2se4j.Constants;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

// Measurement harness (sprint W7). SCAFFOLDING: it measures, it protects nothing, and nothing in the manager depends on it.
// It repeats the manager's store and retrieve N+1 times inside ONE running program (one JVM, one S3 connection) and times
// each phase ("lap") with System.nanoTime, so the numbers are the steady cost of each phase, not the JVM start-up that every
// separate command-line call pays equally. Run 0 is the warm-up (cold JVM, no open connections) and is flagged, not hidden.
//
//   plain  bare S3 PUT/GET of the file bytes to <uid>/plain, no E2SE at all             (baseline A)
//   gcm    sealed vault, no head: take, seal, put / take, get, open                    (the manager with --no-head)
//   head   sealed vault with the version head: the manager's real store and retrieve  (the thesis configuration)
//
// The head's cost = head minus gcm: on store the laps head_get and head_update (fingerprint + compare-and-swap round trip),
// on retrieve the laps head_get and verify (version and fingerprint compared with the head). Each head request opens its
// own TLS connection to the key server, exactly as the manager does.
public class PmBench {
    static final String CSV_HEADER = "mode,run,warmup,vault_bytes,store_total_ns,take_ns,head_get_ns,seal_ns,head_update_ns,s3_put_ns,"
            + "retrieve_total_ns,r_take_ns,r_head_get_ns,s3_get_ns,verify_ns,open_ns,ok";

    /** The laps of one run, in nanoseconds. -1 = this lap does not exist in this mode (written as an empty CSV field). */
    static final class Laps {
        long storeTotal = -1, take = -1, headGet = -1, seal = -1, headUpdate = -1, s3Put = -1;
        long retrieveTotal = -1, rTake = -1, rHeadGet = -1, s3Get = -1, verify = -1, open = -1;
        boolean ok;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("gen")) {
            gen(args[1], Integer.parseInt(args[2]));
            return;
        }
        if (args.length != 5) { usage(); System.exit(2); }
        String mode = args[0], uid = args[1], file = args[2], csv = args[4];
        if (!mode.equals("plain") && !mode.equals("gcm") && !mode.equals("head")) { usage(); System.exit(2); }
        int n = Integer.parseInt(args[3]);
        if (!new java.io.File(file).isFile()) fail("no such file: " + file + " (make one with: PmBench gen " + file + " 100)");

        Properties prop = new Properties();                                       // the same four AWS strings the manager reads
        try (InputStream in = new FileInputStream("config.properties")) {
            prop.load(in);
        }
        String accessKeyId = prop.getProperty("accessKeyId");
        String secretKeyId = prop.getProperty("secretKeyId");
        String regionName  = prop.getProperty("regionName");
        String bucketName  = prop.getProperty("bucketName");

        String passphrase = null;
        if (!mode.equals("plain")) {                                               // plain never unlocks, so it needs no passphrase
            passphrase = System.getenv("E2SE_PP");
            if (passphrase == null) fail("set E2SE_PP to the passphrase of '" + uid + "' in this terminal first (the bench runs unattended)");
        }

        Client client = new Client(accessKeyId, secretKeyId, regionName, bucketName);
        AmazonS3 s3 = s3(accessKeyId, secretKeyId, regionName);                   // ONE S3 connection object for the whole campaign
        byte[] plain = Files.readAllBytes(Paths.get(file));

        Path csvPath = Paths.get(csv);
        boolean newFile = !Files.exists(csvPath);
        System.out.println("bench: mode=" + mode + " uid=" + uid + " file=" + file + " (" + plain.length + " bytes) runs=" + (n + 1)
                + " (run 0 = warm-up) csv=" + csv);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (newFile) out.println(CSV_HEADER);
            for (int run = 0; run <= n; run++) {
                Laps l = new Laps();
                try {
                    storeOnce(mode, run, client, s3, bucketName, uid, passphrase, plain, l);
                    retrieveOnce(mode, client, s3, bucketName, uid, passphrase, plain, l);
                } catch (Exception e) {                                            // fail closed: say which run and why, keep the rows so far
                    fail("run " + run + " failed: " + e + " (rows so far are in " + csv + "; is the key server running, is '" + uid + "' registered, is E2SE_PP its passphrase?)");
                }
                out.println(csvRow(mode, run, plain.length, l));
                out.flush();                                                        // every row reaches the disk at once, so a stopped campaign loses nothing
                System.out.println(terminalLine(mode, run, l));
                if (!l.ok) fail("run " + run + ": the retrieved bytes differ from " + file + "; stopping");
            }
        }
        System.out.println("wrote " + (n + 1) + " rows (run 0 = warm-up) to " + csv);
    }

    /** One store, cut into laps. plain: PUT the file bytes. gcm: unlock, seal as version run+1, PUT; the key server is never told.
     *  head: unlock, HEAD_GET, seal as n+1, fingerprint + HEAD_UPDATE (compare-and-swap on n), PUT: the manager's store with the key server's number as the base. */
    static void storeOnce(String mode, int run, Client client, AmazonS3 s3, String bucket, String uid, String passphrase, byte[] plain, Laps l) throws Exception {
        long t0 = System.nanoTime();
        if (mode.equals("plain")) {
            long a = System.nanoTime();
            upload(s3, bucket, uid + "/plain", plain);                              // the generated vault holds no real password; this is the only mode that uploads plaintext
            l.s3Put = System.nanoTime() - a;
            l.storeTotal = System.nanoTime() - t0;
            return;
        }
        long a = System.nanoTime();
        byte[] mk = client.take(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME,
                uid, passphrase, bucket, uid + "/rid", uid + "/sid");               // the unlock: two salts from S3, the login token to the key server, the locked master key back, opened
        byte[] t = client.getLastT();
        l.take = System.nanoTime() - a;

        int base = run;                                                             // gcm: a number of our own, nobody checks it
        if (mode.equals("head")) {
            a = System.nanoTime();
            Head head = Head.get(client, uid, t);                                   // round trip 1: "what is the latest?"
            l.headGet = System.nanoTime() - a;
            base = head.n;
        }
        a = System.nanoTime();
        byte[] blob = SealedVault.seal(mk, uid, base + 1, plain);
        l.seal = System.nanoTime() - a;

        if (mode.equals("head")) {
            a = System.nanoTime();
            byte[] h = SealedVault.fingerprint(blob);
            Head conflict = Head.update(client, uid, t, base, base + 1, h);         // round trip 2: reserve the number (compare-and-swap)
            l.headUpdate = System.nanoTime() - a;
            if (conflict != null) throw new IOException("head update refused: the key server is at version " + conflict.n + ", expected " + base);
        }
        a = System.nanoTime();
        upload(s3, bucket, uid + "/vault", blob);
        l.s3Put = System.nanoTime() - a;
        l.storeTotal = System.nanoTime() - t0;
    }

    /** One retrieve, cut into laps. plain: GET. gcm: unlock, GET, open. head: unlock, HEAD_GET, GET, verify (version and fingerprint against the head), open.
     *  Every mode checks that the bytes that came back equal the file (column ok). */
    static void retrieveOnce(String mode, Client client, AmazonS3 s3, String bucket, String uid, String passphrase, byte[] expected, Laps l) throws Exception {
        long t0 = System.nanoTime();
        if (mode.equals("plain")) {
            long a = System.nanoTime();
            byte[] got = download(s3, bucket, uid + "/plain");
            l.s3Get = System.nanoTime() - a;
            l.ok = Arrays.equals(got, expected);
            l.retrieveTotal = System.nanoTime() - t0;
            return;
        }
        long a = System.nanoTime();
        byte[] mk = client.take(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME,
                uid, passphrase, bucket, uid + "/rid", uid + "/sid");
        byte[] t = client.getLastT();
        l.rTake = System.nanoTime() - a;

        Head head = null;
        if (mode.equals("head")) {
            a = System.nanoTime();
            head = Head.get(client, uid, t);                                        // the latest version and its fingerprint, from the party just logged in to
            l.rHeadGet = System.nanoTime() - a;
        }
        a = System.nanoTime();
        byte[] blob = download(s3, bucket, uid + "/vault");
        l.s3Get = System.nanoTime() - a;

        if (mode.equals("head")) {
            a = System.nanoTime();
            int nD = SealedVault.version(blob);
            boolean matches = nD == head.n && MessageDigest.isEqual(SealedVault.fingerprint(blob), head.h);
            l.verify = System.nanoTime() - a;
            if (!matches) throw new IOException("storage returned version " + nD + ", key server says " + head.n + " (or the fingerprints differ): refusing");
        }
        a = System.nanoTime();
        byte[] got = SealedVault.open(mk, uid, blob);                               // the seal check; a changed byte throws Refused
        l.open = System.nanoTime() - a;
        l.ok = Arrays.equals(got, expected);
        l.retrieveTotal = System.nanoTime() - t0;
    }

    /** A reproducible vault of k made-up entries: site001 TAB user001 TAB pw-0001-xxxxxxxx. No real password is ever in it. */
    static void gen(String file, int k) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= k; i++) {
            long mix = (i * 2654435761L) & 0xffffffffL;                             // a fixed scramble of i, so the file is the same on every machine
            sb.append(String.format(Locale.ROOT, "site%03d\tuser%03d\tpw-%04d-%08x\n", i, i, i, mix));
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(Paths.get(file), bytes);
        System.out.println("wrote " + k + " entries to " + file + " (" + bytes.length + " bytes)");
    }

    static String csvRow(String mode, int run, int vaultBytes, Laps l) {
        return mode + "," + run + "," + (run == 0 ? 1 : 0) + "," + vaultBytes + ","
                + ns(l.storeTotal) + "," + ns(l.take) + "," + ns(l.headGet) + "," + ns(l.seal) + "," + ns(l.headUpdate) + "," + ns(l.s3Put) + ","
                + ns(l.retrieveTotal) + "," + ns(l.rTake) + "," + ns(l.rHeadGet) + "," + ns(l.s3Get) + "," + ns(l.verify) + "," + ns(l.open) + ","
                + (l.ok ? 1 : 0);
    }

    private static String ns(long v) { return v < 0 ? "" : Long.toString(v); }

    static String terminalLine(String mode, int run, Laps l) {
        StringBuilder sb = new StringBuilder();
        sb.append(mode).append(" run ").append(run).append(run == 0 ? " (warm-up)" : "").append(": store ").append(ms(l.storeTotal)).append(" ms (");
        lap(sb, "take", l.take); lap(sb, "head_get", l.headGet); lap(sb, "seal", l.seal); lap(sb, "head_update", l.headUpdate); lap(sb, "s3_put", l.s3Put);
        sb.append(")  retrieve ").append(ms(l.retrieveTotal)).append(" ms (");
        lap(sb, "take", l.rTake); lap(sb, "head_get", l.rHeadGet); lap(sb, "s3_get", l.s3Get); lap(sb, "verify", l.verify); lap(sb, "open", l.open);
        sb.append(")  ").append(l.ok ? "ok" : "MISMATCH");
        return sb.toString().replace("(, ", "(");
    }

    private static void lap(StringBuilder sb, String name, long v) {
        if (v < 0) return;
        if (sb.charAt(sb.length() - 1) != '(') sb.append(", ");
        sb.append(name).append(' ').append(ms(v));
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.1f", nanos / 1e6); }

    private static byte[] download(AmazonS3 s3, String bucket, String key) throws IOException {
        S3Object object = s3.getObject(new GetObjectRequest(bucket, key));
        try {
            return IOUtils.toByteArray(object.getObjectContent());
        } finally {
            object.close();
        }
    }

    private static void upload(AmazonS3 s3, String bucket, String key, byte[] bytes) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(bytes.length);
        s3.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes), meta));
    }

    private static AmazonS3 s3(String accessKeyId, String secretKeyId, String regionName) {
        return AmazonS3ClientBuilder.standard().withRegion(regionName)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretKeyId)))
                .build();
    }

    private static void fail(String msg) {
        System.err.println("error: " + msg);
        System.exit(1);
    }

    private static void usage() {
        System.out.println("usage: PmBench gen <file> <entries>              write a vault of <entries> made-up lines (no real password in it)");
        System.out.println("       PmBench <mode> <uid> <file> <N> <csv>    store + retrieve <file> N+1 times (run 0 = warm-up), one CSV row per run, appended to <csv>");
        System.out.println("  modes: plain = bare S3 PUT/GET of the file, no E2SE | gcm = sealed vault, no head (as --no-head) | head = sealed vault with the version head");
        System.out.println("  needs: the key server running; 'PasswordManager init <uid>' done; E2SE_PP set to that passphrase (gcm and head)");
    }
}
