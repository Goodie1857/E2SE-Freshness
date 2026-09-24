package edu.sydney.e2se4j;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.io.Console;
import java.io.File;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;

public class PasswordManager {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        String uid = args[1];

        Properties prop = new Properties();
        try (InputStream in = new FileInputStream("config.properties")) {
            prop.load(in);
        }
        String accessKeyId = prop.getProperty("accessKeyId");
        String secretKeyId = prop.getProperty("secretKeyId"); 
        String regionName  = prop.getProperty("regionName"); // which AWS datacentre
        String bucketName  = prop.getProperty("bucketName"); // which storage container

        Client client = new Client(accessKeyId, secretKeyId, regionName, bucketName);

        String key0 = uid + "/sid";
        String key1 = uid + "/rid";
        String vaultKey = uid + "/vault";

        switch (cmd) {
            case "init": {
                String passphrase = promptPassphrase(true);
                try {
                    client.register(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME, uid, passphrase, bucketName, key0); // makes random salt and uploads as key0, derives login token t from passphrase + salt, files login token t at the key server
                    client.give(Constants.AUTH_SERVER_ADDRESS, Constants.AUTH_SERVER_PORT_NUMBER, Constants.AUTH_SERVER_NAME, uid, passphrase, bucketName, key1, key0); // makes second salt key1, fetches key0 back, creates mk and locks it under key derived from passphrase + second salt, sends the locked box + its tag to the key server
                } catch (Exception e) {
                    fail("init failed: is the key server running, and is '" + uid + "' new on this server run?");
                }
                System.out.println("vault key created for " + uid);
                break;
            }

            case "store": { // DEM half: lock the vault file under mk and hand S3 only the ciphertext
                String file = requireFileArg(args);
                if (!new File(file).isFile()) fail("no such file: " + file);
                String passphrase = promptPassphrase(false); // a typo here fails the unlock, it can't lock us out
                byte[] mk = unlock(client, uid, passphrase, bucketName, key1, key0); // Take: rebuild mk in memory from passphrase + both servers; every failure below this line is a party saying no

                File temp = new File(Constants.FILE_PATH + "pm-upload.bin"); // scratch file under DataFile/ - exists because encryptCTRBigFile works file -> file
                try {
                    Client.encryptCTRBigFile(file, temp.getPath(), mk); // scramble the vault under mk into the scratch file
                    s3(accessKeyId, secretKeyId, regionName).putObject(new PutObjectRequest(bucketName, vaultKey, temp)); // upload scratch file as <uid>/vault, only bytes that ever leave this machine are ciphertext
                } finally {
                    temp.delete(); // scrambled copy never lingers on disk
                }
                System.out.println("vault stored encrypted as " + vaultKey);
                break;
            }

            case "retrieve": {
                String file = requireFileArg(args);
                String passphrase = promptPassphrase(false);
                byte[] mk = unlock(client, uid, passphrase, bucketName, key1, key0);

                File temp = new File(Constants.FILE_PATH + "pm-download.bin"); // scratch file for the ENCRYPTED download (never holds plaintext)
                try {
                    try {
                        s3(accessKeyId, secretKeyId, regionName).getObject(new GetObjectRequest(bucketName, vaultKey), temp); // fetch <uid>/vault from S3 and stream it straight into the scratch file
                    } catch (AmazonServiceException e) { // vault doesn't exist
                        fail("no vault stored yet for " + uid + " - run store first");
                    }
                    Client.decryptCTRBigFile(temp.getPath(), file, mk); // unscramble scratch -> the file the user asked for, under mk
                } finally {
                    temp.delete();
                }

                System.out.println("vault retrieved to " + file);
                break;
            }

            default: {
                usage();
                System.exit(2);
            }
        }
    }

    
    private static String promptPassphrase(boolean confirm) {
        Console console = System.console();
        if (console == null) fail("run me from a real terminal");
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
            fail("could not unlock: wrong passphrase, or the key server was restarted / is not running");
        }
        return null; // never reached 'fail() exits' but the compiler can't know that
    }

    private static AmazonS3 s3(String accessKeyId, String secretKeyId, String regionName) {
        return AmazonS3ClientBuilder.standard().withRegion(regionName)
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(accessKeyId, secretKeyId)))
                .build();
    }

    private static String requireFileArg(String[] args) {
        if (args.length < 3) { usage(); System.exit(2); }
        return args[2];
    }

    private static void fail(String msg) {
        System.err.println("error: " + msg);
        System.exit(1);
    }
 
    private static void usage() {
        System.out.println("usage: PasswordManager init <uid>");
        System.out.println("       PasswordManager store <uid> <file>");
        System.out.println("       PasswordManager retrieve <uid> <file>");
    }
}