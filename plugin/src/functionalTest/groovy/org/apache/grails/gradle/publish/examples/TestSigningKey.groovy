/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.grails.gradle.publish.examples

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor
import org.bouncycastle.openpgp.operator.PGPDigestCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair

import java.security.KeyPairGenerator

/**
 * A throwaway PGP key generated for a test, in the shapes the plugin's signing configurations consume: a
 * {@code secring.gpg} keyring file (SIGNING_KEYRING) or an armored key to import into a gpg home directory.
 * The public key verifies the {@code .asc} signatures a build produced.
 */
class TestSigningKey {

    static final String USER_ID = 'Grails Publish Functional Tests <dev@grails.apache.org>'

    final PGPSecretKeyRing secretKeyRing
    final PGPPublicKeyRing publicKeyRing
    /** The passphrase protecting the secret key; null for an unprotected key */
    final String passphrase

    private TestSigningKey(PGPSecretKeyRing secretKeyRing, PGPPublicKeyRing publicKeyRing, String passphrase) {
        this.secretKeyRing = secretKeyRing
        this.publicKeyRing = publicKeyRing
        this.passphrase = passphrase
    }

    /**
     * Generates a fresh RSA key. Pass a null passphrase for a key that gpg can use without a pinentry.
     */
    static TestSigningKey generate(String passphrase = 'grails-publish-functional-tests') {
        KeyPairGenerator generator = KeyPairGenerator.getInstance('RSA')
        generator.initialize(2048)
        PGPKeyPair keyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, generator.generateKeyPair(), new Date())

        PGPDigestCalculator sha1 = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        PBESecretKeyEncryptor encryptor = passphrase == null ? null :
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).build(passphrase.toCharArray())

        PGPKeyRingGenerator ringGenerator = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                keyPair,
                USER_ID,
                sha1,
                null,
                null,
                new BcPGPContentSignerBuilder(keyPair.publicKey.algorithm, HashAlgorithmTags.SHA256),
                encryptor
        )
        new TestSigningKey(ringGenerator.generateSecretKeyRing(), ringGenerator.generatePublicKeyRing(), passphrase)
    }

    /** The full 16 character hex key id */
    String getLongKeyId() {
        String.format('%016X', secretKeyRing.secretKey.keyID)
    }

    /** The short 8 character key id, as documented for {@code signing.keyId} / SIGNING_KEY */
    String getKeyId() {
        longKeyId.substring(8)
    }

    /** Writes a binary secret keyring file, as {@code gpg --export-secret-keys > secring.gpg} would */
    File writeSecretKeyRing(File file) {
        file.parentFile?.mkdirs()
        file.bytes = secretKeyRing.encoded
        file
    }

    /** Writes the secret key ascii armored, suitable for {@code gpg --import} */
    File writeArmoredSecretKey(File file) {
        file.parentFile?.mkdirs()
        file.withOutputStream { OutputStream out ->
            try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
                secretKeyRing.encode(armored)
            }
        }
        file
    }

    /** Verifies an armored detached signature ({@code .asc}) over the data with this key's public key */
    boolean verifies(byte[] data, byte[] armoredSignature) {
        InputStream decoded = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredSignature))
        Object first = new BcPGPObjectFactory(decoded).nextObject()
        if (!(first instanceof PGPSignatureList) || ((PGPSignatureList) first).isEmpty()) {
            return false
        }
        PGPSignature signature = ((PGPSignatureList) first).get(0)
        PGPPublicKey publicKey = publicKeyRing.getPublicKey(signature.keyID)
        if (publicKey == null) {
            return false
        }
        signature.init(new BcPGPContentVerifierBuilderProvider(), publicKey)
        signature.update(data)
        signature.verify()
    }

    boolean verifies(File data, File armoredSignature) {
        verifies(data.bytes, armoredSignature.bytes)
    }
}
