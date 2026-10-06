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

import org.gradle.testkit.runner.GradleRunner
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.ExecConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile

import java.nio.file.Path

/**
 * Releases the {@code simple-library} example from inside a clean container: a JDK, Gradle and gpg, a throwaway
 * key imported into the container's own keyring, and nothing from the host. The container signs with the gpg
 * command and stages the release into the Nexus endpoint running in the test (over TLS, trusting its certificate),
 * reached through an exposed host port. This is the release path of the grails-core workflow (gpg import plus SIGNING_KEY) in an isolated
 * environment, independent of whatever gpg the machine running the tests has.
 *
 * Docker is required to run the functional tests (as it is for grails-core).
 */
class ContainerizedReleaseSpec extends ExampleProjectSpecification {

    void setupSpec() {
        assert DockerClientFactory.instance().isDockerAvailable():
                'Docker is required to run the functional tests: the containerized release cannot run without it.'
    }

    static final String ARTIFACT_ID = 'simple-library'
    static final String RELEASE_VERSION = '4.5.6'
    static final String HOST = 'host.testcontainers.internal'

    /**
     * The official Gradle image with gpg added: the same Gradle version as this build's wrapper, and the JDK major
     * version running these tests, since the plugin under test was compiled by it (its Groovy classes target the
     * compiling JDK) and must load inside the container.
     */
    static final String DOCKERFILE = """\
        FROM gradle:${wrapperGradleVersion()}-jdk${Runtime.version().feature()}
        USER root
        RUN apt-get update && apt-get install -y --no-install-recommends gnupg && rm -rf /var/lib/apt/lists/*
        """.stripIndent()

    /** The Gradle version of this repository's wrapper, so the container runs the version the plugin is built for */
    static String wrapperGradleVersion() {
        Path properties = Path.of('..', 'gradle', 'wrapper', 'gradle-wrapper.properties').toAbsolutePath().normalize()
        String distributionUrl = new Properties().tap { it.load(properties.newReader()) }.getProperty('distributionUrl')
        (distributionUrl =~ /gradle-([0-9][0-9.]*[0-9])-/)[0][1]
    }

    def "a release signed by gpg inside a clean container is staged in Nexus"() {
        given: 'a key that only the container will hold, and a Nexus the container can reach'
        TestSigningKey key = TestSigningKey.generate(null)
        File armoredKey = key.writeArmoredSecretKey(createTempRepository('release-key').resolve('release-key.asc').toFile())
        String description = "org.grails.example:${ARTIFACT_ID}:${RELEASE_VERSION}"
        MockNexus nexus = startNexus(description)
        Testcontainers.exposeHostPorts(nexus.port)

        and: 'the example project, the plugin under test, the key and the init script copied into the container'
        GradleRunner runner = setupExample(ARTIFACT_ID)
        Path project = runner.projectDir.toPath()
        GenericContainer container = new GenericContainer(new ImageFromDockerfile('grails-publish-tests/gradle-gpg', false).withFileFromString('Dockerfile', DOCKERFILE))
                .withCommand('sleep', 'infinity')
                .withAccessToHost(true)
                .withCopyToContainer(MountableFile.forHostPath(project), '/project')
                .withCopyToContainer(MountableFile.forHostPath(pluginRepository), '/plugin-repository')
                .withCopyToContainer(MountableFile.forHostPath(armoredKey.toPath()), '/keys/release-key.asc')
                .withCopyToContainer(MountableFile.forHostPath(nexus.trustStore.toPath()), '/keys/nexus-truststore.p12')
                .withCopyToContainer(Transferable.of(initScriptText('file:///plugin-repository')), '/init/local-plugin.init.gradle')
        container.start()

        when: 'the key is imported into the container gpg'
        ExecResult imported = container.execInContainer('gpg', '--batch', '--import', '/keys/release-key.asc')

        then:
        imported.exitCode == 0

        when: 'the release is published from inside the container'
        Map<String, String> environment = nexus.environmentFor(HOST) + [
                SIGNING_KEY                            : key.keyId,
                ORG_GRADLE_PROJECT_grailsPublishVersion: System.getProperty('grailsGradlePluginVersion'),
                ORG_GRADLE_PROJECT_groovyVersion       : System.getProperty('groovyVersion'),
                ORG_GRADLE_PROJECT_projectVersion      : RELEASE_VERSION,
        ]
        ExecResult build = container.execInContainer(ExecConfig.builder()
                .workDir('/project')
                .envVars(environment)
                // the container has its own Gradle user home, so the configuration cache is requested here, as it is for
                // the builds run by the test kit (see GradleSpecification). This build cannot store an entry, though: the
                // Nexus plugin publishes with explicit credentials and its close task is marked as not compatible
                .command((['gradle', '--no-daemon', '--stacktrace', '--configuration-cache',
                           '--init-script', '/init/local-plugin.init.gradle']
                        + MockNexus.trustArgumentsFor('/keys/nexus-truststore.p12')
                        + ['publishToSonatype', 'closeSonatypeStagingRepository']) as String[])
                .build())
        println build.stdout
        System.err.println build.stderr

        then: 'the container signed with its gpg command and staged the release'
        build.exitCode == 0

        and: 'Gradle ran the release without storing a configuration cache entry, instead of failing it'
        build.stdout.contains('Configuration cache disabled because incompatible task was found.')
        !build.stdout.contains('Configuration cache entry stored.')
        build.stdout.contains('Signing is enabled due to release configuration.')
        build.stdout.contains('No keyring file (SIGNING_KEYRING) has been specified. Assuming the use of local gpgCommand to sign instead.')
        build.stdout.contains('BUILD SUCCESSFUL')
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingRepositoryState == 'closed'
        nexus.unmatchedRequests.isEmpty()

        and: 'every artifact and a signature that verifies against the public key were uploaded'
        Map<String, byte[]> uploads = nexus.stagingUploads
        String prefix = "org/grails/example/${ARTIFACT_ID}/${RELEASE_VERSION}/${ARTIFACT_ID}-${RELEASE_VERSION}"
        ['.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'].every { String suffix ->
            uploads.containsKey("${prefix}${suffix}" as String) &&
                    key.verifies(uploads["${prefix}${suffix}" as String], uploads["${prefix}${suffix}.asc" as String])
        }

        cleanup:
        container?.stop()
    }

}
