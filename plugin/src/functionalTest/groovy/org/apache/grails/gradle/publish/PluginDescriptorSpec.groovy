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

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

import java.nio.file.Path

class PluginDescriptorSpec extends GradleSpecification {

    List<File> toCleanup = []

    def cleanup() {
        toCleanup.each { it.deleteDir() }
    }

    def "the descriptor of a Grails plugin project is published from a clean checkout, built by compileGroovy"() {
        given: 'a project applying the Grails plugin Gradle plugin, whose compiler writes META-INF/grails-plugin.xml'
        File repository = File.createTempDir('grails-plugin-descriptor')
        toCleanup << repository
        GradleRunner runner = setupTestResourceProject('other-artifacts', 'grails-plugin-descriptor')
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)
        runner = addEnvironmentVariable('GRAILS_PUBLISH_RELEASE', 'false', runner)

        when: 'publishing without having compiled anything before'
        BuildResult result = executeTask('publish', runner)

        then: 'the descriptor is compiled, then published with the plugin classifier'
        result.task(':compileGroovy').outcome == TaskOutcome.SUCCESS
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        Path module = repository.toPath().resolve('org/grails/example/grails-plugin-descriptor/0.0.1-SNAPSHOT')
        File descriptor = module.toFile().listFiles().find { it.name.endsWith('-plugin.xml') }
        descriptor != null
        descriptor.text.contains("<plugin name='descriptorPlugin' version='0.0.1-SNAPSHOT'")
    }

    def "a project applying the Grails plugin Gradle plugin without a plugin class publishes no descriptor"() {
        given: 'the same project with its *GrailsPlugin class renamed, so the compiler writes no descriptor'
        File repository = File.createTempDir('grails-plugin-descriptor')
        toCleanup << repository
        GradleRunner runner = setupTestResourceProject('other-artifacts', 'grails-plugin-descriptor')
        File pluginClass = new File(runner.projectDir, 'src/main/groovy/org/grails/example/DescriptorGrailsPlugin.groovy')
        File library = new File(pluginClass.parentFile, 'DescriptorLibrary.groovy')
        library.text = pluginClass.text.replace('class DescriptorGrailsPlugin', 'class DescriptorLibrary')
        assert pluginClass.delete()
        runner = setGradleProperty('mavenPublishUrl', repository.absolutePath, runner)
        runner = addEnvironmentVariable('GRAILS_PUBLISH_RELEASE', 'false', runner)

        when:
        BuildResult result = executeTask('publish', runner)

        then: 'the publication has no descriptor artifact and publishes normally'
        result.task(':publishMavenPublicationToMavenRepository').outcome == TaskOutcome.SUCCESS
        Path module = repository.toPath().resolve('org/grails/example/grails-plugin-descriptor/0.0.1-SNAPSHOT')
        !module.toFile().listFiles().any { it.name.endsWith('-plugin.xml') }
        module.toFile().listFiles().any { it.name.endsWith('.jar') }
    }
}
