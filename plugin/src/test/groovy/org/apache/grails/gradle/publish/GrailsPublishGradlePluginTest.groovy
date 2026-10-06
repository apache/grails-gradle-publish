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

import groovy.namespace.QName
import groovy.xml.XmlParser
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import javax.inject.Inject

class GrailsPublishGradlePluginTest extends Specification {

    def 'findProjectProperty reads the project itself before its parent projects'() {
        given:
        def root = ProjectBuilder.builder().withName('root').build()
        def child = ProjectBuilder.builder().withName('child').withParent(root).build()
        root.extensions.extraProperties.set('githubSlug', 'from/root')
        root.extensions.extraProperties.set('onlyOnRoot', 'from/root')
        child.extensions.extraProperties.set('githubSlug', 'from/child')
        child.version = '1.0.0-SNAPSHOT'
        child.plugins.apply('groovy')
        child.plugins.apply(GrailsPublishGradlePlugin)
        GrailsPublishGradlePlugin plugin = child.plugins.getPlugin(GrailsPublishGradlePlugin)

        expect: 'the project itself wins; a parent is read explicitly (Gradle 10 removes the implicit lookup) when Isolated Projects is off'
        plugin.findProjectProperty(child, 'githubSlug') == 'from/child'
        plugin.findProjectProperty(child, 'onlyOnRoot') == 'from/root'
        plugin.findProjectProperty(child, 'notSetAnywhere') == null
    }

    def 'a plugin extending this one can declare its own injected constructor'() {
        given: 'a subclass shaped like the grails-core profile publish plugin'
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0-SNAPSHOT'
        project.plugins.apply('groovy')

        when:
        project.plugins.apply(ExtendingPublishPlugin)

        then: 'the injected build features are available to the base class'
        project.extensions.findByType(GrailsPublishExtension) != null
    }

    def 'requires java or java platform plugin'() {
        given:
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0'

        when:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        ge.cause.message == 'Grails Publish Plugin requires the Java Platform or Java Plugin to be applied to the project.'
    }

    def 'apply only: plugin registers release task for release version'() {
        given:
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0'

        when:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')

        then:
        project.tasks.names.toList() == [
                'assemble',
                'build',
                'check',
                'clean',
                'closeAndReleaseSonatypeStagingRepository',
                'closeAndReleaseStagingRepositories',
                'closeSonatypeStagingRepository',
                'closeStagingRepositories',
                'findSonatypeStagingRepository',
                'initializeSonatypeStagingRepository',
                'publish',
                'publishToMavenLocal',
                'releaseSonatypeStagingRepository',
                'releaseStagingRepositories',
                'retrieveSonatypeStagingProfile',
        ]
    }

    def 'evaluate: plugin registers release task for release version'() {
        given:
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('java')

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.title.set('Grails Gradle Publish Plugin')
        gpe.desc.set('A plugin to assist in publishing Grails artifacts')
        gpe.developers = ['jdaugherty': 'James Daugherty']

        when:
        ((ProjectInternal) project).evaluate()

        then:
        project.tasks.names.toList() == [
                'artifactTransforms',
                'assemble',
                'build',
                'buildDependents',
                'buildEnvironment',
                'buildNeeded',
                'check',
                'classes',
                'clean',
                'closeAndReleaseSonatypeStagingRepository',
                'closeAndReleaseStagingRepositories',
                'closeSonatypeStagingRepository',
                'closeStagingRepositories',
                'compileJava',
                'compileTestJava',
                'dependencies',
                'dependencyInsight',
                'findSonatypeStagingRepository',
                'generateMetadataFileForMavenPublication',
                'generatePomFileForMavenPublication',
                'grailsPublishValidation',
                'help',
                'init',
                'initializeSonatypeStagingRepository',
                'install',
                'jar',
                'javaToolchains',
                'javadoc',
                'javadocJar',
                'outgoingVariants',
                'processResources',
                'processTestResources',
                'projects',
                'properties',
                'publish',
                'publishAllPublicationsToSonatypeRepository',
                'publishMavenPublicationToMavenLocal',
                'publishMavenPublicationToSonatypeRepository',
                'publishToMavenLocal',
                'publishToSonatype',
                'releaseSonatypeStagingRepository',
                'releaseStagingRepositories',
                'resolvableConfigurations',
                'retrieveSonatypeStagingProfile',
                'signMavenPublication',
                'sourcesJar',
                'tasks',
                'test',
                'testClasses',
                'testSourcesJar',
                'updateDaemonJvm',
                'wrapper'
        ]
    }

