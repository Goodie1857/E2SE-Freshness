package edu.sydney.e2se4j.pm;

import edu.sydney.e2se4j.Constants;
import edu.sydney.e2se4j.HMacKDF;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

// The sealed vault: the stored form of the vault file, and the only code that knows that form (design/0002 item 1).
//
//   blob   = header (21 bytes) || ciphertext (same length as the file) || seal (16 bytes, the GCM tag)
//   header = "E2SV" (4) || format 0x01 (1) || n, the version number, big-endian (4) || nonce (12 random bytes)
//
// The seal covers the ciphertext AND the label = header || uid (UTF-8). So the version number, the nonce, the format
// and the user id are all pinned: change or swap any of them and open() refuses. Nothing in the header is secret.
// Key: the vault key is derived from mk with the label "e2se.vault.v1" (key separation): mk itself never encrypts data.
// This class does no network or file I/O, prints nothing and never exits; the caller decides what a refusal means.
public final class SealedVault {
    static final byte[] MAGIC = {'E', '2', 'S', 'V'};                 // name tag: the first four bytes of every sealed vault
    static final byte FORMAT_V1 = 0x01;                                // format byte; a future layout gets 0x02
    static final int VERSION_OFFSET = 5;                               // where n starts inside the header
    static final int NONCE_OFFSET = 9;                                 // where the nonce starts
    static final int NONCE_LEN = Constants.KEY_ENCRYPTION_IV_LENGTH;   // 12 bytes, the same GCM nonce size the key wrap of mk uses
    static final int HEADER_LEN = NONCE_OFFSET + NONCE_LEN;            // 21
    static final int TAG_LEN = Constants.GCM_TAG_LENGTH / 8;           // 16 bytes
    static final String KDF_INFO = "e2se.vault.v1";                    // the label that separates the vault key from any other key made from mk
    static final int KEY_LEN = 16;                                     // AES-128: the same strength as mk itself

    private SealedVault() {}

    /** The blob must not be opened: wrong format, or the seal did not verify. The message is written for the user. */
    public static final class Refused extends Exception {
        Refused(String msg) { super(msg); }
    }

    /** vault key = HKDF(mk, "e2se.vault.v1"), 16 bytes, with the project's own HKDF class (HMAC-SHA512 like its two other call sites). */
    static byte[] vaultKey(byte[] mk) {
        return new HMacKDF(HMacKDF.HDFK_SHA512, mk).createKey(KDF_INFO, KEY_LEN);
    }

    /** File bytes in, blob out. n is the version number written into the header (1 until the head protocol assigns numbers). */
    public static byte[] seal(byte[] mk, String uid, int n, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);                                                   // fresh per seal; never reused (new-code rule 1)
        byte[] header = ByteBuffer.allocate(HEADER_LEN).put(MAGIC).put(FORMAT_V1).putInt(n).put(nonce).array(); // ByteBuffer writes ints big-endian
        Cipher cipher = Cipher.getInstance(Constants.KEY_ENCRYPTION_ALGORITHM);                 // AES/GCM/NoPadding, as for the wrap of mk
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(vaultKey(mk), Constants.KEY_ENCRYPTION_BASE_ALGORITHM),
                new GCMParameterSpec(Constants.GCM_TAG_LENGTH, nonce));
        cipher.updateAAD(label(header, uid));                                                  // the label goes under the seal, unencrypted
        byte[] sealed = cipher.doFinal(plaintext);                                             // ciphertext || tag
        return ByteBuffer.allocate(HEADER_LEN + sealed.length).put(header).put(sealed).array();
    }

    /** The version number in the header. Refuses anything that is not a sealed vault. */
    public static int version(byte[] blob) throws Refused {
        checkHeader(blob);
        return ByteBuffer.wrap(blob, VERSION_OFFSET, 4).getInt();
    }

    /** Blob in, file bytes out, or Refused. Nothing comes out unless the seal verifies (new-code rule 6, fail-closed). */
    public static byte[] open(byte[] mk, String uid, byte[] blob) throws Refused, GeneralSecurityException {
        checkHeader(blob);
        byte[] header = Arrays.copyOfRange(blob, 0, HEADER_LEN);
        byte[] nonce = Arrays.copyOfRange(blob, NONCE_OFFSET, HEADER_LEN);
        Cipher cipher = Cipher.getInstance(Constants.KEY_ENCRYPTION_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(vaultKey(mk), Constants.KEY_ENCRYPTION_BASE_ALGORITHM),
                new GCMParameterSpec(Constants.GCM_TAG_LENGTH, nonce));
        cipher.updateAAD(label(header, uid));                                                  // must be the same label as at seal time
        try {
            return cipher.doFinal(blob, HEADER_LEN, blob.length - HEADER_LEN);                // GCM recomputes the seal before releasing a byte
        } catch (AEADBadTagException e) {
            throw new Refused("failed its integrity check: the bytes were altered after sealing, or the vault was sealed for a different user");
        }
    }

    /** SHA-256 over the whole blob: the fingerprint the version head at the key server will hold. */
    public static byte[] fingerprint(byte[] blob) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(blob);
    }

    private static void checkHeader(byte[] blob) throws Refused {
        if (blob.length < HEADER_LEN + TAG_LEN || !Arrays.equals(Arrays.copyOfRange(blob, 0, MAGIC.length), MAGIC)) { // the name tag is public, so a plain compare is fine
            throw new Refused("is not a sealed vault (no E2SV header): stored by the pre-fix jar, or replaced by something else");
        }
        if (blob[MAGIC.length] != FORMAT_V1) {
            throw new Refused("has format byte " + (blob[MAGIC.length] & 0xff) + "; this program knows format 1 only");
        }
    }

    private static byte[] label(byte[] header, String uid) {
        byte[] u = uid.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(header.length + u.length).put(header).put(u).array();
    }
}
