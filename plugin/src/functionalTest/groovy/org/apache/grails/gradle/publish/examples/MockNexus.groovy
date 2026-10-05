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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import groovy.json.JsonOutput
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.absent
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.put
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

/**
 * A local Nexus endpoint, backed by WireMock, implementing the parts of the Sonatype staging REST API the
 * {@code io.github.gradle-nexus.publish-plugin} talks to (profiles, start / close / release a staging repository,
 * poll its state) plus the artifact upload endpoints of the staging and snapshot repositories. By default, every
 * request must carry the configured basic auth credentials.
 *
 * The staging repository moves through {@code open}, {@code closed} and {@code released} as the build calls the
 * transition endpoints; uploads are kept in the request journal and can be read back with {@link #uploads}.
 */
class MockNexus implements AutoCloseable {

    /** Host names the server's certificate is valid for: the tests, and a container reaching the host */
    static final List<String> HOST_NAMES = ['localhost', 'host.testcontainers.internal']
    static final String STORE_PASSWORD = 'grails-publish-tests'
    /** The password of the JDK's cacerts, which the trust store extends */
    static final String TRUST_STORE_PASSWORD = 'changeit'

    static final String USERNAME = 'nexus-deployer'
    static final String PASSWORD = 'nexus-deployer-password'
    static final String STAGING_PROFILE_ID = '1a2b3c4d5e6f7'
    static final String STAGING_PROFILE_NAME = 'org.grails.example'
    static final String STAGING_REPOSITORY_ID = 'orggrailsexample-1001'

    private static final String API = '/service/local'
    private static final String SNAPSHOTS = '/content/repositories/snapshots'
    private static final String MAVEN = '/repository/maven'
    private static final String STAGING_SCENARIO = 'staging repository'

    private WireMockServer server

    /** The description the build is expected to use for its staging repository (NEXUS_PUBLISH_DESCRIPTION) */
    final String repositoryDescription

    /**
     * A trust store for the builds that publish here: the JDK's default CA certificates (so Maven Central still
     * resolves) plus this server's self-signed certificate
     */
    final File trustStore

    private final File keyStore

    MockNexus(String repositoryDescription) {
        this.repositoryDescription = repositoryDescription
        File directory = File.createTempDir('mock-nexus')
        keyStore = new File(directory, 'nexus-keystore.p12')
        trustStore = new File(directory, 'nexus-truststore.p12')
    }

