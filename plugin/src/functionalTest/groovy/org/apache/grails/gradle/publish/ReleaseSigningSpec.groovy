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

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Requires
import spock.lang.Shared

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Signs and publishes releases with a throwaway GPG key, to cover the sign and publish task wiring.
 */
@Requires({ ReleaseSigningSpec.gpgAvailable() })
class ReleaseSigningSpec extends GradleSpecification {

    @Shared
    Path gnupgHome

    @Shared
    String keyId

    List<File> toCleanup = []

    void setupSpec() {
        // keep the path short, since gpg-agent's socket path is limited in length
        gnupgHome = Files.createTempDirectory('gpg')
        gpg('--batch', '--passphrase', '', '--quick-gen-key', 'Throwaway Test Key <throwaway@example.invalid>', 'rsa2048', 'sign', 'never')
        keyId = gpg('--list-keys', '--with-colons').readLines()
                .find { it.startsWith('pub:') }
                .split(':')[4]
                .takeRight(8)
    }

    void cleanup() {
        toCleanup.each { it.deleteDir() }
    }

    void cleanupSpec() {
        runGpgconf('--kill', 'gpg-agent')
        gnupgHome.toFile().deleteDir()
    }

    def "a signed release signs every published file - #description"() {
        given:
        File repository = File.createTempDir('release-repository')
        File mavenLocal = File.createTempDir('release-maven-local')
        toCleanup << repository << mavenLocal

        and:
        GradleRunner runner = setupTestResourceProject('other-artifacts', fixture)
        runner = setGradleProperty('projectVersion', '0.0.1', runner)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)
        runner = addEnvironmentVariable('GRAILS_PUBLISH_RELEASE', 'true', runner)
        // the build environment is otherwise empty, and signing runs the gpg command
        runner = addEnvironmentVariable('PATH', System.getenv('PATH'), runner)
        runner = addEnvironmentVariable('GNUPGHOME', gnupgHome.toAbsolutePath().toString(), runner)
        runner = addEnvironmentVariable('SIGNING_KEY', keyId, runner)
        environment.each { String key, String value ->
            runner = addEnvironmentVariable(key, value, runner)
        }

        when:
        executeTask('publish', ['publishToMavenLocal', "-Dmaven.repo.local=${mavenLocal.absolutePath}".toString()], runner)

        then:
        List<File> published = publishedFiles(repository)
        published
        published.findAll { !new File("${it.path}.asc").exists() } == []

        and:
        List<File> publishedLocally = publishedFiles(mavenLocal)
        publishedLocally
        publishedLocally.findAll { !new File("${it.path}.asc").exists() } == []

        where:
        fixture                  | environment                              | description
        'simple-project'         | [:]                                      | 'one publication'
        'additional-publication' | [:]                                      | 'an additional publication'
        'gradle-plugin-project'  | [:]                                      | 'Gradle plugin project, java-gradle-plugin applied last'
        'gradle-plugin-project'  | [APPLY_JAVA_GRADLE_PLUGIN_FIRST: 'true'] | 'Gradle plugin project, java-gradle-plugin applied first'
        'gradle-plugin-project'  | [PUBLISH_SHARED_ARTIFACTS: 'true']       | 'publications sharing artifacts'
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

    private String gpg(String... arguments) {
        run(['gpg', '--homedir', gnupgHome.toString()] + arguments.toList())
    }

    private void runGpgconf(String... arguments) {
        try {
            run(['gpgconf', '--homedir', gnupgHome.toString()] + arguments.toList())
        } catch (Exception ignored) {
            // the agent is gone with the temporary directory either way
        }
    }

    private static String run(List<String> command) {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start()
        String output = process.inputStream.text
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException("${command.join(' ')} failed:\n${output}")
        }
        output
    }

    static boolean gpgAvailable() {
        try {
            run(['gpg', '--version'])
            true
        } catch (Exception ignored) {
            false
        }
    }
}
