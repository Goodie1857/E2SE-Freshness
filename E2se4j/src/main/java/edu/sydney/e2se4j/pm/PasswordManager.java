package edu.sydney.e2se4j.pm;

import edu.sydney.e2se4j.Client;
import edu.sydney.e2se4j.Constants;
import edu.sydney.e2se4j.Utils;

import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

public class PasswordManager {
    // exit codes: 0 ok; 1 error; 2 usage; 3 store conflict (your copy is not the latest version); 4 stale or substituted vault (storage and key server disagree); 5 the sealed vault refused to open
    static final int EXIT_CONFLICT = 3;
    static final int EXIT_STALE = 4;
    static final int EXIT_REFUSED = 5;

    /** Copies experiment (design/0003): the second data server's bucket, from bucketName2 in config.properties; null = the one-bucket behaviour of tag thesis-final. Set by main only; PmBench never sets it. */
    static String bucket2 = null;
    /** The S3 client used for bucket 2: a second credential (accessKeyId2 / secretKeyId2 in config.properties) when present, so that each bucket has its own keyring; otherwise the same client as bucket 1. */
    static AmazonS3 s3b = null;

    /** Command-line switches, accepted anywhere after the command word. */
    static final class Flags {
        boolean force;   // store: take the key server's current version as the base even if the local copy's sidecar says otherwise (the human override; no merge)
        boolean noHead;  // skip the version head entirely: no key server round trips, no freshness check (evaluation baseline, and the demo's control)
    }

    public static void main(String[] args) throws Exception {
        Flags flags = new Flags();
        List<String> words = new ArrayList<String>();   // the arguments with the switches taken out
        for (String a : args) {
            if (a.equals("--force")) flags.force = true;
            else if (a.equals("--no-head")) flags.noHead = true;
            else if (a.startsWith("--")) { usage(); System.exit(2); }
            else words.add(a);
        }
        if (words.size() < 2) {
            usage();
            System.exit(2);
        }
        String cmd = words.get(0);
        String uid = words.get(1);

        Properties prop = new Properties();
        try (InputStream in = new FileInputStream("config.properties")) {
            prop.load(in);
        }
        String accessKeyId = prop.getProperty("accessKeyId");
        String secretKeyId = prop.getProperty("secretKeyId");
        String regionName  = prop.getProperty("regionName"); // which AWS datacentre
        String bucketName  = prop.getProperty("bucketName"); // which storage container
        String second      = prop.getProperty("bucketName2"); // copies experiment: a second bucket under the same credential and region (mechanism only: one credential controls both)
        bucket2 = (second == null || second.trim().isEmpty()) ? null : second.trim();
        String accessKeyId2 = prop.getProperty("accessKeyId2"), secretKeyId2 = prop.getProperty("secretKeyId2"); // optional: the second data server's own credential

        Client client = new Client(accessKeyId, secretKeyId, regionName, bucketName);
        AmazonS3 s3 = s3(accessKeyId, secretKeyId, regionName); // one S3 connection object for the whole command
        s3b = (bucket2 == null) ? null
            : (accessKeyId2 != null && !accessKeyId2.trim().isEmpty()) ? s3(accessKeyId2.trim(), secretKeyId2.trim(), regionName) // bucket 2 under its own credential
            : s3;                                                                                                   // or the same credential (mechanism-only demo)

        // three S3 object names; all three sit at the data server, in plaintext, under this user (uid)'s prefix
        String key0 = uid + "/sid";        // first salt s_id, 16 random bytes: written once by init (Register); read back by every unlock (Take) to re-derive the login token t from the passphrase
        String key1 = uid + "/rid";        // second salt r_id: written once by init (Give); read back by every unlock to re-derive k1 (locks the master key) and k2 (its tag) from the passphrase
        // the vault itself lives at uid + "/vault": written by store, read by retrieve (see store/retrieve below)


        switch (cmd) {
            case "init": {
                String passphrase = promptPassphrase(true);
                try {
                    client.register(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME, uid, passphrase, bucketName, key0); // makes random salt and uploads as key0, derives login token t from passphrase + salt, files login token t at the key server
                    client.give(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME, uid, passphrase, bucketName, key1, key0); // makes second salt key1, fetches key0 back, creates mk and locks it under key derived from passphrase + second salt, sends the locked box + its tag to the key server
                } catch (Exception e) {
                    fail("init failed: is the key server running, and is '" + uid + "' new on this key server?");
                }
                System.out.println("vault key created for " + uid);
                break;
            }

            case "store": { // DEM half: seal the vault file under a key made from mk and hand S3 only the sealed blob, after reserving its version number at the key server
                String file = requireFileArg(words);
                if (!new File(file).isFile()) fail("no such file: " + file);
                String passphrase = promptPassphrase(false); // a typo here fails the unlock, it can't lock us out
                store(client, s3, bucketName, uid, file, passphrase, flags);
                break;
            }

            case "retrieve": {
                String file = requireFileArg(words);
                String passphrase = promptPassphrase(false);
                try {
                    retrieve(client, s3, bucketName, uid, file, passphrase, flags);
                } catch (SealedVault.Refused e) { // the seal did not verify, or the object is not a sealed vault: say so and write nothing
                    fail("vault refused: " + uid + "/vault " + e.getMessage() + ". Nothing was written to " + file + ".", EXIT_REFUSED);
                }
                break;
            }

            case "add": case "edit": case "rm": case "list": case "show": { // the entry commands (sprint W5): one line of the vault at a time; plaintext in memory only; every change = the next version at the key server
                if (flags.force || flags.noHead) { usage(); System.exit(2); }    // these commands always use the head, and there is no local file for --force to apply to
                try {
                    entryCommand(cmd, words, client, s3, bucketName, uid);
                } catch (SealedVault.Refused e) {                                  // the seal did not verify, or the object is not a sealed vault: say so, change nothing
                    fail("vault refused: " + uid + "/vault " + e.getMessage() + ".", EXIT_REFUSED);
                } catch (VaultEntries.BadFormat e) {                               // the vault's text or a typed field is not in the one-line-per-entry form
                    fail(e.getMessage());
                }
                break;
            }

            default: {
                usage();
                System.exit(2);
            }
        }
    }

