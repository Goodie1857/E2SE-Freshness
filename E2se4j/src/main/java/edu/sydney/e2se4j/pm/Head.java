package edu.sydney.e2se4j.pm;

import edu.sydney.e2se4j.Client;
import edu.sydney.e2se4j.Constants;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

// The version head: for each user, the key server K remembers (n, h) = the latest vault version number and the SHA-256
// fingerprint of that version's sealed blob (design/0002). This file holds BOTH ends of the two new requests.
//
//   HEAD_GET      client -> K : 0x05 | len(uid) | uid | t(16)
//                 K -> client : 0x06 | n(4) | h(32)                  (n = 0 and 32 zero bytes: no vault stored yet)
//                            or 0x07                                 ERROR: not registered / no key deposited / wrong t
//   HEAD_UPDATE   client -> K : 0x04 | len(uid) | uid | t(16) | n_expected(4) | n_new(4) | h(32)
//                 K -> client : 0x06                                 accepted: K now holds (n_new, h)
//                            or 0x0A | n(4) | h(32)                  CONFLICT: K's head is not n_expected; nothing changed
//                            or 0x07                                 ERROR, as above
//
// The first three fields copy the original RETRIEVAL request exactly (type byte, 1-byte uid length, uid, raw 16-byte token),
// so the key server's existing code reads the type and the uid before handing the connection to this class. Every new field
// has a fixed size and is read with readFully/readInt, and each side sends its whole message with ONE write.
// The head carries no tag of its own: the client accepts a blob only if S3's bytes match K's fingerprint, so K alone or S3
// alone can only cause a refusal; a stale acceptance needs both to lie together (design/0002 item 3).
public final class Head {
    public static final byte REQ_HEAD_UPDATE = 0x04;   // request type bytes; 0x02, 0x03, 0x08, 0x09 belong to the original protocol
    public static final byte REQ_HEAD_GET = 0x05;
    public static final byte RESP_CONFLICT = 0x0A;     // reply byte; OK 0x06 and ERROR 0x07 are reused from Constants
    static final int HASH_LEN = 32;                    // SHA-256
    static final byte[] NO_HASH = new byte[HASH_LEN];  // sent with n = 0

    public final int n;      // version number; 0 = no vault stored yet
    public final byte[] h;   // fingerprint of the sealed blob of version n

    public Head(int n, byte[] h) {
        this.n = n;
        this.h = h;
    }

    // ---------------------------------------------------------------- key server half (called from AuthServer.start) ----

    /** uid -> head. Held in memory like the key server's two other tables; KState writes every change to disk and refills this table at start-up (sprint W4). */
    private static final Map<String, Head> TABLE = new HashMap<String, Head>();

    /** Called by KState.loadAll at start-up: put a saved head back without touching the disk. */
    static void restore(String uid, Head head) {
        TABLE.put(uid, head);
    }

    /** The three checks the original RETRIEVAL handler makes: registered (a stored t exists), a key was deposited, token matches (constant time). */
    static boolean authenticate(byte[] tokenReceived, byte[] expectedT, boolean deposited) {
        return expectedT != null && deposited && MessageDigest.isEqual(expectedT, tokenReceived);
    }