    def 'apply only:  plugin registers release task for snapshot version'() {
        given:
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0-SNAPSHOT'

        when:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')

        then:
        project.tasks.names.toList() == ['publish', 'publishToMavenLocal']
    }

    def 'evaluate:  plugin registers release task for snapshot version'() {
        given:
        def project = ProjectBuilder.builder().build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('java')

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.title.set('Grails Gradle Publish Plugin')
        gpe.desc.set('A plugin to assist in publishing Grails artifacts')
        gpe.developers = ['jdaugherty': 'James Daugherty']

        when:
        ((ProjectInternal) project).evaluate()

        then:
        project.tasks.names.toList() == [
                'artifactTransforms',
                'assemble',
                'build',
                'buildDependents',
                'buildEnvironment',
                'buildNeeded',
                'check',
                'classes',
                'clean',
                'compileJava',
                'compileTestJava',
                'dependencies',
                'dependencyInsight',
                'generateMetadataFileForMavenPublication',
                'generatePomFileForMavenPublication',
                'grailsPublishValidation',
                'help',
                'init',
                'install',
                'jar',
                'javaToolchains',
                'javadoc',
                'javadocJar',
                'outgoingVariants',
                'processResources',
                'processTestResources',
                'projects',
                'properties',
                'publish',
                'publishAllPublicationsToMavenRepository',
                'publishMavenPublicationToMavenLocal',
                'publishMavenPublicationToMavenRepository',
                'publishToMavenLocal',
                'resolvableConfigurations',
                'sourcesJar',
                'tasks',
                'test',
                'testClasses',
                'testSourcesJar',
                'updateDaemonJvm',
                'wrapper'
        ]
    }

    def 'publishing without a license fails'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('java')

