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

package org.apache.grails.gradle.publish

import org.apache.grails.gradle.publish.examples.TestSigningKey
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Shared

/**
 * Signs and publishes releases with a throwaway key, to cover the sign and publish task wiring. The key is generated
 * in process and used through a keyring file, so no gpg command is needed; signing with the gpg command is covered
 * by ContainerizedReleaseSpec, inside a container.
 */
class ReleaseSigningSpec extends GradleSpecification {

    @Shared
    TestSigningKey key

    @Shared
    File keyring

    List<File> toCleanup = []

    void setupSpec() {
        key = TestSigningKey.generate()
        keyring = key.writeSecretKeyRing(new File(File.createTempDir('release-signing'), 'secring.gpg'))
    }

    void cleanup() {
        toCleanup.each { it.deleteDir() }
    }

    void cleanupSpec() {
        keyring.parentFile.deleteDir()
    }

    def "a signed release signs every published file - #description"() {
        given:
        File repository = File.createTempDir('release-repository')
        File mavenLocal = File.createTempDir('release-maven-local')
        toCleanup << repository << mavenLocal

        and:
        GradleRunner runner = signingRelease(setupTestResourceProject('other-artifacts', fixture))
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)
        environment.each { String name, String value ->
            runner = addEnvironmentVariable(name, value, runner)
        }

        when:
        BuildResult result = executeTask('publish', ['publishToMavenLocal', "-Dmaven.repo.local=${mavenLocal.absolutePath}".toString()], runner)

        then: 'publications with the same coordinates publish one after the other, to each target'
        !sharedCoordinates || (
                publishedBefore(result, 'publishMavenPublicationToMavenRepository', 'publishPluginMavenPublicationToMavenRepository') &&
                        publishedBefore(result, 'publishMavenPublicationToMavenLocal', 'publishPluginMavenPublicationToMavenLocal'))

        and:
        List<File> published = publishedFiles(repository)
        published
        published.findAll { !signatureVerifies(it) } == []

        and:
        List<File> publishedLocally = publishedFiles(mavenLocal)
        publishedLocally
        publishedLocally.findAll { !signatureVerifies(it) } == []

        where:
        fixture                  | environment                              | sharedCoordinates | description
        'simple-project'         | [:]                                      | false             | 'one publication'
        'additional-publication' | [:]                                      | false             | 'an additional publication'
        'gradle-plugin-project'  | [:]                                      | false             | 'Gradle plugin project, java-gradle-plugin applied last'
        'gradle-plugin-project'  | [APPLY_JAVA_GRADLE_PLUGIN_FIRST: 'true'] | false             | 'Gradle plugin project, java-gradle-plugin applied first'
        'gradle-plugin-project'  | [PUBLISH_SHARED_ARTIFACTS: 'true']       | true              | 'publications sharing artifacts'
    }

    /** Whether the first task finished before the second started, using timestamps unaffected by console buffering */
    private static boolean publishedBefore(BuildResult result, String first, String second) {
        def firstEnd = result.output =~ /(?m)^PUBLISH-END ${first} (-?\d+)\r?$/
        def secondStart = result.output =~ /(?m)^PUBLISH-START ${second} (-?\d+)\r?$/
        firstEnd.find() && secondStart.find() && firstEnd.group(1).toLong() < secondStart.group(1).toLong()
    }

    def "a signed release publishes the grails-plugin.xml without signing it inside the classes directory"() {
        given:
        File repository = File.createTempDir('release-repository')
        toCleanup << repository

        and:
        GradleRunner runner = signingRelease(setupTestResourceProject('other-artifacts', 'grails-plugin-project'))
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)

        and: 'the grails-plugin.xml exists: without the Grails plugin Gradle plugin, it is only published when it does at configuration time'
        executeTask('classes', runner)

        when:
        executeTask('publish', runner)

        then: 'the grails-plugin.xml is published and signed'
        File publishedPluginXml = new File(repository, 'org/grails/example/grails-plugin-project/0.0.1/grails-plugin-project-0.0.1-plugin.xml')
        publishedPluginXml.text == '<plugin name="grails-plugin-project"/>'
        signatureVerifies(publishedPluginXml)

        and: 'its signature is not written into the classes directory, which other tasks read'
        !new File(runner.projectDir, 'build/classes/groovy/main/META-INF/grails-plugin.xml.asc').exists()
    }

    def "a signed release of a Grails plugin project publishes the descriptor built by compileGroovy from a clean checkout"() {
        given:
        File repository = File.createTempDir('release-repository')
        toCleanup << repository

        and:
        GradleRunner runner = signingRelease(setupTestResourceProject('other-artifacts', 'grails-plugin-descriptor'))
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)

        when: 'publishing without having compiled anything before'
        executeTask('publish', runner)

        then:
        File publishedPluginXml = new File(repository, 'org/grails/example/grails-plugin-descriptor/0.0.1/grails-plugin-descriptor-0.0.1-plugin.xml')
        publishedPluginXml.text.contains("<plugin name='descriptorPlugin' version='0.0.1'")
        signatureVerifies(publishedPluginXml)
        !new File(runner.projectDir, 'build/classes/groovy/main/META-INF/grails-plugin.xml.asc').exists()
    }

    /** A release build that signs with the keyring file */
    private GradleRunner signingRelease(GradleRunner runner) {
        runner = setGradleProperty('projectVersion', '0.0.1', runner)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = addEnvironmentVariable('GRAILS_PUBLISH_RELEASE', 'true', runner)
        runner = addEnvironmentVariable('SIGNING_KEY', key.keyId, runner)
        runner = addEnvironmentVariable('SIGNING_KEYRING', keyring.absolutePath, runner)
        addEnvironmentVariable('SIGNING_PASSPHRASE', key.passphrase, runner)
    }

    private static List<File> publishedFiles(File directory) {
        List<File> files = []
        directory.eachFileRecurse { File file ->
            if (file.name ==~ /.*\.(jar|pom|module)/) {
                files << file
            }
        }
        files
    }

    /** Whether the file's .asc signature exists and verifies against the throwaway key */
    private boolean signatureVerifies(File file) {
        File signature = new File("${file.path}.asc")
        signature.exists() && key.verifies(file, signature)
    }
}
