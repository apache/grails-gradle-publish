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
 * Publishes every example project shape (a snapshot, to a file based Maven repository unless stated otherwise)
 * and checks the artifacts and metadata each shape is expected to produce.
 */
class ExampleProjectsSpec extends ExampleProjectSpecification {

    static final String VERSION = '1.0.0-SNAPSHOT'

    def "java-library: a Java only project publishes a javadoc jar built by javadoc"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('java-library')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':javadoc').outcome == TaskOutcome.SUCCESS
        result.task(':groovydoc') == null
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and:
        Path module = moduleDirectory(repository, 'java-library', VERSION)
        jarContains(publishedJar(module), 'org/grails/example/javalib/Calculator.class')
        jarContains(publishedFile(module, '-sources.jar'), 'org/grails/example/javalib/Calculator.java')
        jarContains(publishedFile(module, '-javadoc.jar'), 'org/grails/example/javalib/Calculator.html')
        jarContains(publishedFile(module, '-javadoc.jar'), 'index.html')

        and: 'the pom has no dependencies'
        !normalizedXml(publishedFile(module, '.pom')).contains('<dependencies>')
    }

    def "kotlin-dsl: the plugin is configured from a Kotlin build script"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('kotlin-dsl')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and:
        Path module = moduleDirectory(repository, 'kotlin-dsl', VERSION)
        jarContains(publishedJar(module), 'org/grails/example/kotlindsl/KotlinDslGreeter.class')
        jarContains(publishedFile(module, '-javadoc.jar'), 'org/grails/example/kotlindsl/KotlinDslGreeter.html')

        and: 'the pom carries the values set through the Kotlin friendly API'
        String pom = normalizedXml(publishedFile(module, '.pom'))
        pom.contains('<name>Kotlin DSL Library</name>')
        pom.contains('<url>https://github.com/apache/grails-gradle-publish</url>')
        pom.contains('<organization><name>Apache Software Foundation</name><url>https://www.apache.org/</url></organization>')
        pom.contains('<license><name>The Apache Software License, Version 2.0</name>')
        pom.contains('<developer><id>jdaugherty</id><name>James Daugherty</name></developer>')
    }

    def "multi-project-root-configured: subprojects configured from the root publish, including to the test repository"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('multi-project-root-configured')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then: 'each subproject publishes; the root has nothing to publish'
        result.task(':core:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':web:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository') == null

        and:
        Path core = moduleDirectory(repository, 'core', VERSION)
        Path web = moduleDirectory(repository, 'web', VERSION)
        jarContains(publishedJar(core), 'org/grails/example/multi/core/CoreService.class')
        jarContains(publishedJar(web), 'org/grails/example/multi/web/WebController.class')

        and: 'the project dependency is published with the version of the referenced project'
        normalizedXml(publishedFile(web, '.pom')).contains('<dependency><groupId>org.grails.example</groupId><artifactId>core</artifactId><version>1.0.0-SNAPSHOT</version><scope>compile</scope></dependency>')
        normalizedXml(publishedFile(web, '.pom')).contains('<name>Multi Project - web</name>')

        and: 'the test repository in the build directory received both modules as well'
        Path testRepository = runner.projectDir.toPath().resolve('build/local-maven')
        result.task(':core:publishMavenPublicationToTestCaseMavenRepoRepository').outcome == TaskOutcome.SUCCESS
        hasPublishedArtifact(moduleDirectory(testRepository, 'core', VERSION), 'core', VERSION, '.jar')
        hasPublishedArtifact(moduleDirectory(testRepository, 'web', VERSION), 'web', VERSION, '.jar')

        when: 'the aggregate task publishes to the test repository alone'
        BuildResult aggregate = run(runner, 'publishAllPublicationsToTestCaseMavenRepoRepository')

        then:
        aggregate.task(':publishAllPublicationsToTestCaseMavenRepoRepository').outcome == TaskOutcome.SUCCESS
        aggregate.task(':core:publishAllPublicationsToTestCaseMavenRepoRepository').outcome == TaskOutcome.SUCCESS
        aggregate.task(':web:publishAllPublicationsToTestCaseMavenRepoRepository').outcome == TaskOutcome.SUCCESS
        aggregate.task(':core:publishMavenPublicationToMavenRepository') == null
    }

    def "multi-project-per-subproject: subprojects applying the plugin themselves publish a snapshot"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('multi-project-per-subproject')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':core:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':extras:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':initializeSonatypeStagingRepository') == null

        and:
        jarContains(publishedJar(moduleDirectory(repository, 'core', VERSION)), 'org/grails/example/multi/core/CoreService.class')
        Path extras = moduleDirectory(repository, 'extras', VERSION)
        jarContains(publishedJar(extras), 'org/grails/example/multi/extras/ExtraService.class')
        normalizedXml(publishedFile(extras, '.pom')).contains('<dependency><groupId>org.grails.example</groupId><artifactId>core</artifactId><version>1.0.0-SNAPSHOT</version><scope>compile</scope></dependency>')
    }

    def "multi-project-per-subproject: a release stages every subproject in one Nexus staging repository"() {
        given: 'the root project applies the Nexus publish plugin itself'
        String version = '2.0.0'
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        String description = "org.grails.example:multi-project-per-subproject:${version}"
        MockNexus nexus = startNexus(description)

        GradleRunner runner = setupExample('multi-project-per-subproject')
        runner = setGradleProperty('projectVersion', version, runner)
        runner = withEnvironment(runner, nexus.environment + [
                SIGNING_KEY       : key.keyId,
                SIGNING_KEYRING   : keyring.absolutePath,
                SIGNING_PASSPHRASE: key.passphrase,
        ])

        when:
        BuildResult result = run(runner, 'publishToSonatype', 'closeSonatypeStagingRepository')

        then:
        result.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        result.task(':core:signMavenPublication').outcome == TaskOutcome.SUCCESS
        result.task(':extras:signMavenPublication').outcome == TaskOutcome.SUCCESS
        result.task(':core:publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        result.task(':extras:publishMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        result.task(':closeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS

        and: 'one staging repository holds both modules, signed'
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingRepositoryState == 'closed'
        Map<String, byte[]> uploads = nexus.stagingUploads
        ['core', 'extras'].every { String name ->
            ['.jar', '.pom', '.module', '-sources.jar', '-javadoc.jar'].every { String suffix ->
                String path = "org/grails/example/${name}/${version}/${name}-${version}${suffix}"
                uploads.containsKey(path) && key.verifies(uploads[path], uploads["${path}.asc" as String])
            }
        }
        nexus.unmatchedRequests.isEmpty()
    }

    def "bom: a java-platform project publishes a pom only bill of materials"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('bom')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then: 'there is nothing to compile, document or package'
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':jar') == null
        result.task(':sourcesJar') == null
        result.task(':javadocJar') == null

        and:
        Path module = moduleDirectory(repository, 'example-bom', VERSION)
        publishedFileNames(module).findAll { it.startsWith('example-bom') }*.replaceAll(/example-bom-1\.0\.0-\d{8}\.\d{6}-1/, 'example-bom') == ['example-bom.module', 'example-bom.pom']

        and: 'the pom keeps its dependencyManagement section and the custom property'
        String pom = normalizedXml(publishedFile(module, '.pom'))
        pom.contains('<packaging>pom</packaging>')
        pom.contains('<name>Example BOM</name>')
        pom.contains('<dependencyManagement><dependencies>')
        pom.contains('<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>')
        pom.contains('<dependency><groupId>org.apache.groovy</groupId><artifactId>groovy-bom</artifactId><version>4.0.28</version><type>pom</type><scope>import</scope></dependency>')
        pom.contains('<properties><groovy.version>4.0.28</groovy.version></properties>')
    }

    def "grails-plugin: the plugin descriptor is built and published as a -plugin.xml artifact from a clean checkout"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('grails-plugin')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':compileGroovy').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        Path module = moduleDirectory(repository, 'grails-plugin', VERSION)
        File descriptor = publishedFile(module, '-plugin.xml')
        descriptor.text.contains("<plugin name='exampleGrailsPlugin' version='1.0.0-SNAPSHOT'")
        descriptor.text.contains('<type>org.grails.example.plugin.ExampleGrailsPlugin</type>')
        jarContains(publishedJar(module), 'META-INF/grails-plugin.xml')
    }

    def "additional-publication: a companion artifact publishes under its own coordinate"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('additional-publication')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishCliPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':cliSourcesJar').outcome == TaskOutcome.SUCCESS
        result.task(':cliGroovydoc').outcome == TaskOutcome.SUCCESS
        result.task(':cliJavadocJar').outcome == TaskOutcome.SUCCESS

        and: 'the library carries no trace of the cli tier'
        Path library = moduleDirectory(repository, 'additional-publication', VERSION)
        jarContains(publishedJar(library), 'org/grails/example/additional/Library.class')
        !jarContains(publishedJar(library), 'org/grails/example/additional/cli/LibraryCommand.class')
        !jarContains(publishedFile(library, '-sources.jar'), 'org/grails/example/additional/cli/LibraryCommand.groovy')
        String libraryPom = normalizedXml(publishedFile(library, '.pom'))
        !libraryPom.contains('commons-lang3')
        !libraryPom.contains('additional-publication-cli')

        and: 'the library module metadata points at the cli coordinate'
        String libraryModule = publishedFile(library, '.module').text
        libraryModule.contains('"name": "cliApiElements"')
        libraryModule.contains('"available-at"')
        libraryModule.contains('"module": "additional-publication-cli"')
        !libraryModule.contains('commons-lang3')

        and: 'the companion is a complete, unclassified artifact of its own'
        Path cli = moduleDirectory(repository, 'additional-publication-cli', VERSION)
        List<String> cliFiles = publishedFileNames(cli).findAll { it.startsWith('additional-publication-cli') }*.replaceAll(/-1\.0\.0-\d{8}\.\d{6}-1/, '')
        cliFiles == ['additional-publication-cli-javadoc.jar', 'additional-publication-cli-sources.jar', 'additional-publication-cli.jar', 'additional-publication-cli.module', 'additional-publication-cli.pom']
        jarContains(publishedJar(cli), 'org/grails/example/additional/cli/LibraryCommand.class')
        !jarContains(publishedJar(cli), 'org/grails/example/additional/Library.class')
        jarContains(publishedFile(cli, '-sources.jar'), 'org/grails/example/additional/cli/LibraryCommand.groovy')
        jarContains(publishedFile(cli, '-javadoc.jar'), 'org/grails/example/additional/cli/LibraryCommand.html')

        and: 'the companion pom depends on the library and its own dependencies, all versioned'
        String cliPom = normalizedXml(publishedFile(cli, '.pom'))
        !cliPom.contains('<packaging>pom</packaging>')
        cliPom.contains('<name>Additional Publication - CLI</name>')
        cliPom.contains('<description>The cli companion artifact of the additional-publication example</description>')
        cliPom.contains('<dependency><groupId>org.grails.example</groupId><artifactId>additional-publication</artifactId><version>1.0.0-SNAPSHOT</version><scope>compile</scope></dependency>')
        cliPom.contains('<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version><scope>runtime</scope></dependency>')
        !cliPom.contains('<version></version>')
    }

    def "managed-dependency-versions: versions from a platform and the dependency management plugin are resolved into the pom"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('managed-dependency-versions')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when: 'published without the configuration cache, which the dependency management plugin does not support here'
        // io.spring.dependency-management adds its own pom customization to every Maven publication, even when
        // generatedPomCustomization is disabled, and that action holds the project, so the pom task cannot be stored
        BuildResult result = run(runner, '--no-configuration-cache', 'publish')

        then:
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and: 'the pom has a version for every dependency and no dependencyManagement section'
        Path module = moduleDirectory(repository, 'managed-dependency-versions', VERSION)
        String pom = normalizedXml(publishedFile(module, '.pom'))
        !pom.contains('<dependencyManagement>')
        pom ==~ /.*<dependency><groupId>org\.slf4j<\/groupId><artifactId>slf4j-api<\/artifactId><version>[0-9.]+<\/version><scope>compile<\/scope><\/dependency>.*/
        pom ==~ /.*<dependency><groupId>org\.apache\.groovy<\/groupId><artifactId>groovy<\/artifactId><version>4\.0\.28<\/version><scope>runtime<\/scope><\/dependency>.*/
        pom ==~ /.*<dependency><groupId>org\.apache\.commons<\/groupId><artifactId>commons-lang3<\/artifactId><version>[0-9.]+<\/version><scope>runtime<\/scope><\/dependency>.*/
        !pom.contains('<version></version>')

        and: 'the Gradle module metadata has the versions too'
        def metadata = new groovy.json.JsonSlurper().parse(publishedFile(module, '.module'))
        List dependencies = metadata.variants.collectMany { it.dependencies ?: [] }
        dependencies.every { it.version?.requires }
        dependencies.find { it.module == 'slf4j-api' }.version.requires ==~ /[0-9.]+/
        dependencies.find { it.module == 'commons-lang3' }.version.requires ==~ /[0-9.]+/
        dependencies.find { it.module == 'groovy' }.version.requires == '4.0.28'
    }

    def "custom-pom: coordinates, urls, license, developers, organization and pom customization are all honoured"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('custom-pom')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then: 'the publication carries the configured name'
        result.task(':publishLibraryPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository') == null

        and: 'the module is published under the overridden coordinates'
        Path module = moduleDirectory(repository, 'custom-pom-library', VERSION, 'org/grails/example/custom')
        !Files.exists(repository.resolve(EXAMPLE_GROUP_PATH).resolve('custom-pom'))
        jarContains(publishedJar(module), 'org/grails/example/custom/CustomPom.class')

        and:
        String pom = normalizedXml(publishedFile(module, '.pom'))
        pom.contains('<groupId>org.grails.example.custom</groupId><artifactId>custom-pom-library</artifactId><version>1.0.0-SNAPSHOT</version>')
        pom.contains('<name>Custom Pom Library</name><description>Shows every pom setting the Grails Publish plugin supports</description><url>https://example.org/custom-pom</url>')
        pom.contains('<organization><name>Example Org</name><url>https://example.org</url></organization>')
        pom.contains('<license><name>Example Public License 1.0</name><url>https://example.org/license</url><distribution>repo</distribution></license>')
        pom.contains('<developer><id>janedoe</id><name>Jane Doe</name><email>jane@example.org</email><organization>Example Org</organization><roles><role>lead</role></roles></developer>')
        pom.contains('<developer><id>johndoe</id><name>John Doe</name></developer>')
        pom.contains('<scm><connection>scm:git:https://git.example.org/custom-pom.git</connection><developerConnection>scm:git:https://git.example.org/custom-pom.git</developerConnection><url>https://git.example.org/custom-pom</url></scm>')
        pom.contains('<issueManagement><system>Jira</system><url>https://issues.example.org/browse/CUSTOM</url></issueManagement>')
        pom.contains('<inceptionYear>2024</inceptionYear>')
        pom.contains('<properties><example.build.tool>gradle</example.build.tool></properties>')
    }

    def "test-sources: the compiled test classes publish as a tests jar"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('test-sources')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':testSourcesJar').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and:
        Path module = moduleDirectory(repository, 'test-sources', VERSION)
        File testsJar = publishedFile(module, '-tests.jar')
        jarContains(testsJar, 'org/grails/example/testsources/CounterTestSupport.class')
        jarContains(testsJar, 'org/grails/example/testsources/CounterSpec.class')
        !jarContains(testsJar, 'org/grails/example/testsources/Counter.class')
        !jarContains(publishedJar(module), 'org/grails/example/testsources/CounterTestSupport.class')

        and: 'the test dependencies stay out of the pom'
        !normalizedXml(publishedFile(module, '.pom')).contains('spock')
    }

    def "gradle-plugin: the pluginMaven publication of a Gradle plugin is completed by the Grails Publish plugin"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('gradle-plugin')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then: 'only the java-gradle-plugin publications exist'
        result.task(':publishPluginMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishHelloPluginMarkerMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository') == null

        and: 'the plugin jar is published with sources, javadoc and the pom metadata'
        Path module = moduleDirectory(repository, 'gradle-plugin', VERSION)
        jarContains(publishedJar(module), 'org/grails/example/gradle/HelloPlugin.class')
        jarContains(publishedJar(module), 'META-INF/gradle-plugins/org.grails.example.hello.properties')
        jarContains(publishedFile(module, '-sources.jar'), 'org/grails/example/gradle/HelloPlugin.groovy')
        jarContains(publishedFile(module, '-javadoc.jar'), 'org/grails/example/gradle/HelloPlugin.html')
        String pom = normalizedXml(publishedFile(module, '.pom'))
        pom.contains('<name>Example Gradle Plugin</name><description>A Gradle plugin published with the Grails Publish plugin</description>')
        pom.contains('<license><name>The Apache Software License, Version 2.0</name>')

        and: 'the plugin marker points at the plugin'
        Path marker = moduleDirectory(repository, 'org.grails.example.hello.gradle.plugin', VERSION, 'org/grails/example/hello')
        normalizedXml(publishedFile(marker, '.pom')).contains('<dependency><groupId>org.grails.example</groupId><artifactId>gradle-plugin</artifactId><version>1.0.0-SNAPSHOT</version></dependency>')
    }

    def "multi-project-grails-core-layout: the plugin applied from the root to allow-listed subprojects publishes every project type"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('multi-project-grails-core-layout')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then: 'every project type publishes (the snapshot state came from the projectVersion property, as no project had a version when the plugin was applied)'
        result.task(':acme-core:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':acme-bom:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':acme-starter:publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':acme-gradle-plugin:publishPluginMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':acme-gradle-plugin:publishAcmePluginMarkerMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository') == null

        and: 'the core module publishes its test fixtures as a capability variant, with the conventions in the pom'
        Path core = moduleDirectory(repository, 'acme-core', VERSION)
        jarContains(publishedJar(core), 'org/grails/example/acme/core/CoreService.class')
        jarContains(publishedArtifact(core, 'acme-core', VERSION, '-test-fixtures.jar'), 'org/grails/example/acme/core/CoreTestSupport.class')
        jarContains(publishedFile(core, '-javadoc.jar'), 'org/grails/example/acme/core/CoreService.html')
        String corePom = normalizedXml(publishedFile(core, '.pom'))
        corePom.contains('<name>Acme Core</name><description>The core module of the Acme framework</description>')
        corePom.contains('<organization><name>Apache Software Foundation</name><url>https://apache.org/</url></organization>')
        corePom.contains('<developer><id>graemerocher</id><name>Graeme Rocher</name><roles><role>Founder</role></roles></developer>')
        corePom.contains('<developer><id>jdaugherty</id><name>James Daugherty</name><roles><role>Developer</role></roles></developer>')
        corePom.contains('<dependency><groupId>org.apache.groovy</groupId><artifactId>groovy</artifactId><version>4.0.28</version><scope>runtime</scope></dependency>')
        !corePom.contains('<dependencyManagement>')
        def coreMetadata = new groovy.json.JsonSlurper().parse(publishedFile(core, '.module'))
        ['testFixturesApiElements', 'testFixturesRuntimeElements'].every { String name ->
            def variant = coreMetadata.variants.find { it.name == name }
            variant.capabilities*.name == ['acme-core-test-fixtures'] &&
                    variant.dependencies.find { it.module == 'commons-lang3' }.version.requires == '3.17.0'
        }
        coreMetadata.variants.every { variant -> (variant.dependencies ?: []).every { it.version?.requires } }

        and: 'the BOM constrains the sibling projects, through property references'
        Path bom = moduleDirectory(repository, 'acme-bom', VERSION)
        String bomPom = normalizedXml(publishedFile(bom, '.pom'))
        bomPom.contains('<packaging>pom</packaging>')
        bomPom.contains('<dependency><groupId>org.grails.example</groupId><artifactId>acme-core</artifactId><version>${acme-core.version}</version></dependency>')
        bomPom.contains('<dependency><groupId>org.apache.groovy</groupId><artifactId>groovy-bom</artifactId><version>${groovy-bom.version}</version><type>pom</type><scope>import</scope></dependency>')
        bomPom.contains('<acme-core.version>1.0.0-SNAPSHOT</acme-core.version>')
        bomPom.contains('<commons-lang3.version>3.17.0</commons-lang3.version>')

        and: 'the dependency-only starter publishes without sources or javadoc, with the BOM managed versions in its pom'
        Path starter = moduleDirectory(repository, 'acme-starter', VERSION)
        !publishedFileNames(starter).any { it.endsWith('-sources.jar') || it.endsWith('-javadoc.jar') }
        String starterPom = normalizedXml(publishedFile(starter, '.pom'))
        starterPom.contains('<dependency><groupId>org.grails.example</groupId><artifactId>acme-core</artifactId><version>1.0.0-SNAPSHOT</version><scope>compile</scope></dependency>')
        starterPom.contains('<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version><scope>compile</scope></dependency>')
        !starterPom.contains('<dependencyManagement>')

        and: 'the Gradle plugin publishes through the pluginMaven publication with its marker'
        Path gradlePlugin = moduleDirectory(repository, 'acme-gradle-plugin', VERSION)
        jarContains(publishedJar(gradlePlugin), 'META-INF/gradle-plugins/org.grails.example.acme.properties')
        normalizedXml(publishedFile(gradlePlugin, '.pom')).contains('<name>Acme Gradle Plugin</name>')
        Files.isDirectory(moduleDirectory(repository, 'org.grails.example.acme.gradle.plugin', VERSION, 'org/grails/example/acme'))

        when: 'the root aggregate task, wired by the plugin, publishes the whole build to the test repository'
        BuildResult aggregate = run(runner, 'publishAllPublicationsToTestCaseMavenRepoRepository')

        then:
        aggregate.task(':publishAllPublicationsToTestCaseMavenRepoRepository').outcome == TaskOutcome.SUCCESS
        ['acme-core', 'acme-bom', 'acme-starter', 'acme-gradle-plugin'].every {
            aggregate.task(":${it}:publishAllPublicationsToTestCaseMavenRepoRepository").outcome == TaskOutcome.SUCCESS
        }
        Path testRepository = runner.projectDir.toPath().resolve('build/local-maven')
        hasPublishedArtifact(moduleDirectory(testRepository, 'acme-core', VERSION), 'acme-core', VERSION, '-test-fixtures.jar')
        hasPublishedArtifact(moduleDirectory(testRepository, 'acme-bom', VERSION), 'acme-bom', VERSION, '.pom')

        when: 'a separate build consumes the published artifacts'
        GradleRunner consumer = setupProject(runner.projectDir.toPath().resolve('consumer'))
        BuildResult consumed = run(consumer, 'resolve', 'acmeHello', "-PpublishedRepositoryUrl=${testRepository.toUri()}", "-PpublishedVersion=${VERSION}")

        then: 'the starter, the BOM managed versions, the test fixtures and the Gradle plugin all resolve'
        consumed.task(':resolve').outcome == TaskOutcome.SUCCESS
        consumed.task(':acmeHello').outcome == TaskOutcome.SUCCESS
        consumed.output.contains('Hello from the Acme Gradle plugin')
        consumed.output.contains('runtimeClasspath: [acme-core-1.0.0-SNAPSHOT.jar, acme-starter-1.0.0-SNAPSHOT.jar, commons-lang3-3.17.0.jar, groovy-4.0.28.jar]')
        consumed.output.contains('testCompileClasspath: [acme-core-1.0.0-SNAPSHOT-test-fixtures.jar, acme-core-1.0.0-SNAPSHOT.jar, acme-starter-1.0.0-SNAPSHOT.jar, commons-lang3-3.17.0.jar]')
    }

    def "multi-project-grails-core-layout: a release is staged the way the grails-core workflow stages it"() {
        given:
        String version = '3.0.0'
        TestSigningKey key = TestSigningKey.generate()
        File keyring = key.writeSecretKeyRing(createTempRepository('keyring').resolve('secring.gpg').toFile())
        String description = "multi-project-grails-core-layout:${version}"
        MockNexus nexus = startNexus(description)

        GradleRunner runner = setupExample('multi-project-grails-core-layout')
        runner = setGradleProperty('projectVersion', version, runner)
        runner = withEnvironment(runner, nexus.environment + [
                GRAILS_PUBLISH_RELEASE: 'true',
                SIGNING_KEY           : key.keyId,
                SIGNING_KEYRING       : keyring.absolutePath,
                SIGNING_PASSPHRASE    : key.passphrase,
        ])

        when: 'the staging repository is initialized, then published into, then closed, in separate builds'
        BuildResult initialize = run(runner, 'initializeSonatypeStagingRepository')
        BuildResult publish = run(runner, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'publishToSonatype')
        BuildResult close = run(runner, '-x', 'initializeSonatypeStagingRepository', 'findSonatypeStagingRepository', 'closeSonatypeStagingRepository')

        then:
        initialize.task(':initializeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        publish.task(':findSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS
        ['acme-core', 'acme-bom', 'acme-starter'].every {
            publish.task(":${it}:signMavenPublication").outcome == TaskOutcome.SUCCESS &&
                    publish.task(":${it}:publishMavenPublicationToSonatypeRepository").outcome == TaskOutcome.SUCCESS
        }
        publish.task(':acme-gradle-plugin:signPluginMavenPublication').outcome == TaskOutcome.SUCCESS
        publish.task(':acme-gradle-plugin:signAcmePluginMarkerMavenPublication').outcome == TaskOutcome.SUCCESS
        publish.task(':acme-gradle-plugin:publishPluginMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        publish.task(':acme-gradle-plugin:publishAcmePluginMarkerMavenPublicationToSonatypeRepository').outcome == TaskOutcome.SUCCESS
        close.task(':closeSonatypeStagingRepository').outcome == TaskOutcome.SUCCESS

        and: 'one staging repository holds every module, signed'
        nexus.createdStagingRepositoryDescriptions == [description]
        nexus.stagingRepositoryState == 'closed'
        Map<String, byte[]> uploads = nexus.stagingUploads
        [
                'acme-core'         : ['.jar', '-sources.jar', '-javadoc.jar', '-test-fixtures.jar', '.pom', '.module'],
                'acme-bom'          : ['.pom', '.module'],
                'acme-starter'      : ['.jar', '.pom', '.module'],
                'acme-gradle-plugin': ['.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'],
        ].every { String name, List<String> suffixes ->
            suffixes.every { String suffix ->
                String path = "org/grails/example/${name}/${version}/${name}-${version}${suffix}"
                assert uploads.containsKey(path): "missing upload ${path}"
                key.verifies(uploads[path], uploads["${path}.asc" as String])
            }
        }
        String marker = "org/grails/example/acme/org.grails.example.acme.gradle.plugin/${version}/org.grails.example.acme.gradle.plugin-${version}.pom"
        key.verifies(uploads[marker], uploads["${marker}.asc" as String])
        !uploads.keySet().any { it.contains('acme-starter') && (it.endsWith('-sources.jar') || it.endsWith('-javadoc.jar')) }
        nexus.unmatchedRequests.isEmpty()
    }

    def "extended-plugin: a subclass of the plugin publishes its own extra artifact"() {
        given:
        Path repository = createTempRepository('maven-publish')
        GradleRunner runner = setupExample('extended-plugin')
        runner = setGradleProperty('mavenPublishUrl', repository.toString(), runner)

        when:
        BuildResult result = run(runner, 'publish')

        then:
        result.task(':generateProfile').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS

        and: 'the descriptor is published with the classifier the subclass chose, next to the usual artifacts'
        Path module = moduleDirectory(repository, 'extended-plugin', VERSION)
        ['.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module', '-profile.yml'].every { hasPublishedArtifact(module, 'extended-plugin', VERSION, it) }
        publishedFile(module, '-profile.yml').text.contains('name: acme')
        jarContains(publishedJar(module), 'org/grails/example/extended/Profile.class')
    }
}
