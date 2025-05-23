/*
 * Copyright (C)2009 - SSHJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.schmizz.sshj;

import com.hierynomus.sshj.key.KeyAlgorithm;
import com.hierynomus.sshj.key.KeyAlgorithms;
import com.hierynomus.sshj.transport.cipher.BlockCiphers;
import com.hierynomus.sshj.transport.cipher.ChachaPolyCiphers;
import com.hierynomus.sshj.transport.cipher.GcmCiphers;
import com.hierynomus.sshj.transport.cipher.StreamCiphers;
import com.hierynomus.sshj.transport.kex.DHGroups;
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory;
import com.hierynomus.sshj.transport.kex.ExtendedDHGroups;
import com.hierynomus.sshj.transport.mac.Macs;
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.LoggerFactory;
import net.schmizz.sshj.transport.cipher.Cipher;
import net.schmizz.sshj.transport.compression.NoneCompression;
import net.schmizz.sshj.transport.kex.Curve25519SHA256;
import net.schmizz.sshj.transport.kex.DHGexSHA1;
import net.schmizz.sshj.transport.kex.DHGexSHA256;
import net.schmizz.sshj.transport.kex.ECDHNistP;
import net.schmizz.sshj.transport.random.JCERandom;
import net.schmizz.sshj.transport.random.SingletonRandomFactory;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Properties;

/**
 * A {@link net.schmizz.sshj.Config} that is initialized as follows. Items marked with an asterisk are added to the config only if
 * BouncyCastle is in the classpath.
 * <p/>
 * <ul>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setKeyExchangeFactories Key exchange}: {@link net.schmizz.sshj.transport.kex.DHG14}*, {@link net.schmizz.sshj.transport.kex.DHG1}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setCipherFactories Ciphers}: {@link BlockCiphers}, {@link StreamCiphers} [1]</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setMACFactories MAC}: {@link net.schmizz.sshj.transport.mac.HMACSHA1}, {@link net.schmizz.sshj.transport.mac.HMACSHA196}, {@link net.schmizz.sshj.transport.mac.HMACMD5}, {@link
 * net.schmizz.sshj.transport.mac.HMACMD596}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setCompressionFactories Compression}: {@link net.schmizz.sshj.transport.compression.NoneCompression}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setKeyAlgorithms KeyAlgorithm}: {@link net.schmizz.sshj.signature.SignatureRSA}, {@link net.schmizz.sshj.signature.SignatureDSA}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setRandomFactory BC}: {@link net.schmizz.sshj.transport.random.BouncyCastleRandom}* or {@link net.schmizz.sshj.transport.random.JCERandom}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setRandomFactory BCFIPS}: {@link net.schmizz.sshj.transport.random.BouncyCastleFipsRandom}* or {@link net.schmizz.sshj.transport.random.JCERandom}</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setFileKeyProviderFactories Key file support}: {@link net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile}*, {@link
 * net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile}*</li>
 * <li>{@link net.schmizz.sshj.ConfigImpl#setVersion Client version}: {@code "NET_3_0"}</li>
 * </ul>
 * <p/>
 */