    /** store: unlock mk (Take), reserve the next version at the key server, seal the whole file in memory as that version, upload. Static so the benchmark can call it directly. */
    static void store(Client client, AmazonS3 s3, String bucket, String uid, String file, String passphrase, Flags flags) throws Exception {
        String vaultObject = uid + "/vault";                                        // the S3 object name (S3 calls it a "key"; it is a name, not a cryptographic key)
        byte[] mk = unlock(client, uid, passphrase, bucket, uid + "/rid", uid + "/sid"); // Take: rebuild mk in memory from passphrase + both servers; every failure below this line is a party saying no
        byte[] t = client.getLastT();                                               // the login token Take just derived: the head requests authenticate with it
        byte[] plain = Files.readAllBytes(Paths.get(file));                        // the whole vault file in memory; no scratch file touches the disk
        Integer sidecar = readSidecar(file, uid);                                   // which version this local copy came from, if known

        if (flags.noHead) {                                                         // baseline: seal and upload, tell the key server nothing
            int n = sidecar == null ? 1 : sidecar + 1;
            byte[] blob = SealedVault.seal(mk, uid, n, plain);
            writeSidecar(file, uid, n);
            uploadCopies(s3, bucket, vaultObject, blob);
            System.out.println("vault stored as " + vaultObject + ": version " + n + ", " + blob.length + " bytes, sha256 " + Utils.bytesToHex(SealedVault.fingerprint(blob)) + " (no head: the key server was not told)");
            return;
        }

        Head head = headGet(client, uid, t);                                        // round trip 1: "what is the latest?"
        int base = (flags.force || sidecar == null) ? head.n : sidecar;             // the version I believe I am editing
        if (base != head.n) {
            fail("vault is at version " + head.n + " at the key server, your copy is from version " + base + ": retrieve, re-apply your edit, store again (or store --force to overwrite from this copy)", EXIT_CONFLICT);
        }
        int n = base + 1;
        byte[] blob = SealedVault.seal(mk, uid, n, plain);                          // the header carries n; the seal pins it
        byte[] h = SealedVault.fingerprint(blob);
        Head conflict = headUpdate(client, uid, t, base, n, h);                     // round trip 2: reserve n at the key server (compare-and-swap on base)
        if (conflict != null) {
            fail("vault moved to version " + conflict.n + " at the key server while storing (another device?): retrieve, re-apply your edit, store again", EXIT_CONFLICT);
        }
        writeSidecar(file, uid, n);                                                 // BEFORE the upload: if we crash now, our own re-run starts from n and is not locked out
        uploadCopies(s3, bucket, vaultObject, blob);                                      // only sealed bytes ever leave this machine
        System.out.println("vault stored as " + vaultObject + ": version " + n + ", " + blob.length + " bytes, sha256 " + Utils.bytesToHex(h) + "; head at the key server moved " + base + " -> " + n);
    }

