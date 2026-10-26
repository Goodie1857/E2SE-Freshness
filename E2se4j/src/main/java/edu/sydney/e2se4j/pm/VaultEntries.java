package edu.sydney.e2se4j.pm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// The vault's lines (sprint W5). One entry per line: name TAB username TAB password. This class only turns the vault's
// text into entries and back again; it does no network, no crypto and no file I/O. PasswordManager decides what to do
// with the entries, and the sealed vault (SealedVault) never learns that the bytes it seals have lines at all.
public final class VaultEntries {
    private VaultEntries() {}

    /** One line of the vault. */
    public static final class Entry {
        public final String name, username, password;
        public Entry(String name, String username, String password) {
            this.name = name; this.username = username; this.password = password;
        }
    }

    /** The vault's text is not in the expected form, or a typed field would not fit on one line. The message is written for the user. */
    public static final class BadFormat extends Exception {
        BadFormat(String msg) { super(msg); }
    }

    /** Text in, entries out. Blank lines are skipped; every other line must have exactly three tab-separated fields (fail closed: never guess what a malformed line meant). */
    public static List<Entry> parse(byte[] text) throws BadFormat {
        List<Entry> out = new ArrayList<Entry>();
        String[] lines = new String(text, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);    // a file edited on Windows ends its lines with CR LF
            if (line.trim().isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 3) throw new BadFormat("line " + (i + 1) + " of the vault does not have three tab-separated fields (name, username, password)");
            out.add(new Entry(f[0], f[1], f[2]));
        }
        return out;
    }

    /** Entries in, text out: one line per entry, the three fields separated by tabs, a newline after every line. */
    public static byte[] render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.name).append('\t').append(e.username).append('\t').append(e.password).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The position of the entry with this name, or -1 when there is none. Names are compared exactly (capitals matter). */
    public static int find(List<Entry> entries, String name) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(name)) return i;
        return -1;
    }

    /** A field typed on the command line must fit inside one line of the vault: no tab, no line break; a name must not be empty. */
    public static void checkField(String what, String value, boolean mustNotBeEmpty) throws BadFormat {
        if (mustNotBeEmpty && value.isEmpty()) throw new BadFormat("the " + what + " must not be empty");
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new BadFormat("the " + what + " must not contain a tab or a line break");
    }
}
