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
import io.github.gradlenexus.publishplugin.InitializeNexusStagingRepository
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.api.publish.Publication
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

import static org.gradle.api.plugins.BasePlugin.BUILD_GROUP

/**
 * A plugin to ease publishing Grails related artifacts - including source, groovydoc (as javadoc jars), and plugins
 */
class GrailsPublishGradlePlugin implements Plugin<Project> {

    public static String NEXUS_PUBLISH_PLUGIN_ID = 'io.github.gradle-nexus.publish-plugin'
    public static String MAVEN_PUBLISH_PLUGIN_ID = 'maven-publish'
    public static String SIGNING_PLUGIN_ID = 'signing'
    public static String ENVIRONMENT_VARIABLE_BASED_RELEASE = 'GRAILS_PUBLISH_RELEASE'
    public static String SNAPSHOT_PUBLISH_TYPE_PROPERTY = 'snapshotPublishType'
    public static String RELEASE_PUBLISH_TYPE_PROPERTY = 'releasePublishType'

    String getErrorMessage(String missingSetting) {
        return """No '$missingSetting' was specified. Please provide a valid publishing configuration. Example:

grailsPublish {
    websiteUrl = 'https://example.com/myplugin'
    license {
        name = 'Apache-2.0'
    }
    issueTrackerUrl = 'https://github.com/myname/myplugin/issues'
    vcsUrl = 'https://github.com/myname/myplugin'
    title = 'My plugin title'
    desc = 'My plugin description'
    developers = [johndoe: 'John Doe']
}

or

grailsPublish {
    githubSlug = 'foo/bar'
    license {
        name = 'Apache-2.0'
    }
    title = 'My plugin title'
    desc = 'My plugin description'
    developers = [johndoe: 'John Doe']
}

By default snapshotPublishType is set to MAVEN_PUBLISH and releasePublishType is set to NEXUS_PUBLISH.  These can be overridden by setting the associated property.

The credentials and connection url must be specified as a project property or an environment variable:

`MAVEN_PUBLISH` Environment Variables are:
    MAVEN_PUBLISH_USERNAME
    MAVEN_PUBLISH_PASSWORD
    MAVEN_PUBLISH_URL

`NEXUS_PUBLISH` Environment Variables are:
    NEXUS_PUBLISH_USERNAME
    NEXUS_PUBLISH_PASSWORD
    NEXUS_PUBLISH_URL
    NEXUS_PUBLISH_SNAPSHOT_URL
    NEXUS_PUBLISH_STAGING_PROFILE_ID

When using `NEXUS_PUBLISH`, either the property `signing.secretKeyRingFile` must be set to the path of the GPG keyring file or local gpg must be configured to sign artifacts.

Note: if project properties are used, the properties must be defined prior to applying this plugin.
"""
    }