    /** retrieve: unlock mk (Take), fetch the head, download <uid>/vault into memory, accept it only if version and fingerprint match the head, open it (any altered byte -> Refused), and only then write the file. */
    static void retrieve(Client client, AmazonS3 s3, String bucket, String uid, String file, String passphrase, Flags flags) throws Exception {
        String vaultObject = uid + "/vault";
        byte[] mk = unlock(client, uid, passphrase, bucket, uid + "/rid", uid + "/sid");
        byte[] t = client.getLastT();

        if (flags.noHead) {                                                         // control: open whatever storage serves, as long as the seal verifies (an OLD genuine vault passes)
            byte[] blob = download(s3, bucket, vaultObject);
            if (blob == null) fail("no vault stored yet for " + uid + " - run store first");
            byte[] plain = SealedVault.open(mk, uid, blob);                         // throws Refused if the seal does not verify; then nothing below runs
            Files.write(Paths.get(file), plain);
            writeSidecar(file, uid, SealedVault.version(blob));
            System.out.println("vault retrieved to " + file + ": version " + SealedVault.version(blob) + ", " + blob.length + " bytes, sha256 " + Utils.bytesToHex(SealedVault.fingerprint(blob)) + " (no head: the key server was not asked)");
            return;
        }

        Head head = headGet(client, uid, t);                                        // round trip 1: the latest version and its fingerprint, from the party I just logged in to
        if (head.n == 0) fail("no vault stored yet for " + uid + " (the key server holds no version) - run store first");
        byte[] blob = bucket2 == null ? download(s3, bucket, vaultObject)                                   // one data server: exactly as before
                                      : currentCopy(s3, bucket, vaultObject, head, " Nothing was written to " + file + "."); // two: the copy that matches the head, else exit 4
        if (blob == null) fail("key server has version " + head.n + ", storage has nothing: the vault object is missing. Nothing was written to " + file + ".", EXIT_STALE);
        int nD = SealedVault.version(blob);                                         // the version storage claims (Refused if this is not a sealed vault at all)
        if (nD < head.n) fail("storage returned version " + nD + ", key server says " + head.n + " (rollback, or an interrupted store). Nothing was written to " + file + ".", EXIT_STALE);
        if (nD > head.n) fail("vault changed while retrieving (storage has version " + nD + ", key server says " + head.n + "): retry. Nothing was written to " + file + ".", EXIT_STALE);
        if (!MessageDigest.isEqual(SealedVault.fingerprint(blob), head.h)) fail("same version number " + head.n + ", different bytes (substituted or tampered). Nothing was written to " + file + ".", EXIT_STALE);
        byte[] plain = SealedVault.open(mk, uid, blob);                             // the seal check; throws Refused -> exit 5
        Files.write(Paths.get(file), plain);                                        // the file the user asked for, written only after both checks passed
        writeSidecar(file, uid, head.n);
        System.out.println("vault retrieved to " + file + ": version " + head.n + ", " + blob.length + " bytes, sha256 " + Utils.bytesToHex(head.h) + "; matches the head at the key server");
    }