    /** HEAD_GET. `in` is positioned just after the uid: AuthServer has already read the type byte and the uid. */
    public static void handleGet(InputStream in, OutputStream out, String uid, byte[] expectedT, boolean deposited) throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] token = new byte[Constants.R_LENGTH];
        din.readFully(token);                                                              // all 16 bytes, however TCP split them
        if (!authenticate(token, expectedT, deposited)) {
            System.out.println("User " + uid + ": HEAD_GET refused (not registered, no key deposited, or wrong token).");
            out.write(Constants.RESP_TYPE_ERROR);
            return;
        }
        Head cur = TABLE.get(uid);
        if (cur == null) cur = new Head(0, NO_HASH);                                       // no store yet: version 0
        out.write(reply(Constants.RESP_TYPE_OK, cur));                                     // one write: OK | n | h
    }

    /** HEAD_UPDATE with compare-and-swap: move n_expected -> n_new only if K's n is still n_expected and n_new = n_expected + 1. */
    public static void handleUpdate(InputStream in, OutputStream out, String uid, byte[] expectedT, boolean deposited) throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] token = new byte[Constants.R_LENGTH];
        din.readFully(token);
        int nExpected = din.readInt();
        int nNew = din.readInt();
        byte[] h = new byte[HASH_LEN];
        din.readFully(h);
        if (!authenticate(token, expectedT, deposited)) {
            System.out.println("User " + uid + ": HEAD_UPDATE refused (not registered, no key deposited, or wrong token).");
            out.write(Constants.RESP_TYPE_ERROR);
            return;
        }
        Head cur = TABLE.get(uid);
        int curN = cur == null ? 0 : cur.n;
        if (curN == nExpected && nNew == nExpected + 1) {
            TABLE.put(uid, new Head(nNew, h));                                             // the only line that moves a head
            KState.saveHead(uid, nNew, h);                                                 // on disk before the OK goes out (W4)
            System.out.println("User " + uid + ": head " + nExpected + " -> " + nNew + ".");
            out.write(Constants.RESP_TYPE_OK);
        } else {
            System.out.println("User " + uid + ": HEAD_UPDATE " + nExpected + " -> " + nNew + " conflicts with head " + curN + "; nothing changed.");
            out.write(reply(RESP_CONFLICT, cur == null ? new Head(0, NO_HASH) : cur));     // CONFLICT | current n | current h
        }
    }

    private static byte[] reply(byte type, Head head) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeByte(type);
        d.writeInt(head.n);                                                                // 4 bytes, big-endian
        d.write(head.h);                                                                   // 32 bytes
        return buf.toByteArray();
    }

    // ---------------------------------------------------------------- client half (called from PasswordManager) ----

    /** Ask K for this user's head. t = the login token Take derived (Client.getLastT()). Throws if K refuses. */
    public static Head get(Client client, String uid, byte[] t) throws Exception {
        Socket sock = client.connectAndGetSocket(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME); // the same TLS connection Take uses
        try {
            sock.getOutputStream().write(request(REQ_HEAD_GET, uid, t, null));
            DataInputStream din = new DataInputStream(sock.getInputStream());
            byte resp = din.readByte();
            if (resp != Constants.RESP_TYPE_OK) throw new IOException("key server refused the head request (reply byte " + resp + ")");
            return readHead(din);
        } finally {
            sock.close();
        }
    }

    /** Ask K to move the head nExpected -> nNew with fingerprint h. Returns null when accepted, K's current head on CONFLICT; throws if K refuses. */
    public static Head update(Client client, String uid, byte[] t, int nExpected, int nNew, byte[] h) throws Exception {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(tail);
        d.writeInt(nExpected);
        d.writeInt(nNew);
        d.write(h);
        Socket sock = client.connectAndGetSocket(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME);
        try {
            sock.getOutputStream().write(request(REQ_HEAD_UPDATE, uid, t, tail.toByteArray()));
            DataInputStream din = new DataInputStream(sock.getInputStream());
            byte resp = din.readByte();
            if (resp == Constants.RESP_TYPE_OK) return null;
            if (resp == RESP_CONFLICT) return readHead(din);
            throw new IOException("key server refused the head update (reply byte " + resp + ")");
        } finally {
            sock.close();
        }
    }

    /** type | len(uid) | uid | t | tail. The uid bytes use the platform charset, exactly as the original Take sends them, so K's table key matches. */
    private static byte[] request(byte type, String uid, byte[] t, byte[] tail) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] u = uid.getBytes();
        buf.write(type);
        buf.write(u.length);
        buf.write(u);
        buf.write(t);
        if (tail != null) buf.write(tail);
        return buf.toByteArray();
    }

    private static Head readHead(DataInputStream din) throws IOException {
        int n = din.readInt();
        byte[] h = new byte[HASH_LEN];
        din.readFully(h);
        return new Head(n, h);
    }
}