public class DefaultConfig
        extends ConfigImpl {

    private Logger log;

    public DefaultConfig() {
        setLoggerFactory(LoggerFactory.DEFAULT);
        initKeyExchangeFactories();
        initKeyAlgorithms();
        initRandomFactory();
        initFileKeyProviderFactories();
        initCipherFactories();
        initCompressionFactories();
        initMACFactories();
        setKeepAliveProvider(KeepAliveProvider.HEARTBEAT);
    }

    @Override
    public void setLoggerFactory(LoggerFactory loggerFactory) {
        super.setLoggerFactory(loggerFactory);
        log = loggerFactory.getLogger(getClass());
    }

    protected void initKeyExchangeFactories() {
        setKeyExchangeFactories(
                new Curve25519SHA256.Factory(),
                new Curve25519SHA256.FactoryLibSsh(),
                new DHGexSHA256.Factory(),
                new ECDHNistP.Factory521(),
                new ECDHNistP.Factory384(),
                new ECDHNistP.Factory256(),
                new DHGexSHA1.Factory(),
                DHGroups.Group1SHA1(),
                DHGroups.Group14SHA1(),
                DHGroups.Group14SHA256(),
                DHGroups.Group15SHA512(),
                DHGroups.Group16SHA512(),
                DHGroups.Group17SHA512(),
                DHGroups.Group18SHA512(),
                ExtendedDHGroups.Group14SHA256AtSSH(),
                ExtendedDHGroups.Group15SHA256(),
                ExtendedDHGroups.Group15SHA256AtSSH(),
                ExtendedDHGroups.Group15SHA384AtSSH(),
                ExtendedDHGroups.Group16SHA256(),
                ExtendedDHGroups.Group16SHA384AtSSH(),
                ExtendedDHGroups.Group16SHA512AtSSH(),
                ExtendedDHGroups.Group18SHA512AtSSH(),
                new ExtInfoClientFactory()
        );
    }

    protected void initKeyAlgorithms() {
        setKeyAlgorithms(Arrays.<Factory.Named<KeyAlgorithm>>asList(
                KeyAlgorithms.EdDSA25519CertV01(),
                KeyAlgorithms.EdDSA25519(),
                KeyAlgorithms.ECDSASHANistp521CertV01(),
                KeyAlgorithms.ECDSASHANistp521(),
                KeyAlgorithms.ECDSASHANistp384CertV01(),
                KeyAlgorithms.ECDSASHANistp384(),
                KeyAlgorithms.ECDSASHANistp256CertV01(),
                KeyAlgorithms.ECDSASHANistp256(),
                KeyAlgorithms.RSASHA512(),
                KeyAlgorithms.RSASHA256(),
                KeyAlgorithms.SSHRSACertV01(),
                KeyAlgorithms.SSHDSSCertV01(),
                KeyAlgorithms.SSHRSA(),
                KeyAlgorithms.SSHDSA()));
    }

    protected void initRandomFactory() {
        setRandomFactory(new SingletonRandomFactory(new JCERandom.Factory()));
    }

    protected void initFileKeyProviderFactories() {
        setFileKeyProviderFactories(
                new OpenSSHKeyV1KeyFile.Factory(),
                new PKCS8KeyFile.Factory(),
                new OpenSSHKeyFile.Factory(),
                new PuTTYKeyFile.Factory()
        );
    }

    protected void initCipherFactories() {
        List<Factory.Named<Cipher>> avail = new LinkedList<Factory.Named<Cipher>>(Arrays.<Factory.Named<Cipher>>asList(
                ChachaPolyCiphers.CHACHA_POLY_OPENSSH(),
                BlockCiphers.AES128CBC(),
                BlockCiphers.AES128CTR(),
                BlockCiphers.AES192CBC(),
                BlockCiphers.AES192CTR(),
                BlockCiphers.AES256CBC(),
                BlockCiphers.AES256CTR(),
                GcmCiphers.AES128GCM(),
                GcmCiphers.AES256GCM(),
                BlockCiphers.BlowfishCBC(),
                BlockCiphers.BlowfishCTR(),
                BlockCiphers.Cast128CBC(),
                BlockCiphers.Cast128CTR(),
                BlockCiphers.IDEACBC(),
                BlockCiphers.IDEACTR(),
                BlockCiphers.Serpent128CBC(),
                BlockCiphers.Serpent128CTR(),
                BlockCiphers.Serpent192CBC(),
                BlockCiphers.Serpent192CTR(),
                BlockCiphers.Serpent256CBC(),
                BlockCiphers.Serpent256CTR(),
                BlockCiphers.TripleDESCBC(),
                BlockCiphers.TripleDESCTR(),
                BlockCiphers.Twofish128CBC(),
                BlockCiphers.Twofish128CTR(),
                BlockCiphers.Twofish192CBC(),
                BlockCiphers.Twofish192CTR(),
                BlockCiphers.Twofish256CBC(),
                BlockCiphers.Twofish256CTR(),
                BlockCiphers.TwofishCBC(),
                StreamCiphers.Arcfour(),
                StreamCiphers.Arcfour128(),
                StreamCiphers.Arcfour256())
        );

        final ListIterator<Factory.Named<Cipher>> factories = avail.listIterator();
        while (factories.hasNext()) {
            final Factory.Named<Cipher> factory = factories.next();
            try {
                final Cipher cipher = factory.create();
                final byte[] key = new byte[cipher.getBlockSize()];
                final byte[] iv = new byte[cipher.getIVSize()];
                cipher.init(Cipher.Mode.Encrypt, key, iv);
            } catch (Exception e) {
                log.info("Cipher [{}] disabled: {}", factory.getName(), e.getCause().getMessage());
                factories.remove();
            }
        }

        setCipherFactories(avail);
        log.debug("Available Ciphers {}", avail);
    }

    protected void initMACFactories() {
        setMACFactories(
                Macs.HMACSHA1(),
                Macs.HMACSHA1Etm(),
                Macs.HMACSHA196(),
                Macs.HMACSHA196Etm(),
                Macs.HMACMD5(),
                Macs.HMACMD5Etm(),
                Macs.HMACMD596(),
                Macs.HMACMD596Etm(),
                Macs.HMACSHA2256(),
                Macs.HMACSHA2256Etm(),
                Macs.HMACSHA2512(),
                Macs.HMACSHA2512Etm(),
                Macs.HMACRIPEMD160(),
                Macs.HMACRIPEMD160Etm(),
                Macs.HMACRIPEMD16096(),
                Macs.HMACRIPEMD160OpenSsh()
        );
    }

    protected void initCompressionFactories() {
        setCompressionFactories(new NoneCompression.Factory());
    }
}
