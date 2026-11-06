# E2SE-Freshness

Code and evidence for the honours thesis *Rollback protection for a stateless End-to-Same-End-Encryption password manager: design, demonstration and measurement* (Cecil Choi, The University of Sydney, 2026).

It is a command-line password manager built on E2SE4j. The key server remembers the number and the fingerprint of the latest version of the vault, so a storage provider that puts an old vault back is refused.

## Whose code

`E2se4j/` and `TestGuide/` are E2SE4j, the code that Chen, Li, Tang and Yung published with the E2SE paper: <https://github.com/yananli117/E2SE4j> at commit `dbc47d5`, MIT licence (`LICENSE`); their README is `README-E2SE4j.md`. This thesis added the package `E2se4j/src/main/java/edu/sydney/e2se4j/pm/` and changed a few lines of their `AuthServer.java`, `Client.java` and `pom.xml`.

## Run it

You need a JDK (tested on OpenJDK 17), Maven, and an Amazon S3 bucket with an access key. Run everything from inside `E2se4j/`.

```
cd E2se4j
cp config.properties.example config.properties     # fill in your S3 key, region and bucket
keytool -importcert -trustcacerts -alias theCARootNew -file certificatesNew/ca-certificate.pem -cacerts -storepass changeit     # once; may need sudo
mvn clean package
java -jar target/E2se4j-1.0-SNAPSHOT-jar-with-dependencies.jar authserver     # the key server: leave it running
```

In a second terminal, in the same folder:

```
PM="java -cp target/E2se4j-1.0-SNAPSHOT-jar-with-dependencies.jar edu.sydney.e2se4j.pm.PasswordManager"
ADV="java -cp target/E2se4j-1.0-SNAPSHOT-jar-with-dependencies.jar edu.sydney.e2se4j.pm.StorageAdversary"

printf 'gmail\talice\tpw-one\n' > vault_a.txt
printf 'gmail\talice\tpw-two\n' > vault_b.txt
$PM init alice1                  # a new user id; asks for a passphrase
$PM store alice1 vault_a.txt     # version 1
$ADV snapshot alice1 old.bin     # the storage provider keeps a copy
$PM store alice1 vault_b.txt     # version 2
$ADV rollback alice1 old.bin     # the provider puts version 1 back
$PM retrieve alice1 out.txt      # refused with exit code 4; no file is written
```

With `--no-head` after every `store` and `retrieve`, on another new user id, the last command accepts the old vault instead. That is the attack.

## More

- `scripts/` holds the transcripts of the thesis's runs, `bench/` the measurements, `thesis/figures/` the figures.
- The tags `mvp-pre-fix` and `thesis-final` and the branch `copies-experiment` are the three states of the code that the thesis names. Commit ids printed in the transcripts are those of the author's working repository.
- Appendix A of the thesis is the full guide: every command, the exit codes and the measurements.
- Never commit `config.properties`. Do not probe the key server's port with `nc` or a port scanner: it crashes.