    // ------------------------------------------------------------------ the entry commands (sprint W5) ------------------------------------------------------------------

    /** What an entry command starts from: the unlocked master key, the login token, the head, and the vault's text (empty when the key server holds no version yet). */
    static final class Opened {
        final byte[] mk, t;
        final Head head;
        final byte[] plain;
        Opened(byte[] mk, byte[] t, Head head, byte[] plain) { this.mk = mk; this.t = t; this.head = head; this.plain = plain; }
    }

    /** Unlock, fetch the head, download, accept the blob only if its version and fingerprint match the head, open it. The same checks retrieve makes, written once for the five entry commands. Head at 0: no download, an empty vault. */
    static Opened openLatest(Client client, AmazonS3 s3, String bucket, String uid, String passphrase) throws Exception {
        String vaultObject = uid + "/vault";
        byte[] mk = unlock(client, uid, passphrase, bucket, uid + "/rid", uid + "/sid"); // Take: rebuild mk in memory from passphrase + both servers
        byte[] t = client.getLastT();
        Head head = headGet(client, uid, t);                                        // round trip 1: the latest version and its fingerprint. This n is the base every change starts from; there is no sidecar because there is no local file
        if (head.n == 0) return new Opened(mk, t, head, new byte[0]);              // nothing stored yet: the vault starts empty
        byte[] blob = bucket2 == null ? download(s3, bucket, vaultObject)                                   // one data server: exactly as before
                                      : currentCopy(s3, bucket, vaultObject, head, "");                   // two: the copy that matches the head, else exit 4
        if (blob == null) fail("key server has version " + head.n + ", storage has nothing: the vault object is missing.", EXIT_STALE);
        int nD = SealedVault.version(blob);                                         // the version storage claims (Refused if this is not a sealed vault at all)
        if (nD < head.n) fail("storage returned version " + nD + ", key server says " + head.n + " (rollback, or an interrupted store).", EXIT_STALE);
        if (nD > head.n) fail("vault changed while reading it (storage has version " + nD + ", key server says " + head.n + "): retry.", EXIT_STALE);
        if (!MessageDigest.isEqual(SealedVault.fingerprint(blob), head.h)) fail("same version number " + head.n + ", different bytes (substituted or tampered).", EXIT_STALE);
        return new Opened(mk, t, head, SealedVault.open(mk, uid, blob));          // the seal check; throws Refused -> exit 5 in main
    }

    /** Seal the new text as the next version, reserve that number at the key server (compare-and-swap on the base), upload, report. The entry commands' half of store, without the sidecar. */
    static void publish(Client client, AmazonS3 s3, String bucket, String uid, Opened o, byte[] newPlain, String whatChanged) throws Exception {
        String vaultObject = uid + "/vault";
        int base = o.head.n;
        int n = base + 1;
        byte[] blob = SealedVault.seal(o.mk, uid, n, newPlain);                     // the header carries n; the seal pins it
        byte[] h = SealedVault.fingerprint(blob);
        Head conflict = headUpdate(client, uid, o.t, base, n, h);                   // round trip 2: reserve n; refused if another device moved the head since round trip 1
        if (conflict != null) fail("vault moved to version " + conflict.n + " at the key server while changing it (another device?): run the command again", EXIT_CONFLICT);
        uploadCopies(s3, bucket, vaultObject, blob);                                      // only sealed bytes ever leave this machine
        System.out.println(whatChanged);
        System.out.println("vault stored as " + vaultObject + ": version " + n + ", " + blob.length + " bytes, sha256 " + Utils.bytesToHex(h) + "; head at the key server moved " + base + " -> " + n);
    }

