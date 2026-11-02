package edu.sydney.e2se4j.pm;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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

// Plays the malicious data server D. It holds exactly what D holds - the S3 credential - and nothing else: no passphrase, no master key, no contact with the key server K.
public class StorageAdversary {
    public static void main(String[] args) throws Exception { // throws: opening config.properties can fail with a checked IOException
        if (args.length < 3) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        String uid = args[1];
        String file = args[2]; // local file the snapshot is saved to

        Properties prop = new Properties();
        try (InputStream in = new FileInputStream("config.properties")) {
            prop.load(in);
        }
        String accessKeyId = prop.getProperty("accessKeyId");
        String secretKeyId = prop.getProperty("secretKeyId");
        String regionName  = prop.getProperty("regionName");
        String bucketName  = prop.getProperty("bucketName");
        String bucketName2 = prop.getProperty("bucketName2");                 // copies experiment: the second data server's bucket
        String which = args.length >= 4 ? args[3] : "1";                       // which bucket D acts on: 1 (default) or 2
        String cred  = args.length >= 5 ? args[4] : "1";                       // which credential D holds: 1 (default, the first bucket's) or 2 (the second bucket's)
        if (!which.equals("1") && !which.equals("2") || !cred.equals("1") && !cred.equals("2")) { usage(); System.exit(2); }
        if (which.equals("2")) {
            if (bucketName2 == null || bucketName2.trim().isEmpty()) fail("no bucketName2 in config.properties");
            bucketName = bucketName2.trim();
        }
        if (cred.equals("2")) {                                                  // D stole the second credential instead of the first
            String a2 = prop.getProperty("accessKeyId2"), k2 = prop.getProperty("secretKeyId2");
            if (a2 == null || a2.trim().isEmpty()) fail("no accessKeyId2 in config.properties");
            accessKeyId = a2.trim(); secretKeyId = k2.trim();
        }
        String where = "bucket " + which + " (holding credential " + cred + ")";

        String vaultKey = uid + "/vault";

        switch (cmd) {
            case "snapshot": { // D keeps a copy of the victim's current vault object, bytes untouched (D can copy ciphertext; it cannot open it)
                try {
                    s3(accessKeyId, secretKeyId, regionName).getObject(new GetObjectRequest(bucketName, vaultKey), new File(file));
                } catch (AmazonServiceException e) { // no such object, or this credential may not read this bucket
                    fail("cannot read " + vaultKey + " in " + where + ": " + e.getErrorCode());
                }
                System.out.println("D: saved " + vaultKey + " from " + where + " as " + file);
                break;
            }

            case "rollback": { // D serves an OLD copy: upload a saved vault file back over <uid>/vault
                File f = new File(file);                                    // the local snapshot to replay
                if (!f.isFile()) fail("no such file: " + file);            // snapshot must have been taken first
                try {
                    s3(accessKeyId, secretKeyId, regionName)
                            .putObject(new PutObjectRequest(bucketName, vaultKey, f)); // plain PUT: overwrites whatever is at <uid>/vault
                } catch (AmazonServiceException e) {                             // the provider refused this credential for this bucket
                    fail("cannot write " + vaultKey + " in " + where + ": " + e.getErrorCode());
                }
                System.out.println("D: put " + file + " back as " + vaultKey + " in " + where);
                break;
            }

            case "tamper": { // D changes one bit of the stored vault: byte number args[2], counted from 0 (D can change bytes; it cannot read them)
                int index = Integer.parseInt(file);                          // for this command the third word is a byte position, not a file name
                AmazonS3 s3 = s3(accessKeyId, secretKeyId, regionName);
                byte[] blob = null;
                try {
                    blob = IOUtils.toByteArray(s3.getObject(new GetObjectRequest(bucketName, vaultKey)).getObjectContent()); // download the object into memory
                } catch (AmazonServiceException e) {
                    fail("no vault on S3 for " + uid);
                }
                if (index < 0 || index >= blob.length) fail("byte " + index + " is outside the object (" + blob.length + " bytes)");
                blob[index] ^= 1;                                            // flip the lowest bit of that byte
                ObjectMetadata meta = new ObjectMetadata();
                meta.setContentLength(blob.length);                          // S3 needs the length up front when uploading from memory
                s3.putObject(new PutObjectRequest(bucketName, vaultKey, new ByteArrayInputStream(blob), meta)); // upload it back over the original
                System.out.println("D: flipped one bit of byte " + index + " of " + vaultKey + " in " + where + " (" + blob.length + " bytes)");
                break;
            }

            default: {
                usage();
                System.exit(2);
            }
        }
    }

    private static AmazonS3 s3(String accessKeyId, String secretKeyId, String regionName) {
        return AmazonS3ClientBuilder.standard().withRegion(regionName)
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(accessKeyId, secretKeyId)))
                .build();
    }

    private static void fail(String msg) {
        System.err.println("D: error: " + msg);
        System.exit(1);
    }

    private static void usage() {
        System.out.println("usage: StorageAdversary snapshot <uid> <file> [bucket] [cred]   save <uid>/vault as <file>");
        System.out.println("       StorageAdversary rollback <uid> <file> [bucket] [cred]   put <file> back as <uid>/vault");
        System.out.println("       StorageAdversary tamper <uid> <byte> [bucket] [cred]    flip one bit of byte <byte> of <uid>/vault");
        System.out.println("  bucket: 1 = bucketName (default), 2 = bucketName2;  cred: 1 = the first credential (default), 2 = accessKeyId2/secretKeyId2");
    }
}
