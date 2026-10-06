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

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the {@code simple-library} example through every publishing target the plugin supports: snapshot and
 * release builds, Maven and Nexus repositories, keyring based signing, and the release state override (signing with
 * the gpg command is covered by ContainerizedReleaseSpec, inside a container).
 */
class PublishTargetsSpec extends ExampleProjectSpecification {

    static final String ARTIFACT_ID = 'simple-library'
    static final String SNAPSHOT_VERSION = '1.0.0-SNAPSHOT'
    static final String RELEASE_VERSION = '1.2.3'
    static final List<String> PUBLISHED_SUFFIXES = ['.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module']

    def "a snapshot version publishes unsigned artifacts to the MAVEN_PUBLISH repository"() {
        given: 'a file based repository configured through the environment'
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = withEnvironment(runner, [MAVEN_PUBLISH_URL: repository.toUri().toString()])

        when:
        BuildResult result = run(runner, 'publish')

        then: 'the artifacts are built and published without signing'
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':groovydoc').outcome == TaskOutcome.SUCCESS
        result.task(':javadoc').outcome == TaskOutcome.SKIPPED
        signTasks(result).isEmpty()
        !result.output.contains('Signing is enabled due to release configuration.')

        and: 'a timestamped snapshot of every artifact is in the repository'
        Path module = moduleDirectory(repository, ARTIFACT_ID, SNAPSHOT_VERSION)
        List<String> files = publishedFileNames(module)
        files.contains('maven-metadata.xml')
        PUBLISHED_SUFFIXES.every { suffix -> files.any { it ==~ /simple-library-1\.0\.0-\d{8}\.\d{6}-1${suffix.replace('.', '\\.')}/ } }
        !files.any { it.endsWith('.asc') }

        and: 'the jars carry the compiled classes, the resources, the sources and the groovydoc'
        File jar = publishedJar(module)
        jarContains(jar, 'org/grails/example/simple/Greeter.class')
        jarContains(jar, 'org/grails/example/simple/Version.class')
        jarContains(jar, 'META-INF/simple-library.properties')

        File sourcesJar = publishedFile(module, '-sources.jar')
        jarContains(sourcesJar, 'org/grails/example/simple/Greeter.groovy')
        jarContains(sourcesJar, 'org/grails/example/simple/Version.java')
        jarContains(sourcesJar, 'META-INF/simple-library.properties')

        File javadocJar = publishedFile(module, '-javadoc.jar')
        jarContains(javadocJar, 'org/grails/example/simple/Greeter.html')
        jarContains(javadocJar, 'org/grails/example/simple/Version.html')

        and: 'the pom carries the metadata from the grailsPublish block and resolved dependency versions'
        String pom = normalizedXml(publishedFile(module, '.pom'))
        pom.contains('<groupId>org.grails.example</groupId><artifactId>simple-library</artifactId><version>1.0.0-SNAPSHOT</version>')
        pom.contains('<name>Simple Library</name>')
        pom.contains('<description>A Groovy and Java library published with the Grails Publish plugin</description>')
        pom.contains('<url>https://github.com/apache/grails-gradle-publish</url>')
        pom.contains('<license><name>The Apache Software License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license>')
        pom.contains('<developer><id>jdaugherty</id><name>James Daugherty</name></developer>')
        pom.contains('<scm><connection>scm:git@github.com:apache/grails-gradle-publish.git</connection><developerConnection>scm:git@github.com:apache/grails-gradle-publish.git</developerConnection><url>https://github.com/apache/grails-gradle-publish</url></scm>')
        pom.contains('<issueManagement><system>GitHub Issues</system><url>https://github.com/apache/grails-gradle-publish/issues</url></issueManagement>')
        pom.contains('<dependency><groupId>org.apache.groovy</groupId><artifactId>groovy</artifactId><version>4.0.28</version><scope>runtime</scope></dependency>')

