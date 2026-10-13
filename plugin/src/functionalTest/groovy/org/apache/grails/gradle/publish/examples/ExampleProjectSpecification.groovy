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

import org.apache.commons.io.FileUtils
import org.apache.grails.gradle.publish.GradleSpecification
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.regex.Pattern

/**
 * Base class for the specifications that run the example projects under {@code examples/} at the root of this
 * repository. Each example is copied to a scratch directory, and an init script points its {@code pluginManagement}
 * at the freshly built plugin, so the examples stay real, self-contained projects that resolve the plugin from
 * {@code mavenLocal()} when run by hand.
 */
abstract class ExampleProjectSpecification extends GradleSpecification {

    /** The coordinates every example publishes under */
    static final String EXAMPLE_GROUP_PATH = 'org/grails/example'

    static final Path EXAMPLES_DIR = Path.of(Objects.requireNonNull(System.getProperty('examplesDir'), 'examplesDir system property'))

    List<File> toCleanup = []

    /** JVM system property arguments added to every build of the feature, e.g. the trust store of a {@link MockNexus} */
    List<String> systemPropertyArguments = []

    private Path initScript
    private final List<MockNexus> nexusServers = []

    void cleanup() {
        for (MockNexus nexus : nexusServers) {
            nexus.close()
        }
        for (File file : toCleanup) {
            FileUtils.deleteQuietly(file)
        }
    }

    /**
     * Starts a Nexus endpoint (over TLS) that the builds of this feature trust, stopped after the feature.
     */
    protected MockNexus startNexus(String repositoryDescription) {
        MockNexus nexus = new MockNexus(repositoryDescription).start()
        nexusServers << nexus
        systemPropertyArguments.addAll(nexus.trustArguments)
        nexus
    }

    /**
     * Copies the named example (without any leftover build output) and returns a runner rooted at it.
     */
    protected GradleRunner setupExample(String exampleName) {
        Path source = EXAMPLES_DIR.resolve(exampleName)
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("No example named `$exampleName` exists under $EXAMPLES_DIR")
        }
        Path destination = createProjectDir(exampleName)
        FileUtils.copyDirectory(source.toFile(), destination.toFile(), { File file ->
            !(file.directory && file.name in ['build', '.gradle'])
        } as FileFilter)

        initScript = createProjectDir('init-scripts').resolve('local-plugin.init.gradle')
        initScript.toFile().text = initScriptText(pluginRepository.toUri().toString())

