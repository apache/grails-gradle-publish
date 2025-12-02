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
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

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
                'components',
                'dependencies',
                'dependencyInsight',
                'dependentComponents',
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
                'model',
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
                'components',
                'dependencies',
                'dependencyInsight',
                'dependentComponents',
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
                'model',
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
}