    /** Serves over TLS with a certificate generated for this instance, like a real Nexus would (behind https) */
    MockNexus start() {
        generateCertificate()
        server = new WireMockServer(options()
                .dynamicHttpsPort()
                .httpDisabled(true)
                .keystoreType('PKCS12')
                .keystorePath(keyStore.absolutePath)
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD))
        server.start()
        stubStagingApi()
        stubUploads()
        this
    }

    @Override
    void close() {
        server?.stop()
        [keyStore, trustStore].each { it.delete() }
        keyStore.parentFile.delete()
    }

    /** The JVM arguments that make a build trust this server's certificate */
    List<String> getTrustArguments() {
        trustArgumentsFor(trustStore.absolutePath)
    }

    static List<String> trustArgumentsFor(String trustStorePath) {
        [
                "-Djavax.net.ssl.trustStore=${trustStorePath}" as String,
                "-Djavax.net.ssl.trustStorePassword=${TRUST_STORE_PASSWORD}" as String,
                '-Djavax.net.ssl.trustStoreType=PKCS12',
        ]
    }

    /**
     * Generates a self-signed certificate for the test host names and writes the server key store and the trust
     * store for the builds, in process (no keytool or other external command).
     */
    private void generateCertificate() {
        KeyPairGenerator generator = KeyPairGenerator.getInstance('RSA')
        generator.initialize(2048)
        KeyPair keyPair = generator.generateKeyPair()

        X500Name subject = new X500Name('CN=localhost, O=Grails Publish Functional Tests')
        Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5))
        Date notAfter = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
        GeneralName[] names = HOST_NAMES.collect { new GeneralName(GeneralName.dNSName, it) } + [new GeneralName(GeneralName.iPAddress, '127.0.0.1')]
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter, subject, keyPair.public)
                .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names))
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
        ContentSigner signer = new JcaContentSignerBuilder('SHA256withRSA').build(keyPair.private)
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer))

        KeyStore serverKeys = KeyStore.getInstance('PKCS12')
        serverKeys.load(null, null)
        serverKeys.setKeyEntry('nexus', keyPair.private, STORE_PASSWORD.toCharArray(), [certificate] as Certificate[])
        keyStore.withOutputStream { serverKeys.store(it, STORE_PASSWORD.toCharArray()) }

        // start from the JDK's own CA certificates so that the build can still reach Maven Central
        KeyStore trusted = KeyStore.getInstance('PKCS12')
        new File(System.getProperty('java.home'), 'lib/security/cacerts').withInputStream { trusted.load(it, TRUST_STORE_PASSWORD.toCharArray()) }
        trusted.setCertificateEntry('nexus', certificate)
        trustStore.withOutputStream { trusted.store(it, TRUST_STORE_PASSWORD.toCharArray()) }
    }

    private String getBaseUrl() {
        "https://localhost:${server.httpsPort()}"
    }

    /** The value for NEXUS_PUBLISH_URL (the staging API root) */
    String getNexusUrl() {
        "${baseUrl}${API}/"
    }

    /** The value for NEXUS_PUBLISH_SNAPSHOT_URL */
    String getSnapshotRepositoryUrl() {
        "${baseUrl}${SNAPSHOTS}/"
    }

    /** The value for MAVEN_PUBLISH_URL: a plain Maven repository served by this server */
    String getMavenRepositoryUrl() {
        "${baseUrl}${MAVEN}/"
    }

    /** The environment variables of a MAVEN_PUBLISH build targeting this server */
    Map<String, String> getMavenEnvironment() {
        [
                MAVEN_PUBLISH_URL     : mavenRepositoryUrl,
                MAVEN_PUBLISH_USERNAME: USERNAME,
                MAVEN_PUBLISH_PASSWORD: PASSWORD,
        ]
    }

    /** The files uploaded to the plain Maven repository, keyed by their path below the repository root */
    Map<String, byte[]> getMavenUploads() {
        uploads(MAVEN)
    }

    /** Also accepts requests without credentials at the plain Maven repository, leaving Nexus authentication intact */
    void allowAnonymousMavenPublishing() {
        server.stubFor(put(urlMatching("${MAVEN}/.*"))
                .withHeader('Authorization', absent())
                .willReturn(aResponse().withStatus(201)))
        server.stubFor(get(urlMatching("${MAVEN}/.*"))
                .withHeader('Authorization', absent())
                .willReturn(aResponse().withStatus(404)))
    }

    /** The environment variables of a NEXUS_PUBLISH build targeting this server */
    Map<String, String> getEnvironment() {
        [
                NEXUS_PUBLISH_URL               : nexusUrl,
                NEXUS_PUBLISH_SNAPSHOT_URL      : snapshotRepositoryUrl,
                NEXUS_PUBLISH_USERNAME          : USERNAME,
                NEXUS_PUBLISH_PASSWORD          : PASSWORD,
                NEXUS_PUBLISH_STAGING_PROFILE_ID: STAGING_PROFILE_ID,
                NEXUS_PUBLISH_DESCRIPTION       : repositoryDescription,
        ]
    }

    /** The port this server listens on (for exposing it to a container) */
    int getPort() {
        server.httpsPort()
    }

    /** The NEXUS_PUBLISH environment for a build that reaches this server through another host name, e.g. from a container */
    Map<String, String> environmentFor(String hostName) {
        environment.collectEntries { String name, String value ->
            [(name): value.replace(baseUrl, "https://${hostName}:${port}")]
        } as Map<String, String>
    }

    /** The files uploaded to the staging repository, keyed by their path below the repository root */
    Map<String, byte[]> getStagingUploads() {
        uploads("${API}/staging/deployByRepositoryId/${STAGING_REPOSITORY_ID}")
    }

    /** The files uploaded to the snapshot repository, keyed by their path below the repository root */
    Map<String, byte[]> getSnapshotUploads() {
        uploads(SNAPSHOTS)
    }

    /** The descriptions sent when staging repositories were created */
    List<String> getCreatedStagingRepositoryDescriptions() {
        server.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlEqualTo("${API}/staging/profiles/${STAGING_PROFILE_ID}/start")))
                .collect { LoggedRequest request -> new groovy.json.JsonSlurper().parseText(request.bodyAsString).data.description as String }
    }

    /** The current state of the staging repository as the build left it: open, closed or released */
    String getStagingRepositoryState() {
        String state = server.getAllScenarios().scenarios.find { it.name == STAGING_SCENARIO }?.state
        state == Scenario.STARTED ? 'not created' : state
    }

    /** Requests that matched no stub (for example because they lacked credentials) */
    List<String> getUnmatchedRequests() {
        server.findUnmatchedRequests().requests.collect { "${it.method} ${it.url}" as String }
    }

    private Map<String, byte[]> uploads(String repositoryRoot) {
        Map<String, byte[]> files = [:]
        server.findAll(putRequestedFor(urlMatching("${repositoryRoot}/.*"))).each { LoggedRequest request ->
            files[request.url.substring(repositoryRoot.length() + 1)] = request.body
        }
        files
    }

    private void stubStagingApi() {
        server.stubFor(get(urlEqualTo("${API}/staging/profiles"))
                .withBasicAuth(USERNAME, PASSWORD)
                .willReturn(json([data: [[id: STAGING_PROFILE_ID, name: STAGING_PROFILE_NAME]]])))

        server.stubFor(post(urlEqualTo("${API}/staging/profiles/${STAGING_PROFILE_ID}/start"))
                .withBasicAuth(USERNAME, PASSWORD)
                .inScenario(STAGING_SCENARIO).whenScenarioStateIs(Scenario.STARTED).willSetStateTo('open')
                .willReturn(json([data: [stagedRepositoryId: STAGING_REPOSITORY_ID]], 201)))

        server.stubFor(post(urlEqualTo("${API}/staging/bulk/close"))
                .withBasicAuth(USERNAME, PASSWORD)
                .inScenario(STAGING_SCENARIO).whenScenarioStateIs('open').willSetStateTo('closed')
                .willReturn(json([:], 201)))

        server.stubFor(post(urlEqualTo("${API}/staging/bulk/promote"))
                .withBasicAuth(USERNAME, PASSWORD)
                .inScenario(STAGING_SCENARIO).whenScenarioStateIs('closed').willSetStateTo('released')
                .willReturn(json([:], 201)))

        for (String state : ['open', 'closed', 'released']) {
            Map repository = [repositoryId: STAGING_REPOSITORY_ID, type: state, transitioning: false, description: repositoryDescription]
            server.stubFor(get(urlEqualTo("${API}/staging/repository/${STAGING_REPOSITORY_ID}"))
                    .withBasicAuth(USERNAME, PASSWORD)
                    .inScenario(STAGING_SCENARIO).whenScenarioStateIs(state)
                    .willReturn(json(repository)))
            server.stubFor(get(urlEqualTo("${API}/staging/profile_repositories/${STAGING_PROFILE_ID}"))
                    .withBasicAuth(USERNAME, PASSWORD)
                    .inScenario(STAGING_SCENARIO).whenScenarioStateIs(state)
                    .willReturn(json([data: [repository]])))
        }
        // before a repository has been created there is nothing to find
        server.stubFor(get(urlEqualTo("${API}/staging/profile_repositories/${STAGING_PROFILE_ID}"))
                .withBasicAuth(USERNAME, PASSWORD)
                .inScenario(STAGING_SCENARIO).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json([data: []])))
    }

    private void stubUploads() {
        for (String repositoryRoot : ["${API}/staging/deployByRepositoryId/${STAGING_REPOSITORY_ID}", SNAPSHOTS, MAVEN]) {
            server.stubFor(put(urlMatching("${repositoryRoot}/.*"))
                    .withBasicAuth(USERNAME, PASSWORD)
                    .willReturn(aResponse().withStatus(201)))
            // nothing has been published before, so metadata lookups miss...
            server.stubFor(get(urlMatching("${repositoryRoot}/.*"))
                    .withBasicAuth(USERNAME, PASSWORD)
                    .willReturn(aResponse().withStatus(404)))
            // ...but only once the client has answered the authentication challenge (Gradle sends GET requests
            // without credentials first and retries with them after a 401, unlike its uploads)
            server.stubFor(get(urlMatching("${repositoryRoot}/.*"))
                    .atPriority(10)
                    .willReturn(aResponse().withStatus(401).withHeader('WWW-Authenticate', 'Basic realm="Sonatype Nexus Repository Manager"')))
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body, int status = 200) {
        aResponse().withStatus(status).withHeader('Content-Type', 'application/json').withBody(JsonOutput.toJson(body))
    }
}