        setupProject(destination)
    }

    /** The repository the plugin under test was published to by this build */
    protected static Path getPluginRepository() {
        Path.of(Objects.requireNonNull(System.getProperty('localMavenPath'), 'localMavenPath system property'))
    }

    /** An init script that resolves the plugin under test from the given repository */
    protected static String initScriptText(String pluginRepositoryUri) {
        """\
        // Resolves the plugin under test from the repository the build publishes it to
        beforeSettings { settings ->
            settings.pluginManagement.repositories {
                maven {
                    name = 'PluginUnderTest'
                    url = uri('${pluginRepositoryUri}')
                }
            }
        }
        """.stripIndent()
    }

    /** Runs the tasks and expects the build to succeed */
    protected BuildResult run(GradleRunner runner, List<String> arguments) {
        runner.withArguments(commonArguments(arguments)).forwardOutput().build()
    }

    protected BuildResult run(GradleRunner runner, String... arguments) {
        run(runner, arguments.toList())
    }

    /** Runs the tasks and expects the build to fail; the failed result is returned for inspection */
    protected BuildResult runAndFail(GradleRunner runner, String... arguments) {
        try {
            BuildResult result = runner.withArguments(commonArguments(arguments.toList())).forwardOutput().build()
            throw new AssertionError("Expected the build to fail but it succeeded:\n${result.output}" as Object)
        } catch (UnexpectedBuildFailure failure) {
            return failure.buildResult
        }
    }

    private List<String> commonArguments(List<String> arguments) {
        // fail on deprecation warnings, so that anything removed in the next major Gradle version is caught early
        List<String> all = ['--stacktrace', '--warning-mode=fail', '--init-script', initScript.toAbsolutePath().toString()]
        all.addAll(systemPropertyArguments)
        all.addAll(arguments)
        all
    }

    /** Adds environment variables to the runner (on top of the ones the base class sets up) */
    protected GradleRunner withEnvironment(GradleRunner runner, Map<String, String> variables) {
        GradleRunner current = runner
        variables.each { String name, String value ->
            current = addEnvironmentVariable(name, value, current)
        }
        current
    }

    /** A scratch directory (deleted after the feature) usable as a file based Maven repository */
    protected Path createTempRepository(String prefix) {
        File directory = File.createTempDir(prefix)
        toCleanup << directory
        directory.toPath().toAbsolutePath()
    }

    /** The Maven directory of a published module, e.g. {@code repo/org/grails/example/simple-library/1.0.0-SNAPSHOT} */
    protected static Path moduleDirectory(Path repository, String artifactId, String version, String groupPath = EXAMPLE_GROUP_PATH) {
        repository.resolve(groupPath).resolve(artifactId).resolve(version)
    }

    /** The names of the published files of a module, without checksum sidecars */
    protected static List<String> publishedFileNames(Path moduleDirectory) {
        assert Files.isDirectory(moduleDirectory): "No module was published at $moduleDirectory"
        moduleDirectory.toFile().listFiles()
                .findAll { !(it.name ==~ /.*\.(md5|sha1|sha256|sha512)$/) }
                *.name
                .sort()
    }

    /** Finds the single published file whose name ends with the suffix */
    protected static File publishedFile(Path moduleDirectory, String suffix) {
        List<File> matches = moduleDirectory.toFile().listFiles().findAll { it.name.endsWith(suffix) }
        assert matches.size() == 1: "Expected exactly one file ending in `$suffix` in $moduleDirectory but found ${matches*.name}"
        matches.first()
    }

    /** The primary (unclassified) jar of a module */
    protected static File publishedJar(Path moduleDirectory) {
        List<File> matches = moduleDirectory.toFile().listFiles().findAll {
            it.name.endsWith('.jar') && !(it.name ==~ /.*-(sources|javadoc|tests|test-fixtures|cli|plugin)\.jar$/)
        }
        assert matches.size() == 1: "Expected exactly one primary jar in $moduleDirectory but found ${matches*.name}"
        matches.first()
    }

    /**
     * The published file of an artifact, accounting for the timestamped file names of snapshots in remote
     * repositories, e.g. {@code core-1.0.0-20260101.120000-1.jar} for version {@code 1.0.0-SNAPSHOT}.
     */
    protected static File publishedArtifact(Path moduleDirectory, String artifactId, String version, String suffix) {
        List<File> matches = moduleDirectory.toFile().listFiles().findAll { it.name ==~ artifactPattern(artifactId, version, suffix) }
        assert matches.size() == 1: "Expected exactly one `${artifactId}-${version}${suffix}` in $moduleDirectory but found ${matches*.name}"
        matches.first()
    }

    /** Whether the artifact was published (possibly more than once, as timestamped snapshots) */
    protected static boolean hasPublishedArtifact(Path moduleDirectory, String artifactId, String version, String suffix) {
        Files.isDirectory(moduleDirectory) && moduleDirectory.toFile().listFiles().any { it.name ==~ artifactPattern(artifactId, version, suffix) }
    }

    /** Whether the uploaded paths (relative to a repository root) contain the artifact */
    protected static boolean hasUploadedArtifact(Collection<String> paths, String artifactId, String version, String suffix, String groupPath = EXAMPLE_GROUP_PATH) {
        Pattern pattern = artifactPattern(artifactId, version, suffix)
        paths.count { it.startsWith("${groupPath}/${artifactId}/${version}/") && it.substring(it.lastIndexOf('/') + 1) ==~ pattern } == 1
    }

    protected static Pattern artifactPattern(String artifactId, String version, String suffix) {
        String versionPattern = version.endsWith('-SNAPSHOT') ?
                Pattern.quote(version - 'SNAPSHOT') + /(SNAPSHOT|\d{8}\.\d{6}-\d+)/ :
                Pattern.quote(version)
        Pattern.compile('^' + Pattern.quote(artifactId + '-') + versionPattern + Pattern.quote(suffix) + '$')
    }

    /** The pom as a single line with collapsed whitespace, for simple contains() checks */
    protected static String normalizedXml(File file) {
        file.text.replaceAll('\\s+', ' ').replaceAll('> <', '><').trim()
    }

    protected static String normalizedXml(String xml) {
        xml.replaceAll('\\s+', ' ').replaceAll('> <', '><').trim()
    }

    protected static List<String> jarEntries(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            return jarFile.entries().toList()*.name
        }
    }

    protected static boolean jarContains(File jar, String entry) {
        try (JarFile jarFile = new JarFile(jar)) {
            return jarFile.getEntry(entry) != null
        }
    }
}
