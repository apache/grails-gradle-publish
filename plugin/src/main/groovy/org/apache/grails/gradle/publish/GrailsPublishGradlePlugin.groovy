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
import groovy.transform.CompileStatic
import io.github.gradlenexus.publishplugin.InitializeNexusStagingRepository
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import io.github.gradlenexus.publishplugin.NexusRepository
import io.github.gradlenexus.publishplugin.NexusRepositoryContainer
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.publish.PublicationContainer
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPomDeveloper
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomIssueManagement
import org.gradle.api.publish.maven.MavenPomLicense
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.api.publish.maven.MavenPomScm
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.GroovySourceDirectorySet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

import java.lang.reflect.Modifier

import static org.gradle.api.plugins.BasePlugin.BUILD_GROUP

/**
 * A plugin to ease publishing Grails related artifacts - including source, groovydoc (as javadoc jars), and plugins
 */
@CompileStatic
class GrailsPublishGradlePlugin implements Plugin<Project> {

    public static String NEXUS_PUBLISH_PLUGIN_ID = 'io.github.gradle-nexus.publish-plugin'
    public static String MAVEN_PUBLISH_PLUGIN_ID = 'maven-publish'
    public static String SIGNING_PLUGIN_ID = 'signing'
    public static String ENVIRONMENT_VARIABLE_BASED_RELEASE = 'GRAILS_PUBLISH_RELEASE'
    public static String SNAPSHOT_PUBLISH_TYPE_PROPERTY = 'snapshotPublishType'
    public static String RELEASE_PUBLISH_TYPE_PROPERTY = 'releasePublishType'

