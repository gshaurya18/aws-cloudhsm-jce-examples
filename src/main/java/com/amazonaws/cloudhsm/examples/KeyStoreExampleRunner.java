/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.amazonaws.cloudhsm.examples;

import com.amazonaws.cloudhsm.jce.jni.exception.InternalException;
import com.amazonaws.cloudhsm.jce.provider.CloudHsmProvider;
import com.amazonaws.cloudhsm.jce.provider.KeyStoreWithAttributes;
import com.amazonaws.cloudhsm.jce.provider.attributes.KeyAttribute;
import com.amazonaws.cloudhsm.jce.provider.attributes.KeyAttributesMap;
import com.amazonaws.cloudhsm.jce.provider.attributes.KeyAttributesMapBuilder;
import com.amazonaws.cloudhsm.jce.provider.attributes.KeyReferenceSpec;
import com.amazonaws.cloudhsm.jce.provider.attributes.KeyType;
import com.amazonaws.cloudhsm.jce.provider.attributes.ObjectClassType;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/**
 * KeyStoreExampleRunner demonstrates how to load a keystore, get a key entry, sign and store a
 * certificate with the key and list all aliases on the keystore.
 *
 * <p>This example relies on implicit credentials, so you must setup your environment correctly.
 *
 * <p>https://docs.aws.amazon.com/cloudhsm/latest/userguide/java-library-install.html#java-library-credentials
 */
public class KeyStoreExampleRunner {

    private static final String helpString =
            "KeyStoreExampleRunner\n"
                + "This sample demonstrates how to load and store keys using a keystore.\n\n" + "Options\n"
                + "\t--help\t\t\tDisplay this message.\n" + "\t--store <filename>\t\tPath of the keystore.\n"
                + "\t--password <password>\t\tPassword for the keystore (not your CU password).\n"
                + "\t--label <label>\t\t\tLabel to store the key and certificate under.\n"
                + "\t--list\t\t\tList all the keys in the keystore.\n"
                + "\t--rsa-private-keys\t\tGet all RSA private keys using getKeys and KeyAttributesMap.\n"
                + "\t--getkey <key-reference-long>\t\tGet a matching key using key-reference.\n\n";

    public static void main(final String[] args) throws Exception {
        try {
            if (Security.getProvider(CloudHsmProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new CloudHsmProvider());
            }
        } catch (final IOException ex) {
            System.out.println(ex);
            return;
        }

        String keystoreFile = null;
        String password = null;
        String labelArg = null;
        String keyReferenceValue = null;
        Operation operation = Operation.None;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (args[i]) {
                case "--store":
                    keystoreFile = args[++i];
                    break;
                case "--password":
                    password = args[++i];
                    break;
                case "--label":
                    labelArg = args[++i];
                    break;
                case "--list":
                    operation = Operation.List;
                    break;
                case "--rsa-private-keys":
                    operation = Operation.GetKeys;
                    break;
                case "--getkey":
                    operation = Operation.GetKeyByReference;
                    keyReferenceValue = args[++i];
                    break;
                case "--help":
                    help();
                    return;
            }
        }

        if (null == keystoreFile || null == password) {
            help();
            return;
        }

        switch (operation) {
            case List:
                listKeys(keystoreFile, password);
                return;
            case GetKeys:
                getKeys(keystoreFile, password);
                return;
            case GetKeyByReference:
                getKey(keystoreFile, password, keyReferenceValue);
                return;
        }

        final String label;
        if (null == labelArg) {
            label = "Keystore Example Keypair";
        } else {
            label = labelArg;
        }
        final String privateLabel = label + ":Private";

        final KeyStore keyStore = KeyStore.getInstance(CloudHsmProvider.CLOUDHSM_KEYSTORE_TYPE);
        try {
            final FileInputStream instream = new FileInputStream(keystoreFile);
            // This call to keyStore.load() will open the CloudHSM keystore file with the supplied
            // password.
            keyStore.load(instream, password.toCharArray());
        } catch (final FileNotFoundException ex) {
            System.err.println("Keystore not found, loading an empty store");
            keyStore.load(null, null);
        }

        final PasswordProtection passwordProtection =
                new PasswordProtection(password.toCharArray());
        System.out.println("Searching for example key pair and certificate...");

        /*
         * Generates the key pair if not found and signs a certificate with the key and stores it in the
         * KeyStore.
         */
        if (!keyStore.containsAlias(privateLabel)) {
            System.out.println("No entry found for '" + privateLabel + "', creating a keypair...");
            final KeyAttributesMap requiredPublicKeyAttributes =
                    new KeyAttributesMapBuilder().put(KeyAttribute.VERIFY, true).build();
            final KeyAttributesMap requiredPrivateKeyAttributes =
                    new KeyAttributesMapBuilder().put(KeyAttribute.SIGN, true).build();
            final KeyPair keyPair =
                    AsymmetricKeys.generateRSAKeyPair(
                            2048, label, requiredPublicKeyAttributes, requiredPrivateKeyAttributes);

            /** Generate a certificate and associate the chain with the private key. */
            final Certificate selfSignedCert = createAndSignCertificate(keyPair);
            final Certificate[] chain = new Certificate[] {selfSignedCert};
            final PrivateKeyEntry entry = new PrivateKeyEntry(keyPair.getPrivate(), chain);

            /*
             * Set the entry using the label as the alias and save the store. The alias must match the
             * private key label.
             */
            keyStore.setEntry(privateLabel, entry, passwordProtection);

            final FileOutputStream outstream = new FileOutputStream(keystoreFile);
            keyStore.store(outstream, password.toCharArray());
            outstream.close();
        }