        and: 'the Gradle module metadata carries the dependency version too'
        String module_ = publishedFile(module, '.module').text
        module_.contains('"module": "groovy"')
        module_.contains('"requires": "4.0.28"')
    }

    def "a snapshot version publishes with credentials to an https MAVEN_PUBLISH repository, as a CI build does"() {
        given: 'the repository url and credentials come from the environment'
        MockNexus repository = startNexus('not used')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = withEnvironment(runner, repository.mavenEnvironment)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        signTasks(result).isEmpty()

        and: 'every request carried the credentials and every artifact was uploaded'
        repository.unmatchedRequests.isEmpty()
        Set<String> uploads = repository.mavenUploads.keySet()
        PUBLISHED_SUFFIXES.every { suffix -> hasUploadedArtifact(uploads, ARTIFACT_ID, SNAPSHOT_VERSION, suffix) }
        PUBLISHED_SUFFIXES.every { suffix -> hasUploadedArtifact(uploads, ARTIFACT_ID, SNAPSHOT_VERSION, suffix + '.sha1') }
        uploads.contains('org/grails/example/simple-library/1.0.0-SNAPSHOT/maven-metadata.xml')
        uploads.contains('org/grails/example/simple-library/maven-metadata.xml')
        !uploads.any { it.endsWith('.asc') }
    }

    def "credentials from the #repositoryName Gradle properties publish with the configuration cache"() {
        given: 'the repository url from the environment, and its credentials as Gradle properties'
        MockNexus repository = startNexus('not used')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        if (existingRepository) {
            addExistingMavenRepository(runner)
        }
        runner = withEnvironment(runner, [
                MAVEN_PUBLISH_URL: repository.mavenRepositoryUrl,
        ])
        runner = setGradleProperty("${repositoryName}Username", MockNexus.USERNAME, runner)
        runner = setGradleProperty("${repositoryName}Password", MockNexus.PASSWORD, runner)
        String publishTask = ":publishMavenPublicationTo${repositoryName.capitalize()}Repository"

        when:
        BuildResult result = run(runner, 'publish')

        then: 'unlike explicit credentials, Gradle property credentials let the build store an entry'
        result.output.contains('Configuration cache entry stored.')
        result.task(publishTask).outcome == TaskOutcome.SUCCESS

        and: 'every request carried the credentials and every artifact was uploaded'
        repository.unmatchedRequests.isEmpty()
        PUBLISHED_SUFFIXES.every { suffix -> hasUploadedArtifact(repository.mavenUploads.keySet(), ARTIFACT_ID, SNAPSHOT_VERSION, suffix) }

        when: 'the build runs again'
        BuildResult reused = run(runner, 'publish')

        then: 'it publishes a second snapshot from the stored entry, still with the credentials'
        reused.output.contains('Configuration cache entry reused.')
        reused.task(publishTask).outcome == TaskOutcome.SUCCESS
        repository.unmatchedRequests.isEmpty()
        repository.mavenUploads.keySet().count { it ==~ /org\/grails\/example\/simple-library\/1\.0\.0-SNAPSHOT\/simple-library-1\.0\.0-\d{8}\.\d{6}-\d+\.jar/ } == 2

        where:
        existingRepository | repositoryName
        false              | 'maven'
        true               | 'maven2'
    }

    def "a renamed Maven repository publishes anonymously when only another repository has credential properties"() {
        given:
        MockNexus repository = startNexus('not used')
        repository.allowAnonymousMavenPublishing()
        GradleRunner runner = setupExample(ARTIFACT_ID)
        addExistingMavenRepository(runner)
        runner = withEnvironment(runner, [
                MAVEN_PUBLISH_URL               : repository.mavenRepositoryUrl,
                ORG_GRADLE_PROJECT_mavenUsername: 'other-deployer',
                ORG_GRADLE_PROJECT_mavenPassword: 'other-password',
        ])

        when:
        BuildResult result = run(runner, 'publish')

        then: 'the maven properties do not require credentials for maven2'
        result.task(':publishMavenPublicationToMaven2Repository').outcome == TaskOutcome.SUCCESS
        result.output.contains('Configuration cache entry stored.')
        repository.unmatchedRequests.isEmpty()
        PUBLISHED_SUFFIXES.every { suffix -> hasUploadedArtifact(repository.mavenUploads.keySet(), ARTIFACT_ID, SNAPSHOT_VERSION, suffix) }

        when:
        BuildResult reused = run(runner, 'publish')

        then:
        reused.task(':publishMavenPublicationToMaven2Repository').outcome == TaskOutcome.SUCCESS
        reused.output.contains('Configuration cache entry reused.')
        repository.unmatchedRequests.isEmpty()
        repository.mavenUploads.keySet().count { it ==~ /org\/grails\/example\/simple-library\/1\.0\.0-SNAPSHOT\/simple-library-1\.0\.0-\d{8}\.\d{6}-\d+\.jar/ } == 2
    }

    def "a snapshot version publishes to the Nexus snapshot repository when snapshotPublishType is NEXUS_PUBLISH"() {
        given:
        MockNexus nexus = startNexus("org.grails.example:${ARTIFACT_ID}:${SNAPSHOT_VERSION}")
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = withEnvironment(runner, nexus.environment)
        runner = setGradleProperty('snapshotPublishType', 'NEXUS_PUBLISH', runner)

        when:
        BuildResult result = run(runner, 'publishToSonatype')

        then: 'no staging repository is used for a snapshot'
        result.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SKIPPED
        result.task(':publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        nexus.stagingRepositoryState == 'not created'
        nexus.stagingUploads.isEmpty()
        nexus.unmatchedRequests.isEmpty()

        and: 'every artifact is uploaded to the snapshot repository'
        Set<String> uploads = nexus.snapshotUploads.keySet()
        uploads.contains('org/grails/example/simple-library/maven-metadata.xml')
        uploads.contains('org/grails/example/simple-library/1.0.0-SNAPSHOT/maven-metadata.xml')
        PUBLISHED_SUFFIXES.every { suffix -> uploads.any { it ==~ /org\/grails\/example\/simple-library\/1\.0\.0-SNAPSHOT\/simple-library-1\.0\.0-\d{8}\.\d{6}-1${suffix.replace('.', '\\.')}/ } }
        !uploads.any { it.endsWith('.asc') }
    }

    def "a release version publishes signed artifacts to a Nexus staging repository which is then closed and released"() {
        given: 'a release version, a keyring to sign with, and a Nexus to stage in'
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        String description = "org.grails.example:${ARTIFACT_ID}:${RELEASE_VERSION}"
        MockNexus nexus = startNexus(description)

        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = withEnvironment(runner, nexus.environment + [
                SIGNING_KEY       : key.keyId,
                SIGNING_KEYRING   : keyring.absolutePath,
                SIGNING_PASSPHRASE: key.passphrase,
        ])

        when:
        BuildResult result = run(runner, 'publishToSonatype', 'closeAndReleaseSonatypeStagingRepository')

        then: 'the artifacts are signed with the keyring'
        result.output.contains('Signing is enabled due to release configuration.')
        result.output.contains('Keyring file has been specified. Using java to sign.')
        result.task(':signMavenPublication').outcome == TaskOutcome.SUCCESS

        and: 'a staging repository is created with the configured description, then closed and released'
        result.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        result.task(':closeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        result.task(':releaseSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingRepositoryState == 'released'
        nexus.snapshotUploads.isEmpty()
        nexus.unmatchedRequests.isEmpty()

        and: 'every artifact, its checksums and its signature are uploaded to the staging repository'
        Map<String, byte[]> uploads = nexus.stagingUploads
        String prefix = "org/grails/example/simple-library/${RELEASE_VERSION}/simple-library-${RELEASE_VERSION}"
        List<String> expected = PUBLISHED_SUFFIXES.collectMany { suffix ->
            ['', '.md5', '.sha1', '.sha256', '.sha512', '.asc'].collect { sidecar -> "${prefix}${suffix}${sidecar}" as String }
        }
        expected - uploads.keySet() == []
        uploads.containsKey('org/grails/example/simple-library/maven-metadata.xml')

        and: 'the signatures verify against the public key'
        PUBLISHED_SUFFIXES.every { suffix -> key.verifies(uploads["${prefix}${suffix}" as String], uploads["${prefix}${suffix}.asc" as String]) }

        and: 'the pom is the release pom'
        String pom = normalizedXml(new String(uploads["${prefix}.pom" as String], 'UTF-8'))
        pom.contains("<artifactId>simple-library</artifactId><version>${RELEASE_VERSION}</version>")
        pom.contains('<name>Simple Library</name>')
    }

    def "a release can be staged, published, closed and released in separate builds, the way grails-core releases"() {
        given:
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        String description = "org.grails.example:${ARTIFACT_ID}:${RELEASE_VERSION}"
        MockNexus nexus = startNexus(description)

        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = withEnvironment(runner, nexus.environment + [
                SIGNING_KEY       : key.keyId,
                SIGNING_KEYRING   : keyring.absolutePath,
                SIGNING_PASSPHRASE: key.passphrase,
        ])

        when: 'the staging repository is created on its own'
        BuildResult initialize = run(runner, 'initializeSonatypeStagingRepository')

        then:
        initialize.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingRepositoryState == 'open'
        nexus.stagingUploads.isEmpty()

        when: 'a later build finds the repository by its description and publishes into it'
        BuildResult publish = run(runner, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'publishToSonatype')

        then:
        publish.task(':findSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        publish.task(':publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingUploads.containsKey("org/grails/example/simple-library/${RELEASE_VERSION}/simple-library-${RELEASE_VERSION}.jar.asc" as String)
        nexus.stagingRepositoryState == 'open'

        when: 'the repository is closed by another build'
        BuildResult close = run(runner, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'closeSonatypeStagingRepository')

        then: 'the close task, marked as not compatible, runs without storing a configuration cache entry'
        close.task(':closeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        close.output.contains('Configuration cache entry discarded')
        !close.output.contains('Configuration cache entry stored.')
        nexus.stagingRepositoryState == 'closed'

        when: 'and released by yet another'
        BuildResult release = run(runner, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'releaseSonatypeStagingRepository')

        then:
        release.task(':releaseSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        nexus.stagingRepositoryState == 'released'
        nexus.unmatchedRequests.isEmpty()
    }

    def "a release version publishes signed artifacts to a Maven repository when releasePublishType is MAVEN_PUBLISH"() {
        given:
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        Path repository = createTempRepository('maven-publish')

        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = withEnvironment(runner, [
                MAVEN_PUBLISH_URL : repository.toUri().toString(),
                SIGNING_KEY       : key.keyId,
                SIGNING_KEYRING   : keyring.absolutePath,
                SIGNING_PASSPHRASE: key.passphrase,
        ])

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':signMavenPublication').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':initializeSonatypeStagingRepository') == null

        and: 'the signing and publishing tasks of the release were stored in the configuration cache entry'
        result.output.contains('Configuration cache entry stored.')

        and: 'the release and a verifiable signature of each artifact are in the repository'
        Path module = moduleDirectory(repository, ARTIFACT_ID, RELEASE_VERSION)
        List<String> files = publishedFileNames(module)
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${RELEASE_VERSION}${suffix}" as String) }
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${RELEASE_VERSION}${suffix}.asc" as String) }
        PUBLISHED_SUFFIXES.every { suffix ->
            key.verifies(module.resolve("simple-library-${RELEASE_VERSION}${suffix}").toFile(), module.resolve("simple-library-${RELEASE_VERSION}${suffix}.asc").toFile())
        }
        repository.resolve(EXAMPLE_GROUP_PATH).resolve(ARTIFACT_ID).resolve('maven-metadata.xml').text.contains("<release>${RELEASE_VERSION}</release>")
    }

    def "a release with the signing tasks disabled publishes unsigned artifacts"() {
        given: 'the example disables signing when -PskipSigning is set'
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = withEnvironment(runner, [MAVEN_PUBLISH_URL: repository.toUri().toString()])

        when:
        BuildResult result = run(runner, 'publish', '-PskipSigning=true')

        then:
        result.task(':signMavenPublication').outcome == TaskOutcome.SKIPPED
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and:
        List<String> files = publishedFileNames(moduleDirectory(repository, ARTIFACT_ID, RELEASE_VERSION))
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${RELEASE_VERSION}${suffix}" as String) }
        !files.any { it.endsWith('.asc') }
    }

    def "a release without a signing key fails before anything is published"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = withEnvironment(runner, [MAVEN_PUBLISH_URL: repository.toUri().toString()])

        when:
        BuildResult result = runAndFail(runner, 'publish')

        then:
        result.output.contains('No keyring file (SIGNING_KEYRING) has been specified. Assuming the use of local gpgCommand to sign instead.')
        result.output.contains('A signing key is required to sign a release. Set GRAILS_PUBLISH_RELEASE=false to bypass signing.')
        result.task(':signMavenPublication').outcome == TaskOutcome.FAILED
        result.task(':publishMavenPublicationToMavenRepository') == null
        !Files.exists(repository.resolve(EXAMPLE_GROUP_PATH))
    }

    def "GRAILS_PUBLISH_RELEASE=true publishes a snapshot version as a signed release"() {
        given:
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        Path repository = createTempRepository('maven-publish')

        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('releasePublishType', 'MAVEN_PUBLISH', runner)
        runner = withEnvironment(runner, [
                GRAILS_PUBLISH_RELEASE: 'true',
                MAVEN_PUBLISH_URL     : repository.toUri().toString(),
                SIGNING_KEY           : key.keyId,
                SIGNING_KEYRING       : keyring.absolutePath,
                SIGNING_PASSPHRASE    : key.passphrase,
        ])

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.output.contains('Environment Variable `GRAILS_PUBLISH_RELEASE` detected - using variable instead of project version.')
        result.output.contains('Signing is enabled due to release configuration.')
        result.task(':signMavenPublication').outcome == TaskOutcome.SUCCESS

        and: 'the snapshot version is published, signed'
        Path module = moduleDirectory(repository, ARTIFACT_ID, SNAPSHOT_VERSION)
        List<String> files = publishedFileNames(module)
        PUBLISHED_SUFFIXES.every { suffix -> hasPublishedArtifact(module, ARTIFACT_ID, SNAPSHOT_VERSION, suffix) }
        PUBLISHED_SUFFIXES.every { suffix -> hasPublishedArtifact(module, ARTIFACT_ID, SNAPSHOT_VERSION, suffix + '.asc') }
        PUBLISHED_SUFFIXES.every { suffix ->
            key.verifies(publishedArtifact(module, ARTIFACT_ID, SNAPSHOT_VERSION, suffix), publishedArtifact(module, ARTIFACT_ID, SNAPSHOT_VERSION, suffix + '.asc'))
        }
    }

    def "GRAILS_PUBLISH_RELEASE=false publishes a release version as an unsigned snapshot"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)
        runner = withEnvironment(runner, [
                GRAILS_PUBLISH_RELEASE: 'false',
                MAVEN_PUBLISH_URL     : repository.toUri().toString(),
        ])

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.output.contains('Environment Variable `GRAILS_PUBLISH_RELEASE` detected - using variable instead of project version.')
        !result.output.contains('Signing is enabled due to release configuration.')
        signTasks(result).isEmpty()
        result.task(':initializeSonatypeStagingRepository') == null

        and:
        List<String> files = publishedFileNames(moduleDirectory(repository, ARTIFACT_ID, RELEASE_VERSION))
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${RELEASE_VERSION}${suffix}" as String) }
        !files.any { it.endsWith('.asc') }
    }

    def "publishToMavenLocal and its install alias publish to the local Maven repository"() {
        given:
        Path mavenLocal = createTempRepository('maven-local')
        GradleRunner runner = setupExample(ARTIFACT_ID)

        when:
        BuildResult result = run(runner, 'install', "-Dmaven.repo.local=${mavenLocal}")

        then:
        result.task(':install').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenLocal').outcome == TaskOutcome.SUCCESS
        result.task(':publishToMavenLocal').outcome == TaskOutcome.SUCCESS
        signTasks(result).isEmpty()

        and:
        List<String> files = publishedFileNames(moduleDirectory(mavenLocal, ARTIFACT_ID, SNAPSHOT_VERSION))
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${SNAPSHOT_VERSION}${suffix}" as String) }
    }

    def "publishing fails with the documented message when the #setting is missing"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = withEnvironment(runner, [MAVEN_PUBLISH_URL: repository.toUri().toString()])
        File buildFile = new File(runner.projectDir, 'build.gradle')
        assert buildFile.text.contains(configuration)
        buildFile.text = buildFile.text.replaceAll(removed, '')
        assert !buildFile.text.contains(configuration)

        when:
        BuildResult result = runAndFail(runner, 'publish')

        then:
        result.output.contains("No '${setting}' was specified. Please provide a valid publishing configuration.")

        where:
        setting      | configuration   | removed
        'license'    | 'license {'     | /(?s)license \{.*?\}\n/
        'developers' | 'developers = [' | /developers = \[.*?\]\n/
    }

    def "several builds publish into one staging repository, found by its description"() {
        given: 'two independent builds releasing the same version under one description, as grails-core, grails-gradle and grails-forge do'
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        String description = "grails-examples:${RELEASE_VERSION}"
        MockNexus nexus = startNexus(description)
        Map<String, String> environment = nexus.environment + [
                SIGNING_KEY       : key.keyId,
                SIGNING_KEYRING   : keyring.absolutePath,
                SIGNING_PASSPHRASE: key.passphrase,
        ]
        GradleRunner simpleLibrary = withEnvironment(setGradleProperty('projectVersion', RELEASE_VERSION, setupExample('simple-library')), environment)
        Path javaLibraryDir = simpleLibrary.projectDir.toPath().parent.resolve('java-library')
        org.apache.commons.io.FileUtils.copyDirectory(EXAMPLES_DIR.resolve('java-library').toFile(), javaLibraryDir.toFile(), { File file -> !(file.directory && file.name in ['build', '.gradle']) } as FileFilter)

        when: 'the first build creates the staging repository and publishes into it'
        BuildResult first = run(simpleLibrary, 'publishToSonatype')

        then:
        first.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        first.task(':publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        nexus.createdStagingRepositoryDescriptions == [description]

        when: 'the second build finds that repository by its description and publishes into it too'
        GradleRunner javaLibrary = withEnvironment(setGradleProperty('projectVersion', RELEASE_VERSION, setupProject(javaLibraryDir)), environment)
        BuildResult second = run(javaLibrary, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'publishToSonatype')

        then:
        second.task(':findSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        second.task(':publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        nexus.createdStagingRepositoryDescriptions == [description]

        when: 'the first build closes it'
        BuildResult closed = run(simpleLibrary, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'closeSonatypeStagingRepository')

        then:
        closed.task(':closeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        nexus.stagingRepositoryState == 'closed'

        and: 'both modules are in the one staging repository, signed'
        Map<String, byte[]> uploads = nexus.stagingUploads
        ['simple-library', 'java-library'].every { String name ->
            PUBLISHED_SUFFIXES.every { String suffix ->
                String path = "org/grails/example/${name}/${RELEASE_VERSION}/${name}-${RELEASE_VERSION}${suffix}"
                uploads.containsKey(path) && key.verifies(uploads[path], uploads["${path}.asc" as String])
            }
        }
        nexus.unmatchedRequests.isEmpty()
    }

    def "a release version publishes to the local Maven repository unsigned when the signing tasks are disabled"() {
        given: 'the reproducible build verification of grails-core: a release version, published locally, without a key'
        Path mavenLocal = createTempRepository('maven-local')
        GradleRunner runner = setupExample(ARTIFACT_ID)
        runner = setGradleProperty('projectVersion', RELEASE_VERSION, runner)

        when:
        BuildResult result = run(runner, 'publishToMavenLocal', "-Dmaven.repo.local=${mavenLocal}", '-PskipSigning=true')

        then:
        result.output.contains('Signing is enabled due to release configuration.')
        result.task(':signMavenPublication').outcome == TaskOutcome.SKIPPED
        result.task(':publishMavenPublicationToMavenLocal').outcome == TaskOutcome.SUCCESS

        and:
        List<String> files = publishedFileNames(moduleDirectory(mavenLocal, ARTIFACT_ID, RELEASE_VERSION))
        PUBLISHED_SUFFIXES.every { suffix -> files.contains("simple-library-${RELEASE_VERSION}${suffix}" as String) }
        !files.any { it.endsWith('.asc') }
    }

    private static void addExistingMavenRepository(GradleRunner runner) {
        new File(runner.projectDir, 'build.gradle') << '''
            publishing.repositories.maven {
                url = layout.buildDirectory.dir('existing-repository')
            }
        '''.stripIndent()
    }

    private static List<String> signTasks(BuildResult result) {
        result.tasks*.path.findAll { it.contains(':sign') }
    }
}
