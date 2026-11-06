# Tests without a transcript file

Four tests that the thesis refers to have no transcript in this folder; Appendix A.4 of the thesis names them. They are recorded in the record of changes that was kept, entry by entry, while the code was written. The passages of that record which say what was typed and what came back are copied here word for word, under the date of the entry each belongs to. "[…]" marks where the rest of an entry is left out.

How to read them: K is the key server and D the storage provider; a "sidecar" is the receipt file that `retrieve` and `store` write beside the vault file; user ids such as `cecil3` are test accounts. "Verified (Claude …)" marks a check made with the AI tool that the thesis names in its statement on the use of generative AI; "Cecil's run" marks a test made by the author of the thesis.

## Entry of 22 Oct 2026 (the sealed vault): the seal tests of thesis §5.3

> […]
> **Verified (Claude, 2026-10-22, K on the laptop, real S3):** `mvn clean
> package` BUILD SUCCESS, jar 21:57. uid `t8a22215712`: init / store / retrieve
> exit 0, `cmp` silent; snapshot of the object: 105 bytes = 68 + 21 + 16, `xxd`
> starts `4532 5356 01 00000001` then the nonce; the program's printed sha256
> equals `sha256sum` of the snapshot (6b754ae7…). One bit of byte 20 (the last
> nonce byte) flipped and put back with `StorageAdversary rollback`: retrieve →
> "failed its integrity check", exit 5, no output file. uid `env22214835`
> (stored tonight by the pre-fix path, 84 bytes): retrieve → "is not a sealed
> vault (no E2SV header)", exit 5, no file. uid `t8b22215717`: round trip ok;
> one bit of byte 40 (inside the ciphertext) flipped → the same refusal, exit 5.
> Cecil's done-when runs: 2026-10-23, uid cecil1: init, store, retrieve, and cmp printed nothing, so the retrieved file was identical to the original.
> 2026-10-25, uid cecil3: one bit of byte 40 flipped with StorageAdversary tamper, then retrieve --no-head:
> "vault refused: cecil3/vault failed its integrity check", exit 5, and no file was written.
> […]

## Entry of 24 Oct 2026 (the version head at the key server): the first test of store and retrieve with the head

> […]
> Cecil's run (2026-10-25, uid cecil3, key server on the laptop, real S3): I stored the file three times and got versions 1, 2 and 3, the key server reporting 0 -> 1, 1 -> 2 and 2 -> 3 (I did not edit the file between stores, so version 3 holds the same text as version 2). I took a snapshot of version 2, stored version 3, then ran rollback. retrieve refused: "storage returned version 2, key server says 3 (rollback, or an interrupted store). Nothing was written to back.txt.", exit 4. retrieve --no-head accepted version 2 (sha256 B4D8…, the fingerprint the second store had printed) and wrote the file, exit 0: the seal alone cannot see staleness. A store from that back.txt, whose sidecar said version 2, was refused: "vault is at version 3 at the key server, your copy is from version 2", exit 3, nothing uploaded. Also observed: the key server had been restarted since the day before, so init cecil2 succeeded a second time and a store from a file whose sidecar said "cecil2 4" was refused with "version 0 at the key server, your copy is from version 4", exit 3. A key server that has lost its memory causes a refusal, not an acceptance; persistence (W4) is what stops it losing the memory.
> […]

## Entry of 25 Oct 2026 (persistence at the key server): the persistence test of thesis §5.6

> […]
> Cecil's run (25 Oct 2026, 23:10–23:27, uid cecil4, key server on the
> laptop, real S3; recorded by Claude from Cecil's pasted transcript at his
> request, 26 Oct 00:10): init → "vault key created for cecil4" and one
> `.properties` file under kstate/; store vault_test.txt → version 1, 105 bytes,
> head 0 → 1; after a key-server restart, retrieve → version 1, "matches the head
> at the key server", `cmp` silent; the sidecar run: `vault_r4.txt.version` =
> `cecil4 1`, store from that file → version 2, head 1 → 2, a different sha256
> for the same text (fresh nonce, new n in the header); the record file showed
> uid, ct, tao, t, headN=2, headHash (`\=` escapes); with the first character of
> `tao` changed and K restarted, retrieve → "could not unlock: wrong passphrase,
> the key server is not running, or its record for 'cecil4' is missing or
> damaged", exit 1, no file; character restored, K restarted → retrieve works,
> version 2, matches the head; `init cecil4` again → "init failed: is the key
> server running, and is 'cecil4' new on this key server?", exit 1; a retrieve
> after that → "Retrieval failed! (Auth Server returned error)" then the same
> could-not-unlock line (the Register-ordering finding, next entry). The key
> server's "kstate: 1 user record(s) loaded" lines were on terminal 1, which was
> not pasted [Cecil to confirm]. Cecil's reading of the last two refusals ("retrieve
> doesn't work after I changed the kstate back") was corrected: the restore had
> worked (the version-2 retrieve); the later refusals came from the re-init.
> […]

## Entry of 26 Oct 2026 (the entry commands): the entry-command demonstration

> […]
> Cecil's run (26 Oct 2026, 15:48–15:56, uid cecil5, key server on the laptop,
> real S3; recorded by Claude from his pasted transcript, his own sentences to
> follow): init → "vault key created for cecil5"; add gmail → "added gmail",
> version 1, 56 bytes, head 0 -> 1; list → "version 1, 1 entry", `gmail alice`;
> edit gmail → version 2, 56 bytes, head 1 -> 2; show → "version 2, matches the
> head at the key server", `gmail alice pw-two`; `rm cecil5` without a name →
> usage (exit 2, correct); rm gmail → version 3, 37 bytes, head 2 -> 3; list →
> "version 3, 0 entries"; add outlook → version 4, 61 bytes; snapshot; edit
> outlook → version 5, 60 bytes, head 4 -> 5; second snapshot; rollback of the
> version-4 copy; show outlook → "error: storage returned version 4, key server
> says 5 (rollback, or an interrupted store).", exit 4; the version-5 copy put
> back → show → "version 5, matches the head at the key server", `outlook oliver
> pw-four`, exit 0. Every line as the plan predicted.
> […]