        and: 'developers are configured, but no license'
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.developers = ['jdaugherty': 'James Daugherty']

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        causeChainContains(ge, "No 'license' was specified")
    }

    def 'publishing without developers fails'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('java')

        and: 'a license is configured, but the developer list is empty'
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        causeChainContains(ge, "No 'developers' was specified")
    }

    def 'additional publication registers a second publication with its own artifactId and docs jar tasks'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('groovy')
        project.sourceSets.create('cli')

        and: 'a cli software component exists'
        def componentFactoryHolder = project.objects.newInstance(ComponentFactoryHolder)
        project.components.add(componentFactoryHolder.factory.adhoc('cli'))

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.title.set('Grails Gradle Publish Plugin')
        gpe.desc.set('A plugin to assist in publishing Grails artifacts')
        gpe.developers = ['jdaugherty': 'James Daugherty']
        gpe.additionalPublication('cli') {
        }

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def publishing = project.extensions.getByType(PublishingExtension)
        publishing.publications.names.toSet() == ['maven', 'cli'] as Set

        and: 'the artifactId defaults to the project name with the publication name appended'
        (publishing.publications.getByName('cli') as MavenPublication).artifactId == 'test-project-cli'
        (publishing.publications.getByName('maven') as MavenPublication).artifactId == 'test-project'

        and: 'the sources, groovydoc, and javadoc jar tasks exist for the cli source set'
        project.tasks.names.containsAll(['cliSourcesJar', 'cliGroovydoc', 'cliJavadocJar'])
    }

    def 'additional publication requires its software component to exist'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('groovy')
        project.sourceSets.create('cli')

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.developers = ['jdaugherty': 'James Daugherty']
        gpe.additionalPublication('cli') {
        }

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        causeChainContains(ge, 'requires a software component named `cli`')
    }

    def 'additional publication requires its source set to exist'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('groovy')

        and: 'a cli software component exists, but no cli source set'
        def componentFactoryHolder = project.objects.newInstance(ComponentFactoryHolder)
        project.components.add(componentFactoryHolder.factory.adhoc('cli'))

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.developers = ['jdaugherty': 'James Daugherty']
        gpe.additionalPublication('cli') {
        }

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        causeChainContains(ge, 'requires source set `cli`')
    }

    def 'additional publication name must not conflict with the primary publication'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        project.plugins.apply('groovy')

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.developers = ['jdaugherty': 'James Daugherty']
        gpe.additionalPublication('maven') {
        }

        when:
        ((ProjectInternal) project).evaluate()

        then:
        def ge = thrown(GradleException)
        causeChainContains(ge, 'conflicts with the primary publication name')
    }

    def 'additional publication names must be unique'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'

        and:
        project.plugins.apply('org.apache.grails.gradle.grails-publish')

        and:
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.additionalPublication('cli') {
        }

        when:
        gpe.additionalPublication('cli') {
        }

        then:
        def iude = thrown(InvalidUserDataException)
        iude.message == 'An additional publication named `cli` is already registered.'
    }

    def 'resolved versions are only resolved when transitive dependencies are enabled'() {
        given: 'a project depending on a sibling project'
        def root = ProjectBuilder.builder().withName('root').build()
        def lib = ProjectBuilder.builder().withName('lib').withParent(root).build()
        lib.plugins.apply('java-library')
        lib.group = 'org.example'
        lib.version = '1.2.3'
        def app = ProjectBuilder.builder().withName('app').withParent(root).build()
        app.plugins.apply('java-library')
        app.dependencies.add('implementation', app.dependencies.project(path: ':lib'))

        and:
        Property<Boolean> enabled = app.objects.property(Boolean).value(false)
        Provider<Map<String, String>> versions = GrailsPublishGradlePlugin.resolvedVersionsProvider(app, enabled,
                ['runtimeClasspath', 'notAConfiguration'])

        expect: 'nothing is resolved while disabled'
        versions.get() == [:]
        app.configurations.getByName('runtimeClasspath').state == Configuration.State.UNRESOLVED

        when:
        enabled.set(true)

        then: 'the versions of the resolved components, skipping configurations that do not exist'
        versions.get() == ['org.example:lib': '1.2.3']
    }

    def 'dependencies without a version in the pom get the resolved version'() {
        given:
        Node pom = new XmlParser().parseText('''\
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>managed</artifactId></dependency>
                <dependency><groupId>org.example</groupId><artifactId>empty</artifactId><version> </version></dependency>
                <dependency><groupId>org.example</groupId><artifactId>pinned</artifactId><version>9.9.9</version></dependency>
              </dependencies>
            </project>'''.stripIndent())
        Map<String, String> versions = ['org.example:managed': '1.0.0', 'org.example:empty': '2.0.0', 'org.example:pinned': '3.0.0']

        when:
        GrailsPublishGradlePlugin.setDependencyVersions(pom, ProjectBuilder.builder().build().providers.provider { versions })

        then:
        pom.'**'.findAll { it instanceof Node && localName(it as Node) == 'version' }*.text() == ['1.0.0', '2.0.0', '9.9.9']
    }

    def 'a dependency without a version that was not resolved fails the pom'() {
        given:
        Node pom = new XmlParser().parseText('''\
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>unknown</artifactId></dependency>
              </dependencies>
            </project>'''.stripIndent())

        when:
        GrailsPublishGradlePlugin.setDependencyVersions(pom, ProjectBuilder.builder().build().providers.provider { [:] })

        then:
        def iude = thrown(InvalidUserDataException)
        iude.message == 'No version found for dependency org.example:unknown.'
    }

    def 'a dependency that cannot be resolved fails the resolved versions instead of being left out'() {
        given: 'a project depending on a module no repository has'
        def root = ProjectBuilder.builder().withName('root').build()
        def app = ProjectBuilder.builder().withName('app').withParent(root).build()
        app.plugins.apply('java-library')
        app.dependencies.add('implementation', 'org.example:missing:1.0')

        and:
        Provider<Map<String, String>> versions = GrailsPublishGradlePlugin.resolvedVersionsProvider(app,
                app.objects.property(Boolean).value(true), ['runtimeClasspath'])

        when:
        versions.get()

        then: 'the failure names the dependency and keeps the resolution failure as its cause'
        def ge = thrown(GradleException)
        ge.message.startsWith("Could not resolve the versions to publish from configuration ':app:runtimeClasspath':")
        ge.message.contains('org.example:missing:1.0')
        ge.cause != null
    }

    def 'the configurations are resolved once, however often the resolved versions are queried'() {
        given:
        def root = ProjectBuilder.builder().withName('root').build()
        def lib = ProjectBuilder.builder().withName('lib').withParent(root).build()
        lib.plugins.apply('java-library')
        lib.group = 'org.example'
        lib.version = '1.2.3'
        def app = ProjectBuilder.builder().withName('app').withParent(root).build()
        app.plugins.apply('java-library')
        app.dependencies.add('implementation', app.dependencies.project(path: ':lib'))

        when:
        Provider<Map<String, String>> versions = GrailsPublishGradlePlugin.resolvedVersionsProvider(app,
                app.objects.property(Boolean).value(true), ['runtimeClasspath'])
        Map<String, String> first = versions.get()

        then: 'the second query returns the versions of the first'
        versions.get().is(first)
        first == ['org.example:lib': '1.2.3']
    }

    def 'the resolved versions are not queried when every pom dependency has a version'() {
        given:
        Node pom = new XmlParser().parseText('''\
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>pinned</artifactId><version>9.9.9</version></dependency>
              </dependencies>
            </project>'''.stripIndent())

        when:
        GrailsPublishGradlePlugin.setDependencyVersions(pom, ProjectBuilder.builder().build().providers.provider {
            throw new IllegalStateException('queried')
        })

        then:
        noExceptionThrown()
    }

    def 'module metadata dependencies without a version get the resolved version and keep their other constraints'() {
        given:
        Map module = [variants: [[name: 'testFixturesApiElements', dependencies: [
                [group: 'org.example', module: 'managed'],
                [group: 'org.example', module: 'rejecting', version: [rejects: ['1.0.0']]],
                [group: 'org.example', module: 'pinned', version: [requires: '9.9.9']],
        ]]]]
        Map<String, String> versions = ['org.example:managed': '1.1.0', 'org.example:rejecting': '1.2.0', 'org.example:pinned': '3.0.0']

        when:
        boolean changed = GrailsPublishGradlePlugin.setModuleDependencyVersions(module, 'maven',
                ProjectBuilder.builder().build().providers.provider { versions })

        then:
        changed
        (module.variants[0].dependencies as List<Map>)*.version == [
                [requires: '1.1.0'],
                [requires: '1.2.0', rejects: ['1.0.0']],
                [requires: '9.9.9'],
        ]
    }

    def 'module metadata versions are not queried when every dependency has a version'() {
        given:
        Map module = [variants: [[name: 'runtimeElements', dependencies: [
                [group: 'org.example', module: 'pinned', version: [requires: '9.9.9']],
                [group: 'org.example', module: 'strict', version: [strictly: '1.0.0']],
        ]]]]

        when:
        boolean changed = GrailsPublishGradlePlugin.setModuleDependencyVersions(module, 'maven',
                ProjectBuilder.builder().build().providers.provider { throw new IllegalStateException('queried') })

        then:
        !changed
    }

    def 'an empty resolved version fails the module metadata, as it fails the pom'() {
        given:
        Map module = [variants: [[name: 'runtimeElements', dependencies: [[group: 'org.example', module: 'sibling']]]]]
        Node pom = new XmlParser().parseText('''\
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>sibling</artifactId></dependency>
              </dependencies>
            </project>'''.stripIndent())
        Provider<Map<String, String>> versions = ProjectBuilder.builder().build().providers.provider { ['org.example:sibling': ''] }

        when:
        GrailsPublishGradlePlugin.setModuleDependencyVersions(module, 'maven', versions)

        then:
        def moduleFailure = thrown(InvalidUserDataException)
        moduleFailure.message == 'No version found for dependency org.example:sibling of variant runtimeElements in the module metadata of publication maven.'

        when:
        GrailsPublishGradlePlugin.setDependencyVersions(pom, versions)

        then:
        def pomFailure = thrown(InvalidUserDataException)
        pomFailure.message == 'No version found for dependency org.example:sibling.'
    }

    def 'a plugin overriding the deprecated setDependencyVersions hook still has it called when the pom is generated'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'
        project.extensions.extraProperties.set('mavenPublishUrl', File.createTempDir().toURI().toString())
        project.plugins.apply('java')
        project.plugins.apply(OverridingPublishPlugin)
        configurePublishing(project)
        ((ProjectInternal) project).evaluate()

        when:
        GenerateMavenPom pomTask = project.tasks.getByName('generatePomFileForMavenPublication') as GenerateMavenPom
        pomTask.actions.each { it.execute(pomTask) }

        then: 'the override ran, with the configurations the pom versions are resolved from'
        pomTask.destination.text.contains('<inceptionYear>from-override</inceptionYear>')
        OverridingPublishPlugin.configurationNames == ['compileClasspath', 'runtimeClasspath',
                                                       'testFixturesCompileClasspath', 'testFixturesRuntimeClasspath']
    }

    def 'the publish tasks of publications with the same coordinates run one after the other'() {
        given:
        def project = ProjectBuilder.builder().withName('test-project').build()
        project.version = '1.0.0-SNAPSHOT'
        project.group = 'org.example'
        project.extensions.extraProperties.set('mavenPublishUrl', File.createTempDir().toURI().toString())
        project.plugins.apply('java')
        project.plugins.apply('org.apache.grails.gradle.grails-publish')
        configurePublishing(project)
        ((ProjectInternal) project).evaluate()

        and: 'a publication with the coordinates of the primary one, and one with other coordinates'
        def publications = project.extensions.getByType(PublishingExtension).publications
        publications.create('shared', MavenPublication) { MavenPublication publication ->
            publication.groupId = 'org.example'
            publication.artifactId = 'test-project'
        }
        publications.create('separate', MavenPublication) { MavenPublication publication ->
            publication.groupId = 'org.example'
            publication.artifactId = 'other-artifact'
        }

        expect: 'the publication sharing the coordinates publishes after the primary one, to each target'
        mustRunAfter(project, 'publishSharedPublicationToMavenRepository') == ['publishMavenPublicationToMavenRepository'] as Set
        mustRunAfter(project, 'publishSharedPublicationToMavenLocal') == ['publishMavenPublicationToMavenLocal'] as Set

        and: 'the others are not ordered'
        mustRunAfter(project, 'publishMavenPublicationToMavenRepository').isEmpty()
        mustRunAfter(project, 'publishSeparatePublicationToMavenRepository').isEmpty()
    }

    def 'the Nexus staging repository transition tasks are marked as not compatible, also when the root project applied the Nexus plugin'() {
        given: 'a root project applying and configuring the Nexus plugin itself'
        def root = ProjectBuilder.builder().withName('root').build()
        root.pluginManager.apply(NexusPublishPlugin)
        root.extensions.getByType(NexusPublishExtension).repositories { it.sonatype() }

        and: 'two subprojects releasing through Nexus'
        ['a', 'b'].each { String name ->
            def subproject = ProjectBuilder.builder().withName(name).withParent(root).build()
            subproject.version = '1.0.0'
            subproject.plugins.apply('java')
            subproject.plugins.apply(GrailsPublishGradlePlugin)
        }

        expect:
        ['closeSonatypeStagingRepository', 'releaseSonatypeStagingRepository'].every { String taskName ->
            (root.tasks.getByName(taskName) as TaskInternal).reasonTaskIsIncompatibleWithConfigurationCache.orElse(null) ==
                    'The staging repository transition tasks of the Nexus publish plugin reference the project'
        }
    }

    private static Set<String> mustRunAfter(Project project, String taskName) {
        def task = project.tasks.getByName(taskName)
        task.mustRunAfter.getDependencies(task)*.name as Set<String>
    }

    private static void configurePublishing(Project project) {
        GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
        gpe.githubSlug.set('apache/grails-gradle-publish')
        gpe.license {
            name = 'Apache-2.0'
        }
        gpe.title.set('Grails Gradle Publish Plugin')
        gpe.desc.set('A plugin to assist in publishing Grails artifacts')
        gpe.developers = ['jdaugherty': 'James Daugherty']
    }

    /** The name of a pom node, which has a namespace unless the plugin appended it */
    private static String localName(Node node) {
        node.name() instanceof QName ? ((QName) node.name()).localPart : node.name().toString()
    }

    private static boolean causeChainContains(Throwable throwable, String expected) {
        Throwable current = throwable
        while (current != null) {
            if (current.message?.contains(expected)) {
                return true
            }
            current = current.cause
        }
        false
    }

    static class OverridingPublishPlugin extends GrailsPublishGradlePlugin {

        static List<String> configurationNames

        @Override
        protected void setDependencyVersions(Node pomNode, Project project, List<String> configurationNames) {
            OverridingPublishPlugin.configurationNames = configurationNames
            pomNode.appendNode('inceptionYear', 'from-override')
        }
    }

    static class ExtendingPublishPlugin extends GrailsPublishGradlePlugin {

        final ObjectFactory objectFactory

        @Inject
        ExtendingPublishPlugin(ObjectFactory objectFactory) {
            this.objectFactory = objectFactory
        }
    }

    static abstract class ComponentFactoryHolder {

        @Inject
        abstract SoftwareComponentFactory getFactory()
    }
}