    static String createErrorMessage(String missingSetting) {
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
        String signingKeyId = project.findProperty('signing.keyId') ?: System.getenv('SIGNING_KEY')
        if (isRelease) {
            project.logger.lifecycle('Signing is enabled due to release configuration.')
            extraPropertiesExtension.set('signing.keyId', signingKeyId)
            String secringFile = project.findProperty('signing.secretKeyRingFile') ?: System.getenv('SIGNING_KEYRING')
            if (!secringFile) {
                project.logger.lifecycle('No keyring file (SIGNING_KEYRING) has been specified. Assuming the use of local gpgCommand to sign instead.')
                localSigning = true
                extraPropertiesExtension.set('signing.gnupg.keyName', signingKeyId)
            } else {
                project.logger.lifecycle('Keyring file has been specified. Using java to sign.')
                extraPropertiesExtension.set('signing.secretKeyRingFile', secringFile)

                String signingPassphrase = project.findProperty('signing.password') ?: System.getenv('SIGNING_PASSPHRASE')
                if (signingPassphrase) {
                    extraPropertiesExtension.set('signing.password', signingPassphrase)
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
                project.rootProject.extensions.configure(NexusPublishExtension) { NexusPublishExtension it ->
                    if (nexusPublishDescription) {
                        it.repositoryDescription.set(nexusPublishDescription)
                    }
                    it.repositories { NexusRepositoryContainer repoContainer ->
                        repoContainer.sonatype { NexusRepository repo ->
                            if (nexusPublishUrl) {
                                repo.nexusUrl.set(project.uri(nexusPublishUrl))
                            }
                            if (nexusPublishSnapshotUrl) {
                                repo.snapshotRepositoryUrl.set(project.uri(nexusPublishSnapshotUrl))
                            }
                            repo.username.set(nexusPublishUsername)
                            repo.password.set(nexusPublishPassword)
                            if (nexusPublishStagingProfileId) {
                                repo.stagingProfileId.set(nexusPublishStagingProfileId)
                            }
                        }
                    }
                }
            }
        }

        project.afterEvaluate {
            final ExtensionContainer extensionContainer = project.extensions

            validateProjectPublishable(project as Project)

            project.extensions.configure(PublishingExtension) { PublishingExtension pe ->
                final GrailsPublishExtension gpe = extensionContainer.findByType(GrailsPublishExtension)

                final def mavenPublishUrl = project.findProperty('mavenPublishUrl') ?: System.getenv('MAVEN_PUBLISH_URL')
                if (useMavenPublish) {
                    System.setProperty('org.gradle.internal.publish.checksums.insecure', true as String)

                    pe.repositories { RepositoryHandler repoHandler ->
                        repoHandler.maven { MavenArtifactRepository repo ->
                            final String mavenPublishUsername = project.findProperty('mavenPublishUsername') ?: System.getenv('MAVEN_PUBLISH_USERNAME')
                            final String mavenPublishPassword = project.findProperty('mavenPublishPassword') ?: System.getenv('MAVEN_PUBLISH_PASSWORD')
                            if (mavenPublishUsername && mavenPublishPassword) {
                                repo.credentials { PasswordCredentials credentials ->
                                    credentials.username = mavenPublishUsername
                                    credentials.password = mavenPublishPassword
                                }
                            }
                            repo.url = mavenPublishUrl
                            repo.name = 'maven'
                        }

                        def testRepoPath = gpe.testRepositoryPath.getOrNull()
                        if (testRepoPath) {
                            repoHandler.maven { MavenArtifactRepository repo ->
                                repo.name = 'TestCaseMavenRepo'
                                repo.url = testRepoPath
                            }
                        }
                    }
                } else {
                    // This is a local publish. Add the test case repository if it's defined on the extension.
                    def testRepoPath = gpe.testRepositoryPath.getOrNull()
                    if (testRepoPath) {
                        pe.repositories { RepositoryHandler repoHandler ->
                            repoHandler.maven { MavenArtifactRepository repo ->
                                repo.name = 'TestCaseMavenRepo'
                                repo.url = testRepoPath
                            }
                        }
                    }
                }

                pe.publications { PublicationContainer publications ->
                    publications.create(gpe.publicationName.get(), MavenPublication) { MavenPublication publication ->
                        publication.artifactId = gpe.artifactId.get()
                        publication.groupId = gpe.groupId.get()

                        if (gpe.addComponents.get()) {
                            doAddArtefact(project, publication)
                            def extraArtefact = getDefaultExtraArtifact(project)
                            if (extraArtefact) {
                                publication.artifact(extraArtefact)
                            }
                        }

                        publication.pom { MavenPom pom ->
                            pom.name.set(gpe.title.get())
                            pom.description.set(gpe.desc.get())
                            pom.url.set(gpe.websiteUrl.get())

                            def organization = gpe.organization
                            if (organization.name.isPresent() || organization.url.isPresent()) {
                                pom.organization { org ->
                                    if (organization.name.isPresent()) {
                                        org.name.set(organization.name)
                                    }
                                    if (organization.url.isPresent()) {
                                        org.url.set(organization.url)
                                    }
                                }
                            }

                            def license = gpe.license
                            if (license) {
                                def concreteLicense = License.LICENSES.get(license.name)
                                if (concreteLicense) {
                                    pom.licenses { MavenPomLicenseSpec licenses ->
                                        licenses.license { MavenPomLicense pomLicense ->
                                            pomLicense.name.set(concreteLicense.name)
                                            pomLicense.url.set(concreteLicense.url)
                                            pomLicense.distribution.set(concreteLicense.distribution)
                                        }
                                    }
                                } else if (license.name && license.url) {
                                    pom.licenses { MavenPomLicenseSpec licenses ->
                                        licenses.license { MavenPomLicense pomLicense ->
                                            pomLicense.name.set(license.name)
                                            pomLicense.url.set(license.url)
                                            pomLicense.distribution.set(license.distribution)
                                        }
                                    }
                                }
                            } else {
                                throw new RuntimeException(createErrorMessage('license'))
                            }

                            pom.scm { MavenPomScm scm ->
                                scm.url.set(gpe.scmUrl.get())
                                scm.connection.set(gpe.scmUrlConnection.get())
                                scm.developerConnection.set(gpe.scmUrlConnection.get())
                            }

                            pom.issueManagement { MavenPomIssueManagement issue ->
                                issue.system.set(gpe.issueTrackerName.get())
                                issue.url.set(gpe.issueTrackerUrl.get())
                            }

                            if (gpe.developers) {
                                pom.developers { MavenPomDeveloperSpec devs ->
                                    for (MavenPomDeveloper source : gpe.developers.get()) {
                                        devs.developer { MavenPomDeveloper target ->
                                            cloneDeveloper(source, target)
                                        }
                                    }
                                }
                            } else {
                                throw new RuntimeException(createErrorMessage('developers'))
                            }

                            pom.withXml { XmlProvider xml ->
                                Node pomNode = xml.asNode()

                                if (!project.extensions.findByType(JavaPlatformExtension)) {
                                    // Spring boot dependency management plugin will add the dependencyManagement section,
                                    // we do not want to publish this information as we will determine the specific versions
                                    // and set them instead
                                    NodeList dependencyManagement = (NodeList) pomNode.get('dependencyManagement')
                                    if (dependencyManagement) {
                                        dependencyManagement.replaceNode {}
                                    }
                                }

                                if (gpe.pomCustomization.isPresent()) {
                                    Closure pomCustomization = gpe.pomCustomization.get()
                                    pomCustomization.delegate = pom
                                    pomCustomization.resolveStrategy = Closure.DELEGATE_FIRST
                                    pomCustomization.call(xml)
                                }

                                // fix dependencies without a version, this can occur when the spring dependency management plugin is used
                                // disabling that plugin will cause gradle to fail on any unresolved, or by disabling the check with:
                                // https://github.com/gradle/gradle/issues/23030
                                //tasks.withType(GenerateModuleMetadata).configureEach {
                                //    suppressedValidationErrors.add('dependencies-without-versions')
                                //}
                                if (gpe.transitiveDependencies.get()) {
                                    setDependencyVersions(pomNode, project)
                                }
                            }
                        }
                    }
                }
            }

            if (isRelease) {
                extensionContainer.configure(SigningExtension) {
                    it.required = isRelease
                    if (localSigning) {
                        it.useGpgCmd()
                    }

                    PublishingExtension publishing = project.extensions.getByType(PublishingExtension)
                    it.sign(publishing.publications)
                }

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

    static void cloneDeveloper(MavenPomDeveloper source, MavenPomDeveloper target) {
        source.metaClass.properties.each {
            if(!Modifier.isPublic(it.modifiers)) {
                return
            }

            String propertyName = it.name
            def sourceProperty = it.getProperty(source)
            if(sourceProperty == null || !Provider.isAssignableFrom(sourceProperty.class)) {
                return
            }

            if (!target.hasProperty(propertyName)) {
                return
            }

            def targetProperty = ((GroovyObject)target).getProperty(propertyName)
            switch (sourceProperty) {
                case Property:
                    (targetProperty as Property).set((sourceProperty as Property).orNull)
                    break
                case SetProperty:
                    (targetProperty as SetProperty).addAll((sourceProperty as SetProperty).getOrElse([] as Set))
                    break
                case ListProperty:
                    (targetProperty as ListProperty).addAll((sourceProperty as ListProperty).getOrElse([]))
                    break
                case MapProperty:
                    (targetProperty as MapProperty).putAll((sourceProperty as MapProperty).getOrElse([:]) as Map)
                    break
                default:
                    throw new IllegalStateException("Could not handle type [${targetProperty.class}] for property [${propertyName}]")
            }
        }
    }

    protected void setDependencyVersions(Node pomNode, Project project) {
        def mavenPomNamespace = 'http://maven.apache.org/POM/4.0.0'
        def dependenciesQName = new QName(mavenPomNamespace, 'dependencies')
        def dependencyQName = new QName(mavenPomNamespace, 'dependency')
        def versionQName = new QName(mavenPomNamespace, 'version')
        def groupIdQName = new QName(mavenPomNamespace, 'groupId')
        def artifactIdQName = new QName(mavenPomNamespace, 'artifactId')

        NodeList nodes = pomNode.getAt(dependenciesQName) as NodeList
        if (nodes.isEmpty()) {
            return
        }
        NodeList dependencyNodes = (nodes.get(0) as Node).getAt(dependencyQName) as NodeList

        LinkedHashSet<ResolvedArtifact> resolvedArtifacts = []
        resolvedArtifacts.addAll(project.configurations.getByName('compileClasspath').resolvedConfiguration.resolvedArtifacts)
        resolvedArtifacts.addAll(project.configurations.getByName('runtimeClasspath').resolvedConfiguration.resolvedArtifacts)
        if (project.configurations.findByName('testFixturesCompileClasspath') != null) {
            resolvedArtifacts.addAll(project.configurations.getByName('testFixturesCompileClasspath').resolvedConfiguration.resolvedArtifacts)
            resolvedArtifacts.addAll(project.configurations.getByName('testFixturesRuntimeClasspath').resolvedConfiguration.resolvedArtifacts)
        }

        dependencyNodes.findAll { dependencyNode ->
            NodeList versionNodes = (dependencyNode as Node)[versionQName] as NodeList
            return versionNodes.size() == 0 || (versionNodes.first() as Node).text().trim().isEmpty()
        }.each { objectNode ->
            def dependencyNode = objectNode as Node
            def groupId = (dependencyNode[groupIdQName].first() as Node).text()
            def artifactId = (dependencyNode[artifactIdQName].first() as Node).text()

            def managedVersion = resolvedArtifacts.find {
                it.moduleVersion.id.group == groupId &&
                        it.moduleVersion.id.name == artifactId
            }?.moduleVersion?.id?.version
            if (!managedVersion) {
                throw new RuntimeException("No version found for dependency $groupId:$artifactId.")
            }

            NodeList versionNode = dependencyNode[versionQName]
            if (versionNode) {
                (versionNode.first() as Node).value = managedVersion
            } else {
                dependencyNode.appendNode('version', managedVersion)
            }
        }
    }

    protected void addInstallTaskAliases(Project project) {
        final TaskContainer taskContainer = project.tasks
        if (!taskContainer.names.contains('install')) {
            taskContainer.register('install') { Task task ->
                task.dependsOn(taskContainer.named('publishToMavenLocal'))
                task.setGroup('publishing')
            }
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
            publication.from(project.components.named('javaPlatform').get())

            if (gpe.publishTestSources.get()) {
                throw new RuntimeException('BOM publishes may only contain dependencies.')
            }

            return
        }

        publication.from project.components.named('java').get()
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

        def main = sourceSets.named('main').get()
        def groovy = main.getExtensions().findByType(GroovySourceDirectorySet)
        if (!groovy) {
            return null
        }

        def pluginXml = groovy.classesDirectory.get().file('META-INF/grails-plugin.xml').asFile
        pluginXml.exists() ? [
                source    : pluginXml.canonicalPath,
                classifier: getDefaultClassifier(),
                extension : 'xml'
        ] : null
    }

    protected String getDefaultClassifier() {
        'plugin'
    }

    protected validateProjectPublishable(Project project) {
        def javaPlugin = project.extensions.findByType(JavaPluginExtension)
        def javaPlatform = project.extensions.findByType(JavaPlatformExtension)

        if (!javaPlugin && !javaPlatform) {
            throw new RuntimeException('Grails Publish Plugin requires the Java Platform or Java Plugin to be applied to the project.')
        }

        if (javaPlatform) {
            return
        }

        project.extensions.configure(JavaPluginExtension) {
            it.withJavadocJar()
            it.withSourcesJar()
        }

        final TaskContainer tasks = project.tasks
        tasks.named('javadoc').configure {
            if (tasks.names.contains('groovydoc')) {
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

            Groovydoc groovyDocTask = tasks.findByName('groovydoc') as Groovydoc
            if (groovyDocTask) {
                jar.dependsOn(groovyDocTask)

                // Ensure the java source set is included in the groovydoc source set
                SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
                groovyDocTask.source(project.files(sourceSets.named('main').get().java.srcDirs))

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
            def testSourceSet = sourceSets.named('test').get()
            jar.from(testSourceSet.output)
            jar.inputs.files(testSourceSet.output)
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