    /** add / edit / rm / list / show. Each: unlock, head, download, check, open in memory; then change the lines and publish the next version (add, edit, rm), or print (list, show). */
    static void entryCommand(String cmd, List<String> words, Client client, AmazonS3 s3, String bucket, String uid) throws Exception {
        int need = cmd.equals("add") ? 5 : cmd.equals("edit") ? 4 : cmd.equals("list") ? 2 : 3; // words = the command, the uid, then the fields
        if (words.size() != need) { usage(); System.exit(2); }
        String name = need > 2 ? words.get(2) : null;
        if (name != null) VaultEntries.checkField("name", name, true);
        String passphrase = promptPassphrase(false);
        Opened o = openLatest(client, s3, bucket, uid, passphrase);
        if (o.head.n == 0 && !cmd.equals("add")) fail("no vault stored yet for " + uid + " (the key server holds no version) - run add first");
        List<VaultEntries.Entry> entries = VaultEntries.parse(o.plain);
        int at = name == null ? -1 : VaultEntries.find(entries, name);
        switch (cmd) {
            case "add": {
                String username = words.get(3), password = words.get(4);
                VaultEntries.checkField("username", username, false);
                VaultEntries.checkField("password", password, false);
                if (at >= 0) fail("an entry named '" + name + "' already exists in " + uid + "/vault; use edit");
                entries.add(new VaultEntries.Entry(name, username, password));
                publish(client, s3, bucket, uid, o, VaultEntries.render(entries), "added " + name);
                break;
            }
            case "edit": {
                String password = words.get(3);
                VaultEntries.checkField("password", password, false);
                if (at < 0) fail("no entry named '" + name + "' in " + uid + "/vault");
                VaultEntries.Entry old = entries.get(at);
                entries.set(at, new VaultEntries.Entry(old.name, old.username, password)); // the password only; name and username stay
                publish(client, s3, bucket, uid, o, VaultEntries.render(entries), "changed the password of " + name);
                break;
            }
            case "rm": {
                if (at < 0) fail("no entry named '" + name + "' in " + uid + "/vault");
                entries.remove(at);
                publish(client, s3, bucket, uid, o, VaultEntries.render(entries), "removed " + name);
                break;
            }
            case "list": {
                System.out.println("vault " + uid + "/vault: version " + o.head.n + ", " + entries.size() + (entries.size() == 1 ? " entry" : " entries"));
                for (VaultEntries.Entry e : entries) System.out.println(e.name + "\t" + e.username); // names and usernames only: no password reaches the screen unless show asks for it
                break;
            }
            case "show": {
                if (at < 0) fail("no entry named '" + name + "' in " + uid + "/vault");
                VaultEntries.Entry e = entries.get(at);
                System.out.println("vault " + uid + "/vault: version " + o.head.n + ", matches the head at the key server");
                System.out.println(e.name + "\t" + e.username + "\t" + e.password);   // the only place a password is printed: the terminal, never a file
                break;
            }
        }
    }

    // ------------------------------------------------------------------ copies experiment (design/0003; branch copies-experiment only) ------------------------------------------------------------------