    @Override
    void apply(Project project) {
        project.rootProject.logger.info("Applying Grails Publish Gradle Plugin for `${project.name}`...");
        if (project.extensions.findByName('grailsPublish') == null) {
            project.extensions.create('grailsPublish', GrailsPublishExtension)
        }
        final String nexusPublishUrl = project.findProperty('nexusPublishUrl') ?: System.getenv('NEXUS_PUBLISH_URL') ?: ''
        final String nexusPublishSnapshotUrl = project.findProperty('nexusPublishSnapshotUrl') ?: System.getenv('NEXUS_PUBLISH_SNAPSHOT_URL') ?: ''
        final String nexusPublishUsername = project.findProperty('nexusPublishUsername') ?: System.getenv('NEXUS_PUBLISH_USERNAME') ?: ''
        final String nexusPublishPassword = project.findProperty('nexusPublishPassword') ?: System.getenv('NEXUS_PUBLISH_PASSWORD') ?: ''
        final String nexusPublishStagingProfileId = project.findProperty('nexusPublishStagingProfileId') ?: System.getenv('NEXUS_PUBLISH_STAGING_PROFILE_ID') ?: ''
        final String nexusPublishDescription = project.findProperty('nexusPublishDescription') ?: System.getenv('NEXUS_PUBLISH_DESCRIPTION') ?: ''

        final ExtraPropertiesExtension extraPropertiesExtension = project.extensions.findByType(ExtraPropertiesExtension)

        PublishType snapshotPublishType = project.hasProperty(SNAPSHOT_PUBLISH_TYPE_PROPERTY) ? PublishType.valueOf(project.property(SNAPSHOT_PUBLISH_TYPE_PROPERTY) as String) : PublishType.MAVEN_PUBLISH
        PublishType releasePublishType = project.hasProperty(RELEASE_PUBLISH_TYPE_PROPERTY) ? PublishType.valueOf(project.property(RELEASE_PUBLISH_TYPE_PROPERTY) as String) : PublishType.NEXUS_PUBLISH

        boolean isSnapshot, isRelease
        if (System.getenv(ENVIRONMENT_VARIABLE_BASED_RELEASE) != null) {
            // Detect release state based on environment variables instead of versions
            isRelease = Boolean.parseBoolean(System.getenv(ENVIRONMENT_VARIABLE_BASED_RELEASE))
            isSnapshot = !isRelease

            project.rootProject.logger.lifecycle('Environment Variable `{}` detected - using variable instead of project version.', ENVIRONMENT_VARIABLE_BASED_RELEASE)
        } else {
            String detectedVersion = (project.version == Project.DEFAULT_VERSION ? (project.findProperty('projectVersion') ?: Project.DEFAULT_VERSION) : project.version) as String
            if (detectedVersion == Project.DEFAULT_VERSION) {
                throw new IllegalStateException("Project ${project.name} has an unspecified version (neither `version` or the property `projectVersion` is defined). Release state cannot be determined.")
            }
            project.rootProject.logger.info('Version {} detected for project {}', detectedVersion, project.name)

            isSnapshot = detectedVersion.endsWith('SNAPSHOT')
            isRelease = !isSnapshot

            if (project.version == Project.DEFAULT_VERSION) {
                if (isRelease) {
                    project.rootProject.logger.warn('Project {} does not have a version defined. Using the gradle property `projectVersion` to assume version is {}.', project.name, detectedVersion)
                } else {
                    project.rootProject.logger.info('Project {} does not have a version defined. Using the gradle property `projectVersion` to assume version is {}.', project.name, detectedVersion)
                }
            }
        }

        if (isSnapshot) {
            project.rootProject.logger.info('Project {} will be a snapshot.', project.name)
        }
        if (isRelease) {
            project.rootProject.logger.info('Project {} will be a release.', project.name)
        }

        boolean useMavenPublish = (isSnapshot && snapshotPublishType == PublishType.MAVEN_PUBLISH) || (isRelease && releasePublishType == PublishType.MAVEN_PUBLISH)
        if (useMavenPublish) {
            project.rootProject.logger.info('Maven Publish is enabled for project {}', project.name)
        }
        boolean useNexusPublish = (isSnapshot && snapshotPublishType == PublishType.NEXUS_PUBLISH) || (isRelease && releasePublishType == PublishType.NEXUS_PUBLISH)
        if (useNexusPublish) {
            project.rootProject.logger.info('Nexus Publish is enabled for project {}', project.name)
        }

        // Required for the pom always
        final PluginManager projectPluginManager = project.pluginManager
        projectPluginManager.apply(MavenPublishPlugin)

        boolean localSigning = false
        String signingKeyId = project.findProperty('signing.keyId') ?: System.getenv('SIGNING_PASSPHRASE')
        if (isRelease) {
            extraPropertiesExtension.setProperty('signing.keyId', signingKeyId)
            String secringFile = project.findProperty('signing.secretKeyRingFile') ?: System.getenv('SIGNING_KEYRING')
            if (!secringFile) {
                project.logger.lifecycle('No keyring file (SIGNING_KEYRING) has been specified. Assuming the use of local gpgCommand to sign instead.')
                localSigning = true
                extraPropertiesExtension.setProperty('signing.gnupg.keyName', signingKeyId)
            } else {
                extraPropertiesExtension.setProperty('signing.secretKeyRingFile', secringFile)

                String signingPassphrase = project.findProperty('signing.password') ?: System.getenv('SIGNING_PASSPHRASE')
                if (signingPassphrase) {
                    extraPropertiesExtension.setProperty('signing.password', signingPassphrase)
                }
            }
        }

        if (isRelease || useNexusPublish) {
            if (project.pluginManager.hasPlugin(SIGNING_PLUGIN_ID)) {
                project.rootProject.logger.debug('Signing Plugin already applied to project {}', project.name)
            } else {
                projectPluginManager.apply(SigningPlugin)
            }

            project.tasks.withType(Sign).configureEach { Sign task ->
                task.onlyIf { isRelease }
            }
        }


        if (useNexusPublish) {
            // The nexus plugin is special since it must always be applied to the root project.
            // Handle when multiple subprojects exist and grailsPublish is defined in each one instead of at the root.
            final PluginManager rootProjectPluginManager = project.rootProject.pluginManager
            boolean hasNexusPublishApplied = rootProjectPluginManager.hasPlugin(NEXUS_PUBLISH_PLUGIN_ID)
            if (hasNexusPublishApplied) {
                project.rootProject.logger.debug('Nexus Publish Plugin already applied to root project')
            } else {
                rootProjectPluginManager.apply(NexusPublishPlugin)
            }

            if (isRelease) {
                project.rootProject.tasks.withType(InitializeNexusStagingRepository).configureEach { InitializeNexusStagingRepository task ->
                    task.shouldRunAfter = project.tasks.withType(Sign)
                }
            }

            if (!hasNexusPublishApplied) {
                project.rootProject.nexusPublishing {
                    if (nexusPublishDescription) {
                        repositoryDescription = "${nexusPublishDescription}"
                    }
                    repositories {
                        sonatype {
                            if (nexusPublishUrl) {
                                nexusUrl = project.uri(nexusPublishUrl)
                            }
                            if (nexusPublishSnapshotUrl) {
                                snapshotRepositoryUrl = project.uri(nexusPublishSnapshotUrl)
                            }
                            username = nexusPublishUsername
                            password = nexusPublishPassword
                            if (nexusPublishStagingProfileId) {
                                stagingProfileId = nexusPublishStagingProfileId
                            }
                        }
                    }
                }
            }
        }

        project.afterEvaluate {
            final ExtensionContainer extensionContainer = project.extensions

            validateProjectPublishable(project as Project)

            project.publishing {
                final GrailsPublishExtension gpe = extensionContainer.findByType(GrailsPublishExtension)

                final def mavenPublishUrl = project.findProperty('mavenPublishUrl') ?: System.getenv('MAVEN_PUBLISH_URL')
                if (useMavenPublish) {
                    System.setProperty('org.gradle.internal.publish.checksums.insecure', true as String)

                    repositories {
                        maven {
                            final String mavenPublishUsername = project.findProperty('mavenPublishUsername') ?: System.getenv('MAVEN_PUBLISH_USERNAME')
                            final String mavenPublishPassword = project.findProperty('mavenPublishPassword') ?: System.getenv('MAVEN_PUBLISH_PASSWORD')
                            if (mavenPublishUsername && mavenPublishPassword) {
                                credentials {
                                    username = mavenPublishUsername
                                    password = mavenPublishPassword
                                }
                            }
                            url = mavenPublishUrl
                        }

                        def testRepoPath = gpe.testRepositoryPath.getOrNull()
                        if (testRepoPath) {
                            maven {
                                name = 'TestCaseMavenRepo'
                                url = testRepoPath
                            }
                        }
                    }
                } else {
                    // This is a local publish. Add the test case repository if it's defined on the extension.
                    def testRepoPath = gpe.testRepositoryPath.getOrNull()
                    if (testRepoPath) {
                        repositories {
                            maven {
                                name = 'TestCaseMavenRepo'
                                url = testRepoPath
                            }
                        }
                    }
                }

                publications {
                    it.create(gpe.publicationName.get(), MavenPublication) {
                        delegate.artifactId = gpe.artifactId.get()
                        delegate.groupId = gpe.groupId.get()

                        if (gpe.addComponents.get()) {
                            doAddArtefact(project, delegate)
                            def extraArtefact = getDefaultExtraArtifact(project)
                            if (extraArtefact) {
                                artifact extraArtefact
                            }
                        }

                        pom.withXml {
                            Node pomNode = asNode()

                            if (!project.extensions.findByType(JavaPlatformExtension)) {
                                // Prevent multiple dependencyManagement nodes
                                if (pomNode.dependencyManagement) {
                                    pomNode.dependencyManagement[0].replaceNode {}
                                }
                            }

                            if (gpe != null) {
                                pomNode.children().last() + {
                                    delegate.name gpe.title.get()
                                    delegate.description gpe.desc.get()
                                    delegate.url gpe.websiteUrl.get()

                                    def license = gpe.license
                                    if (license != null) {
                                        def concreteLicense = GrailsPublishExtension.License.LICENSES.get(license.name)
                                        if (concreteLicense != null) {
                                            delegate.licenses {
                                                delegate.license {
                                                    delegate.name concreteLicense.name
                                                    delegate.url concreteLicense.url
                                                    delegate.distribution concreteLicense.distribution
                                                }
                                            }
                                        } else if (license.name && license.url) {
                                            delegate.licenses {
                                                delegate.license {
                                                    delegate.name license.name
                                                    delegate.url license.url
                                                    delegate.distribution license.distribution
                                                }
                                            }
                                        }
                                    } else {
                                        throw new RuntimeException(getErrorMessage('license'))
                                    }

                                    delegate.scm {
                                        delegate.url gpe.scmUrl.get()
                                        delegate.connection gpe.scmUrlConnection.get()
                                        delegate.developerConnection gpe.scmUrlConnection.get()
                                    }

                                    delegate.issueManagement {
                                        delegate.system gpe.issueTrackerName.get()
                                        delegate.url gpe.issueTrackerUrl.get()
                                    }

                                    if (gpe.developers) {
                                        delegate.developers {
                                            for (entry in gpe.developers.get().entrySet()) {
                                                delegate.developer {
                                                    delegate.id entry.key
                                                    delegate.name entry.value
                                                }
                                            }
                                        }
                                    } else {
                                        throw new RuntimeException(getErrorMessage('developers'))
                                    }
                                }
                            }

                            if (gpe.pomCustomization) {
                                gpe.pomCustomization.delegate = delegate
                                gpe.pomCustomization.resolveStrategy = Closure.DELEGATE_FIRST
                                gpe.pomCustomization.call()
                            }

                            // fix dependencies without a version
                            if (gpe.transitiveDependencies.get()) {
                                def mavenPomNamespace = 'http://maven.apache.org/POM/4.0.0'
                                def dependenciesQName = new QName(mavenPomNamespace, 'dependencies')
                                def dependencyQName = new QName(mavenPomNamespace, 'dependency')
                                def versionQName = new QName(mavenPomNamespace, 'version')
                                def groupIdQName = new QName(mavenPomNamespace, 'groupId')
                                def artifactIdQName = new QName(mavenPomNamespace, 'artifactId')
                                def nodes = (pomNode.getAt(dependenciesQName) as NodeList)
                                if (nodes) {
                                    def dependencyNodes = (nodes.first() as Node).getAt(dependencyQName)
                                    dependencyNodes.findAll { dependencyNode ->
                                        def versionNodes = (dependencyNode as Node).getAt(versionQName)
                                        return versionNodes.size() == 0 || (versionNodes.first() as Node).text().isEmpty()
                                    }.each { dependencyNode ->
                                        def groupId = ((dependencyNode as Node).getAt(groupIdQName).first() as Node).text()
                                        def artifactId = ((dependencyNode as Node).getAt(artifactIdQName).first() as Node).text()
                                        def resolvedArtifacts = project.configurations.compileClasspath.resolvedConfiguration.resolvedArtifacts +
                                                project.configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
                                        if (project.configurations.hasProperty('testFixturesCompileClasspath')) {
                                            resolvedArtifacts += project.configurations.testFixturesCompileClasspath.resolvedConfiguration.resolvedArtifacts +
                                                    project.configurations.testFixturesRuntimeClasspath.resolvedConfiguration.resolvedArtifacts
                                        }
                                        def managedVersion = resolvedArtifacts.find {
                                            it.moduleVersion.id.group == groupId &&
                                                    it.moduleVersion.id.name == artifactId
                                        }?.moduleVersion?.id?.version
                                        if (!managedVersion) {
                                            throw new RuntimeException("No version found for dependency $groupId:$artifactId.")
                                        }
                                        def versionNode = (dependencyNode as Node).getAt(versionQName)
                                        if (versionNode) {
                                            (versionNode.first() as Node).value = managedVersion
                                        } else {
                                            (dependencyNode as Node).appendNode('version', managedVersion)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isRelease) {
                extensionContainer.configure(SigningExtension, {
                    it.required = isRelease
                    if (localSigning) {
                        it.useGpgCmd()
                    }

                    Publication[] publications = new Publication[project.publishing.publications.size()]
                    project.publishing.publications.findAll().toArray(publications)
                    it.sign(publications)
                })

                // The sign task does not properly setup dependencies, see https://github.com/gradle/gradle/issues/26091
                project.tasks.withType(Sign).configureEach {
                    it.dependsOn(project.tasks.withType(Jar))
                    it.doFirst {
                        if (!signingKeyId) {
                            throw new GradleException('A signing key is required to sign a release. Set GRAILS_PUBLISH_RELEASE=false to bypass signing.')
                        }
                    }
                }
                project.tasks.withType(PublishToMavenRepository).configureEach {
                    it.mustRunAfter(project.tasks.withType(Sign))
                }
            }

            if (project.rootProject.tasks.names.contains('publishAllPublicationsToTestCaseMavenRepoRepository')) {
                project.rootProject.tasks.named('publishAllPublicationsToTestCaseMavenRepoRepository').configure {
                    it.dependsOn(project.tasks.named('publishAllPublicationsToTestCaseMavenRepoRepository'))
                }
            }

            addInstallTaskAliases(project)
        }
    }

    protected void addInstallTaskAliases(Project project) {
        final TaskContainer taskContainer = project.tasks
        if (!taskContainer.names.contains('install')) {
            taskContainer.register('install', { Task task ->
                task.dependsOn(taskContainer.named('publishToMavenLocal'))
                task.setGroup('publishing')
            })
        }
    }

    protected void registerValidationTask(Project project, String taskName, Closure c) {
        project.plugins.withId(MAVEN_PUBLISH_PLUGIN_ID) {
            TaskProvider<? extends Task> publishTask = project.tasks.named('publish')

            TaskProvider validateTask = project.tasks.register(taskName, c)
            publishTask.configure {
                it.dependsOn validateTask
            }
        }
    }

    protected void doAddArtefact(Project project, MavenPublication publication) {
        GrailsPublishExtension gpe = project.extensions.findByType(GrailsPublishExtension)
        if (project.extensions.findByType(JavaPlatformExtension)) {
            publication.from project.components.javaPlatform

            if (gpe.publishTestSources.get()) {
                throw new RuntimeException('BOM publishes may only contain dependencies.')
            }

            return
        }

        publication.from project.components.java
        if (gpe.publishTestSources.get()) {
            publication.artifact(project.tasks.named('testSourcesJar', Jar))
        }
    }

    private static SourceSetContainer findSourceSets(Project project) {
        JavaPluginExtension plugin = project.extensions.getByType(JavaPluginExtension)
        SourceSetContainer sourceSets = plugin?.sourceSets
        return sourceSets
    }

    protected Map<String, String> getDefaultExtraArtifact(Project project) {
        if (project.extensions.findByType(JavaPlatformExtension)) {
            return null
        }

        SourceSetContainer sourceSets = findSourceSets(project)
        if (!sourceSets.main.hasProperty('groovy')) {
            return null
        }

        String pluginXml = "${sourceSets.main.groovy.getClassesDirectory().get().getAsFile()}/META-INF/grails-plugin.xml".toString()
        new File(pluginXml).exists() ? [
                source    : pluginXml,
                classifier: getDefaultClassifier(),
                extension : 'xml'
        ] : null
    }

    protected String getDefaultClassifier() {
        'plugin'
    }

    protected validateProjectPublishable(Project project) {
        boolean hasJavaPlugin = project.extensions.findByType(JavaPluginExtension)
        boolean hasJavaPlatform = project.extensions.findByType(JavaPlatformExtension)

        if (!hasJavaPlugin && !hasJavaPlatform) {
            if (!hasJavaPlugin) {
                throw new RuntimeException('Grails Publish Plugin requires the Java Plugin to be applied to the project.')
            }

            throw new RuntimeException('Grails Publish Plugin requires the Java Platform Plugin to be applied to the project.')
        }

        if (hasJavaPlatform) {
            return
        }

        project.extensions.configure(JavaPluginExtension) {
            it.withJavadocJar()
            it.withSourcesJar()
        }

        final TaskContainer tasks = project.tasks
        tasks.named('javadoc').configure {
            Task groovyDocTask = tasks.findByName('groovydoc')
            if (groovyDocTask) {
                project.rootProject.logger.info('Configuring javadocJar task for project {} to include groovydoc', project.name)
                it.enabled = false
            }
        }

        tasks.named('javadocJar', Jar).configure { Jar jar ->
            jar.reproducibleFileOrder = true
            jar.preserveFileTimestamps = false
            // to avoid platform specific defaults, set the permissions consistently
            jar.filePermissions { permissions ->
                permissions.unix(0644)
            }
            jar.dirPermissions { permissions ->
                permissions.unix(0755)
            }

            Groovydoc groovyDocTask = tasks.findByName('groovydoc')
            if (groovyDocTask) {
                jar.dependsOn(groovyDocTask)

                // Ensure the java source set is included in the groovydoc source set
                SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
                groovyDocTask.source(project.files(sourceSets.main.java.srcDirs))

                ConfigurableFileCollection groovyDocFiles = project.files(groovyDocTask.destinationDir)
                jar.from(groovyDocFiles)
                jar.inputs.files(groovyDocFiles)
            }
        }

        tasks.named('sourcesJar', Jar).configure { Jar jar ->
            SourceSetContainer sourceSets = GrailsPublishGradlePlugin.findSourceSets(project)
            jar.reproducibleFileOrder = true
            jar.preserveFileTimestamps = false
            // to avoid platform specific defaults, set the permissions consistently
            jar.filePermissions { permissions ->
                permissions.unix(0644)
            }
            jar.dirPermissions { permissions ->
                permissions.unix(0755)
            }
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

            // don't only include main, but any source set
            jar.from sourceSets.collect { it.allSource }
            jar.inputs.files(sourceSets.collect { it.allSource })
        }

        project.tasks.register('testSourcesJar', Jar).configure { Jar jar ->
            jar.onlyIf {
                project.extensions.findByType(GrailsPublishExtension).publishTestSources.get() &&
                        !jar.source.files.isEmpty()
            }
            jar.dependsOn('testClasses')
            jar.reproducibleFileOrder = true
            jar.preserveFileTimestamps = false
            // to avoid platform specific defaults, set the permissions consistently
            jar.filePermissions { permissions ->
                permissions.unix(0644)
            }
            jar.dirPermissions { permissions ->
                permissions.unix(0755)
            }
            SourceSetContainer sourceSets = GrailsPublishGradlePlugin.findSourceSets(project)
            jar.from sourceSets.test.output
            jar.inputs.files(sourceSets.test.output)
            jar.archiveClassifier.set('tests')
            jar.group = BUILD_GROUP
        }

        // TODO: Revisit this as an optional feature instead of forced, see @PendingFeature test case
        // it's valid to publish boms, profiles, and projects that export only dependencies without any code
        // so for now remove this and let the maven publish plugin fail if conditions aren't met
//        SourceSetContainer sourceSets = findSourceSets(project)
//        Collection<SourceSet> publishedSources = sourceSets.findAll { SourceSet sourceSet ->
//            (
//                    project.extensions.findByType(GrailsPublishExtension).publishTestSources ||
//                            sourceSet.name != SourceSet.TEST_SOURCE_SET_NAME
//            ) && !sourceSet.allSource.isEmpty()
//        }
//        if (!publishedSources) {
//            throw new RuntimeException("Cannot apply Grails Publish Plugin. Project ${project.name} does not have anything to publish.")
//        }

        registerValidationTask(project, 'grailsPublishValidation') {
            Task groovyDocTask = project.tasks.findByName('groovydoc')
            if (groovyDocTask) {
                if (!groovyDocTask.enabled) {
                    throw new RuntimeException('Groovydoc task is disabled. Please enable it to ensure javadoc can be published correctly with the Grails Publish Plugin.')
                }
            }
        }
    }
}

