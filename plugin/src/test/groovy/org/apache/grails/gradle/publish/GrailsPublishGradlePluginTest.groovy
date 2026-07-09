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

import org.gradle.api.GradleException
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import javax.inject.Inject

class GrailsPublishGradlePluginTest extends Specification {

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
        def iae = thrown(IllegalArgumentException)
        iae.message == 'An additional publication named `cli` is already registered.'
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

    static abstract class ComponentFactoryHolder {

        @Inject
        abstract SoftwareComponentFactory getFactory()
    }
}
