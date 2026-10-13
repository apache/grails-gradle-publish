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
import org.gradle.testkit.runner.UnexpectedBuildFailure

import java.nio.file.Files
import java.nio.file.Path

class AdditionalPublicationSpec extends GradleSpecification {

    List<File> toCleanup = []

    def cleanup() {
        for (File file : toCleanup) {
            try {
                file.deleteDir()
            }
            catch (ignored) {
            }
        }
    }

    def "additional publication publishes a companion artifact with its own coordinate and dependency graph"() {
        given:
        File tempDir = File.createTempDir("additional-publication")
        toCleanup << tempDir

        and:
        GradleRunner runner = setupTestResourceProject('other-artifacts', 'additional-publication')

        runner = setGradleProperty("projectVersion", "0.0.1-SNAPSHOT", runner)
        runner = setGradleProperty("mavenPublishUrl", tempDir.toPath().toAbsolutePath().toString(), runner)
        runner = addEnvironmentVariable("GRAILS_PUBLISH_RELEASE", "false", runner)

        when:
        def result = executeTask("publish", ["--info"], runner)

        then: "the docs jars of both publications are built"
        assertTaskSuccess("sourcesJar", result)
        assertTaskSuccess("javadocJar", result)
        assertTaskSuccess("cliSourcesJar", result)
        assertTaskSuccess("cliGroovydoc", result)
        assertTaskSuccess("cliJavadocJar", result)

        and: "the primary artifact publishes under the project coordinate"
        Path mainArtifactDir = tempDir.toPath().resolve("org/grails/example/additional-publication/0.0.1-SNAPSHOT")
        Files.exists(mainArtifactDir)
        File[] mainArtifacts = mainArtifactDir.toFile().listFiles()

        File mainPomFile = mainArtifacts.find { it.name.endsWith(".pom") }
        mainPomFile

        and: "the primary pom has no trace of the cli tier"
        String mainPom = mainPomFile.text
        !mainPom.contains("commons-lang3")
        !mainPom.contains("additional-publication-cli")

        and: "the primary module metadata carries no cli dependencies; the cli variants are only available-at redirects to the cli coordinate"
        File mainModuleFile = mainArtifacts.find { it.name.endsWith(".module") }
        mainModuleFile
        String mainModule = mainModuleFile.text
        !mainModule.contains("commons-lang3")
        mainModule.contains('"name": "cliApiElements"')
        mainModule.contains('"available-at"')
        mainModule.contains('"module": "additional-publication-cli"')

        and: "the primary sources jar contains only the non-cli sources"
        File mainSourcesJar = mainArtifacts.find { it.name.endsWith("-sources.jar") }
        mainSourcesJar
        findJarFileEntry("org/grails/example/MyProject.groovy", mainSourcesJar)
        !findJarFileEntry("org/grails/example/cli/MyCommand.groovy", mainSourcesJar)

        and: "the primary classes jar contains only the non-cli classes"
        File mainClassesJar = mainArtifacts.find {
            it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar")
        }
        mainClassesJar
        findJarFileEntry("org/grails/example/MyProject.class", mainClassesJar)
        !findJarFileEntry("org/grails/example/cli/MyCommand.class", mainClassesJar)

        and: "the companion artifact publishes under its own -cli coordinate"
        Path cliArtifactDir = tempDir.toPath().resolve("org/grails/example/additional-publication-cli/0.0.1-SNAPSHOT")
        Files.exists(cliArtifactDir)
        File[] cliArtifacts = cliArtifactDir.toFile().listFiles()

        File cliClassesJar = cliArtifacts.find {
            it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar")
        }
        cliClassesJar
        findJarFileEntry("org/grails/example/cli/MyCommand.class", cliClassesJar)
        !findJarFileEntry("org/grails/example/MyProject.class", cliClassesJar)

        and: "the companion module metadata has the cli variants, but no class or resource directory variants without files"
        String cliModule = cliArtifacts.find { it.name.endsWith(".module") }.text
        cliModule.contains('"name": "cliApiElements"')
        cliModule.contains('"name": "cliRuntimeElements"')
        !cliModule.contains('ElementsClasses')
        !cliModule.contains('ElementsResources')

        and: "the companion pom carries the cli dependency graph, including the primary coordinate"
        File cliPomFile = cliArtifacts.find { it.name.endsWith(".pom") }
        cliPomFile
        String cliPom = cliPomFile.text.replaceAll('\\n', '').replaceAll('\\s+', ' ')
        cliPom.contains("<artifactId>additional-publication</artifactId>")
        cliPom.contains("<artifactId>commons-lang3</artifactId>")
        cliPom.contains("<description>The cli companion artifact of the example project</description>")

        and: "every companion pom dependency has a resolved version"
        !cliPom.contains("<version></version>")
        cliPom.contains("<version>3.17.0</version>")

        and: "the companion sources and javadoc jars contain only the cli tier"
        File cliSourcesJar = cliArtifacts.find { it.name.endsWith("-sources.jar") }
        cliSourcesJar
        findJarFileEntry("org/grails/example/cli/MyCommand.groovy", cliSourcesJar)
        !findJarFileEntry("org/grails/example/MyProject.groovy", cliSourcesJar)

        File cliJavadocJar = cliArtifacts.find { it.name.endsWith("-javadoc.jar") }
        cliJavadocJar
        findJarFileEntry("org/grails/example/cli/MyCommand.html", cliJavadocJar)
    }

    def "additional publication fails when its component contains class or resource directories"() {
        given:
        GradleRunner runner = setupTestResourceProject('other-artifacts', 'additional-publication')
        runner = setGradleProperty("projectVersion", "0.0.1-SNAPSHOT", runner)

        and: 'the component keeps the directory variants'
        File buildFile = new File(runner.projectDir, 'build.gradle')
        buildFile.text = buildFile.text.replace('if (isDirectoryVariant(it)) {', 'if (false) {')

        when:
        executeTask("assemble", runner)

        then:
        UnexpectedBuildFailure bf = thrown(UnexpectedBuildFailure)
        bf.buildResult.output.contains("Publication `cli` of root project 'additional-publication' contains the directory `build/classes/")
        bf.buildResult.output.contains('skip the variants whose artifact type is one of java-classes-directory, java-resources-directory')
    }

    def "additional publication components can still be changed after the plugin configured the publications"() {
        given:
        File tempDir = File.createTempDir("additional-publication")
        toCleanup << tempDir

        and:
        GradleRunner runner = setupTestResourceProject('other-artifacts', 'additional-publication')
        runner = setGradleProperty("projectVersion", "0.0.1-SNAPSHOT", runner)
        runner = setGradleProperty("mavenPublishUrl", tempDir.toPath().toAbsolutePath().toString(), runner)
        runner = addEnvironmentVariable("GRAILS_PUBLISH_RELEASE", "false", runner)

        and: 'the build changes the component in an afterEvaluate block that runs after the plugin'
        File buildFile = new File(runner.projectDir, 'build.gradle')
        buildFile.text += """
afterEvaluate {
    components.cli.withVariantsFromConfiguration(configurations.cliRuntimeElements) {}
}
"""

        when:
        def result = executeTask("publish", runner)

        then:
        assertTaskSuccess("publish", result)
    }
}