    /** PUT the sealed blob to bucket 1 and, when bucketName2 is configured, to bucket 2 as well. Succeeds when at least one copy was written: the head has already committed at the key server, and one written copy is current. */
    static void uploadCopies(AmazonS3 s3, String bucket, String vaultObject, byte[] blob) {
        if (bucket2 == null) { upload(s3, bucket, vaultObject, blob); return; }    // one data server: exactly as before
        String[] names = { "bucket 1", "bucket 2" };
        String[] buckets = { bucket, bucket2 };
        int written = 0;
        for (int i = 0; i < 2; i++) {                                               // in series (the design says in parallel; the demonstration proves the mechanism, not the timing)
            try {
                upload(i == 0 ? s3 : s3b, buckets[i], vaultObject, blob);
                System.out.println(names[i] + " written");
                written++;
            } catch (Exception e) {
                System.out.println(names[i] + " failed: " + e.getMessage() + "; will be repaired on the next successful read");
            }
        }
        if (written == 0) fail("no copy was written: the key server is already at the new version, so readers see an interrupted store until the next store");
    }

    /** Two data servers: return the first copy whose version and fingerprint equal the head (bucket 1 first, bucket 2 on failover, repairing bucket 1 from it); refuse with exit 4 naming what each bucket returned when neither matches. */
    static byte[] currentCopy(AmazonS3 s3, String bucket, String vaultObject, Head head, String nothingWritten) throws Exception {
        byte[] b1 = download(s3, bucket, vaultObject);
        if (matchesHead(b1, head)) return b1;                                       // bucket 1 is current: the everyday path, nothing printed
        byte[] b2 = download(s3b, bucket2, vaultObject);
        if (matchesHead(b2, head)) {
            System.out.println("bucket 1 returned " + describe(b1) + ", key server says " + head.n + "; bucket 2 is current: recovered");
            try {
                upload(s3, bucket, vaultObject, b2);                                // repair with bytes just verified against the head: availability hygiene, not a security step
                System.out.println("bucket 1 repaired");
            } catch (Exception e) {
                System.out.println("bucket 1 not repaired: " + e.getMessage());
            }
            return b2;
        }
        fail("bucket 1 returned " + describe(b1) + ", bucket 2 returned " + describe(b2) + ", key server says " + head.n + " (rollback at both, or an interrupted store)." + nothingWritten, EXIT_STALE);
        return null; // never reached: fail() exits
    }

    /** True when the blob exists, its header says the head's version and its fingerprint is the head's. A blob that is not a sealed vault at all counts as not matching. */
    private static boolean matchesHead(byte[] blob, Head head) {
        if (blob == null) return false;
        try {
            return SealedVault.version(blob) == head.n && MessageDigest.isEqual(SealedVault.fingerprint(blob), head.h);
        } catch (Exception e) {
            return false;
        }
    }

    private static String describe(byte[] blob) {
        if (blob == null) return "nothing";
        try {
            return "version " + SealedVault.version(blob);
        } catch (Exception e) {
            return "something that is not a sealed vault";
        }
    }

    private static Head headGet(Client client, String uid, byte[] t) {
        try {
            return Head.get(client, uid, t);
        } catch (Exception e) {
            fail("could not read the version head from the key server: " + e.getMessage());
            return null; // never reached: fail() exits
        }
    }

    private static Head headUpdate(Client client, String uid, byte[] t, int nExpected, int nNew, byte[] h) {
        try {
            return Head.update(client, uid, t, nExpected, nNew, h);
        } catch (Exception e) {
            fail("could not update the version head at the key server: " + e.getMessage());
            return null; // never reached: fail() exits
        }
    }

    /** GET the object into memory; null if S3 says there is no such object (404). Any other S3 error (permissions, network) is thrown and shown. */
    private static byte[] download(AmazonS3 s3, String bucket, String key) throws IOException {
        try {
            S3Object object = s3.getObject(new GetObjectRequest(bucket, key));
            return IOUtils.toByteArray(object.getObjectContent());                  // the sealed blob, in memory only (never written to disk)
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        }
    }