        final PrivateKeyEntry keyEntry =
                (PrivateKeyEntry) keyStore.getEntry(privateLabel, passwordProtection);
        final String name = keyEntry.getCertificate().toString();
        System.out.printf("Found private key with label %s and certificate %s%n", label, name);
    }

    private enum Operation {
        None, List, GetKeys, GetKeyByReference
    }

    private static void help() {
        System.out.println(helpString);
    }

    /** List all the keys in the keystore. */
    private static void listKeys(final String keystoreFile, final String password)
            throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(CloudHsmProvider.PROVIDER_NAME);

        try {
            final FileInputStream inputStream = new FileInputStream(keystoreFile);
            keyStore.load(inputStream, password.toCharArray());
        } catch (final FileNotFoundException ex) {
            System.err.println("Keystore not found, creating an empty store.");
            keyStore.load(null, null);
        }

        for (final Enumeration<String> entry = keyStore.aliases(); entry.hasMoreElements(); ) {
            System.out.println(entry.nextElement());
        }
    }

    /** Generate a certificate signed by a given keypair. */
    private static Certificate createAndSignCertificate(final KeyPair keyPair)
            throws CertificateException, NoSuchProviderException, NoSuchAlgorithmException,
                    SignatureException, InvalidKeyException, OperatorCreationException {
        final X500Name x500Name =
                new X500Name("C=US, ST=Washington, L=Seattle, O=Amazon, OU=AWS, CN=CloudHSM");

        // Serial number should be unique per CA
        final long serialNumberValue = System.currentTimeMillis() % Long.MAX_VALUE;
        final BigInteger serialNumber = BigInteger.valueOf(serialNumberValue);
        final Calendar calendar = Calendar.getInstance();

        final String keyAlgorithm = keyPair.getPrivate().getAlgorithm();
        final SubjectPublicKeyInfo publicKeyInfo =
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

        final X500Name issuer = x500Name;
        final X500Name subject = x500Name;
        final Date notValidUntil = calendar.getTime();
        final Date notValidAfter = notValidUntil;
        final X509v3CertificateBuilder builder =
                new X509v3CertificateBuilder(
                        issuer, serialNumber, notValidUntil, notValidAfter, subject, publicKeyInfo);

        final String signatureAlgorithm;
        if (keyAlgorithm.equalsIgnoreCase("RSA")) {
            signatureAlgorithm = "SHA512WithRSA";
        } else {
            throw new IllegalArgumentException(
                    "KeyAlgorithm should be RSA, but found " + keyAlgorithm);
        }

        final ContentSigner signer =
                new JcaContentSignerBuilder(signatureAlgorithm)
                        .setProvider(CloudHsmProvider.PROVIDER_NAME)
                        .build(keyPair.getPrivate());
        final X509CertificateHolder certificateHolder = builder.build(signer);
        final JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        return converter.getCertificate(certificateHolder);
    }

    /** Fetches all keys matching filter criteria - Private, RSA using KeyStoreWithAttributes **/
    private static void getKeys(String keystoreFile, String password) throws Exception {
        final KeyStoreWithAttributes keyStore = KeyStoreWithAttributes.getInstance(CloudHsmProvider.PROVIDER_NAME);

        try {
            final FileInputStream inputStream = new FileInputStream(keystoreFile);
            keyStore.load(inputStream, password.toCharArray());
        } catch (final FileNotFoundException ex) {
            System.err.println("Keystore not found, creating an empty store.");
            keyStore.load(null, null);
        }

        final KeyAttributesMap searchSpec = new KeyAttributesMap();
        searchSpec.put(KeyAttribute.KEY_TYPE, KeyType.RSA);
        searchSpec.put(KeyAttribute.OBJECT_CLASS, ObjectClassType.PRIVATE_KEY);

        try {
            List<Key> keys = keyStore.getKeys(searchSpec);
            if (!keys.isEmpty()) {
                System.out.println("Total number of RSA Private Keys found : " + keys.size());
            } else {
                System.out.println("RSA Private Keys not found");
            }

        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }

    /** Fetches the key matching given Key-Reference value using KeyStoreWithAttributes **/
    private static void getKey(String keystoreFile, String password, String keyReferenceValue) throws Exception {
        final KeyStoreWithAttributes keyStore = KeyStoreWithAttributes.getInstance(CloudHsmProvider.PROVIDER_NAME);

        try {
            final FileInputStream inputStream = new FileInputStream(keystoreFile);
            keyStore.load(inputStream, password.toCharArray());
        } catch (final FileNotFoundException ex) {
            System.err.println("Keystore not found, creating an empty store.");
            keyStore.load(null, null);
        }

        try {
            KeyReferenceSpec keyReferenceSpec = KeyReferenceSpec.getInstance(Long.parseLong(keyReferenceValue));
            Key key = keyStore.getKey(keyReferenceSpec);
            if (key != null) {
                System.out.println("Found a key using key reference : " + keyReferenceValue);
            } else {
                System.out.println("Key not found using key reference: " + keyReferenceValue);
            }

        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(e);
        } catch (NumberFormatException e) {
            System.err.println("Unable to parse KeyReferenceValue");
            throw new RuntimeException(e);
        } catch (InternalException e) {
            System.err.println("Error in getKey Operation " + e);
            throw new RuntimeException(e);
        }
    }

}