    private static void upload(AmazonS3 s3, String bucket, String key, byte[] blob) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(blob.length);                                         // S3 wants the length up front when uploading from memory
        s3.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(blob), meta));
    }

    /** The sidecar <file>.version holds "uid n": the version this local copy came from. null when absent, written for another user, or unreadable. */
    static Integer readSidecar(String file, String uid) {
        Path p = Paths.get(file + ".version");
        if (!Files.isRegularFile(p)) return null;
        try {
            String[] parts = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim().split(" ");
            if (parts.length != 2 || !parts[0].equals(uid)) return null;            // another user's sidecar (the same test file is reused across fresh uids): ignore it
            return Integer.valueOf(parts[1]);
        } catch (Exception e) {
            System.err.println("note: ignoring unreadable sidecar " + p);
            return null;
        }
    }

    static void writeSidecar(String file, String uid, int n) throws IOException {
        Files.write(Paths.get(file + ".version"), (uid + " " + n + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String promptPassphrase(boolean confirm) {
        String fromEnv = System.getenv("E2SE_PP"); // passphrase from the environment; never printed
        if (fromEnv != null) return fromEnv;       // set: use it, no confirm step; unset: ask at the terminal as before
        Console console = System.console();
        if (console == null) fail("run me from a real terminal, or set E2SE_PP");
        String pp = new String(console.readPassword("passphrase: "));
        if (confirm) {
            String again = new String(console.readPassword("confirm passphrase: "));
            if (!pp.equals(again)) fail("passphrases do not match");
        }
        return pp;
    }

    private static byte[] unlock(Client client, String uid, String passphrase,
                                 String bucketName, String key1, String key0) {
        try {
            return client.take(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER,
                    Constants.AUTH_SERVER_NAME, uid, passphrase, bucketName, key1, key0); // fetches both salts from S3, re-derives the token and box key from your passphrase, logs into the key server with the token, receives the locked box, opens it, and returns mk
        } catch (AmazonServiceException e) {
            fail("no salts on S3 for " + uid + " - run init first");
        } catch (Exception e) {
            fail("could not unlock: wrong passphrase, the key server is not running, or its record for '" + uid + "' is missing or damaged");
        }
        return null; // never reached 'fail() exits' but the compiler can't know that
    }

    private static AmazonS3 s3(String accessKeyId, String secretKeyId, String regionName) {
        return AmazonS3ClientBuilder.standard().withRegion(regionName)
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(accessKeyId, secretKeyId)))
                .build();
    }

    private static String requireFileArg(List<String> words) {
        if (words.size() < 3) { usage(); System.exit(2); }
        return words.get(2);
    }

    private static void fail(String msg) {
        fail(msg, 1);
    }

    private static void fail(String msg, int exitCode) {
        System.err.println("error: " + msg);
        System.exit(exitCode);
    }

    private static void usage() {
        System.out.println("usage: PasswordManager init <uid>");
        System.out.println("       PasswordManager store <uid> <file> [--force] [--no-head]");
        System.out.println("       PasswordManager retrieve <uid> <file> [--no-head]");
        System.out.println("       PasswordManager add <uid> <name> <username> <password>   add one entry (makes the next version)");
        System.out.println("       PasswordManager edit <uid> <name> <newpassword>         change one entry's password (next version)");
        System.out.println("       PasswordManager rm <uid> <name>                         remove one entry (next version)");
        System.out.println("       PasswordManager list <uid>                              names and usernames");
        System.out.println("       PasswordManager show <uid> <name>                       name, username and password, on the terminal only");
        System.out.println("  vault lines: name TAB username TAB password, one entry per line; the entry commands always check the head and keep the plaintext in memory only");
        System.out.println("  --force    store: use the key server's current version as the base even if your copy is older (overwrites; no merge)");
        System.out.println("  --no-head  skip the version head: no freshness check (evaluation baseline / demo control)");
        System.out.println("  bucketName2 in config.properties (copies experiment): store writes the sealed vault to both buckets; retrieve takes the copy that matches the head and repairs the other");
        System.out.println("exit codes: 0 ok, 1 error, 2 usage, 3 store conflict (your copy is not the latest), 4 stale or substituted vault, 5 vault refused (seal or format check failed)");
    }
}
