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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.namespace.QName
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import io.github.gradlenexus.publishplugin.InitializeNexusStagingRepository
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import io.github.gradlenexus.publishplugin.NexusRepository
import io.github.gradlenexus.publishplugin.NexusRepositoryContainer
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.component.SoftwareComponentVariant
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.CopySpec
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileTreeElement
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
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPomDeveloper
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomIssueManagement
import org.gradle.api.publish.maven.MavenPomLicense
import org.gradle.api.publish.maven.MavenPomLicenseSpec
import org.gradle.api.publish.maven.MavenPomScm
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.GroovySourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

import javax.inject.Inject
import java.lang.reflect.Modifier
import java.nio.file.Path

import static org.gradle.api.plugins.BasePlugin.BUILD_GROUP

/**
 * A plugin to ease publishing Grails related artifacts - including source, groovydoc (as javadoc jars), and plugins
 */
@CompileStatic
class GrailsPublishGradlePlugin implements Plugin<Project> {

    private static final Logger LOG = Logging.getLogger(GrailsPublishGradlePlugin)

    public static String NEXUS_PUBLISH_PLUGIN_ID = 'io.github.gradle-nexus.publish-plugin'
    public static String MAVEN_PUBLISH_PLUGIN_ID = 'maven-publish'
    public static String SIGNING_PLUGIN_ID = 'signing'
    public static String ENVIRONMENT_VARIABLE_BASED_RELEASE = 'GRAILS_PUBLISH_RELEASE'
    public static String SNAPSHOT_PUBLISH_TYPE_PROPERTY = 'snapshotPublishType'
    public static String RELEASE_PUBLISH_TYPE_PROPERTY = 'releasePublishType'
    /** The Gradle plugins under which the Grails compiler generates META-INF/grails-plugin.xml */
    public static List<String> GRAILS_PLUGIN_IDS = ['org.apache.grails.gradle.grails-plugin', 'org.grails.grails-plugin'].asImmutable()
    public static String PLUGIN_DESCRIPTOR_PATH = 'META-INF/grails-plugin.xml'
    public static String MAIN_GROOVY_COMPILE_TASK = 'compileGroovy'

    /**
     * Artifact types of the class and resource directory variants Gradle creates for a source set. The java
     * component leaves them out, since a directory cannot be published.
     */
    private static final Set<String> DIRECTORY_ARTIFACT_TYPES = [
            ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
            ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY,
            ArtifactTypeDefinition.DIRECTORY_TYPE,
    ] as Set<String>

    /**
     * Injected through a getter rather than the constructor, so plugins extending this one keep working with their own
     * constructors.
     */
    @Inject
    protected BuildFeatures getBuildFeatures() {
        throw new UnsupportedOperationException('Injected by Gradle')
    }

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

Note: properties are read as Gradle properties (the root project's gradle.properties, the Gradle user home, -P or ORG_GRADLE_PROJECT_ environment variables), from the project applying this plugin (its build script, before the plugin is applied), or from a parent project's build script; the last is reported and not available with Isolated Projects. or its build script before the plugin is applied. Properties set on parent projects, including in the gradle.properties of a parent project's directory, are not read.
"""
    }

    /**
     * Finds a property set via `ext` on the given project, a Gradle property, or, failing both, a property set via
     * `ext` on a parent project.
     *
     * The project's own extra properties are checked first, so a value set in its build script overrides a Gradle
     * property, as it does with {@link Project#findProperty}. Parent projects are read explicitly rather than through
     * that method: Gradle 10 removes its implicit lookup of parent project properties, and reading them at all is not
     * allowed with Isolated Projects, so a value found on a parent is reported (builds enabling Isolated Projects have
     * to declare the property on the project itself or as a Gradle property) and parents are not read when Isolated
     * Projects is active.
     *
     * @param quietly log a parent lookup at info instead of warn, for properties every project of a build is expected
     *                to inherit, such as `projectVersion`
     */
    Object findProjectProperty(Project project, String name, boolean quietly = false) {
        def extraProperties = project.extensions.extraProperties
        if (extraProperties.has(name)) {
            return extraProperties.get(name)
        }
        Object gradleProperty = project.providers.gradleProperty(name).orNull
        if (gradleProperty != null) {
            return gradleProperty
        }
        if (buildFeatures.isolatedProjects.active.get()) {
            // parent projects cannot be inspected, and such builds never relied on reading them
            return null
        }
        for (Project parent = project.parent; parent != null; parent = parent.parent) {
            if (parent.extensions.extraProperties.has(name)) {
                String message = 'Property `{}` of {} was read from {}. Declare it on the project itself, in its build script ' +
                        'before applying the Grails Publish plugin, or as a Gradle property (gradle.properties, -P{}=..., ' +
                        'ORG_GRADLE_PROJECT_{}): parent project properties cannot be read with Isolated Projects.'
                if (quietly) {
                    LOG.info(message, name, project, parent, name, name)
                } else {
                    LOG.warn(message, name, project, parent, name, name)
                }
                return parent.extensions.extraProperties.get(name)
            }
        }
        null
    }

    @Override
    void apply(Project project) {
        LOG.info('Applying Grails Publish Gradle Plugin for `{}`...', project.name)
        if (project.extensions.findByName('grailsPublish') == null) {
            project.extensions.create('grailsPublish', GrailsPublishExtension)
        }
        GrailsPublishExtension extension = project.extensions.getByType(GrailsPublishExtension)
        extension.githubSlug.convention(project.provider { findProjectProperty(project, 'githubSlug', true) as String })
        extension.pluginDescriptor.convention(defaultPluginDescriptor(project))
        final ExtraPropertiesExtension extraPropertiesExtension = project.extensions.findByType(ExtraPropertiesExtension)

        final Object snapshotPublishTypeProperty = findProjectProperty(project, SNAPSHOT_PUBLISH_TYPE_PROPERTY)
        final Object releasePublishTypeProperty = findProjectProperty(project, RELEASE_PUBLISH_TYPE_PROPERTY)
        PublishType snapshotPublishType = snapshotPublishTypeProperty != null ? PublishType.valueOf(snapshotPublishTypeProperty as String) : PublishType.MAVEN_PUBLISH
        PublishType releasePublishType = releasePublishTypeProperty != null ? PublishType.valueOf(releasePublishTypeProperty as String) : PublishType.NEXUS_PUBLISH

        boolean isSnapshot, isRelease
        if (System.getenv(ENVIRONMENT_VARIABLE_BASED_RELEASE) != null) {
            // Detect release state based on environment variables instead of versions
            isRelease = Boolean.parseBoolean(System.getenv(ENVIRONMENT_VARIABLE_BASED_RELEASE))
            isSnapshot = !isRelease

            LOG.lifecycle('Environment Variable `{}` detected - using variable instead of project version.', ENVIRONMENT_VARIABLE_BASED_RELEASE)
        } else {
            String detectedVersion = (project.version == Project.DEFAULT_VERSION ? (findProjectProperty(project, 'projectVersion', true) ?: Project.DEFAULT_VERSION) : project.version) as String
            if (detectedVersion == Project.DEFAULT_VERSION) {
                throw new InvalidUserDataException("Project ${project.name} has an unspecified version (neither `version` or the property `projectVersion` is defined). Release state cannot be determined.")
            }
            LOG.info('Version {} detected for project {}', detectedVersion, project.name)

            isSnapshot = detectedVersion.endsWith('SNAPSHOT')
            isRelease = !isSnapshot

            if (project.version == Project.DEFAULT_VERSION) {
                if (isRelease) {
                    LOG.warn('Project {} does not have a version defined. Using the gradle property `projectVersion` to assume version is {}.', project.name, detectedVersion)
                } else {
                    LOG.info('Project {} does not have a version defined. Using the gradle property `projectVersion` to assume version is {}.', project.name, detectedVersion)
                }
            }
        }

        if (isSnapshot) {
            LOG.info('Project {} will be a snapshot.', project.name)
        }
        if (isRelease) {
            LOG.info('Project {} will be a release.', project.name)
        }

        boolean useMavenPublish = (isSnapshot && snapshotPublishType == PublishType.MAVEN_PUBLISH) || (isRelease && releasePublishType == PublishType.MAVEN_PUBLISH)
        if (useMavenPublish) {
            LOG.info('Maven Publish is enabled for project {}', project.name)
        }
        boolean useNexusPublish = (isSnapshot && snapshotPublishType == PublishType.NEXUS_PUBLISH) || (isRelease && releasePublishType == PublishType.NEXUS_PUBLISH)
        if (useNexusPublish) {
            LOG.info('Nexus Publish is enabled for project {}', project.name)
        }

        // Required for the pom always
        final PluginManager projectPluginManager = project.pluginManager
        projectPluginManager.apply(MavenPublishPlugin)

        boolean localSigning = false
        String signingKeyId = findProjectProperty(project, 'signing.keyId') ?: System.getenv('SIGNING_KEY')
        if (isRelease) {
            LOG.lifecycle('Signing is enabled due to release configuration.')
            extraPropertiesExtension.set('signing.keyId', signingKeyId)
            String secringFile = findProjectProperty(project, 'signing.secretKeyRingFile') ?: System.getenv('SIGNING_KEYRING')
            if (!secringFile) {
                LOG.lifecycle('No keyring file (SIGNING_KEYRING) has been specified. Assuming the use of local gpgCommand to sign instead.')
                localSigning = true
                extraPropertiesExtension.set('signing.gnupg.keyName', signingKeyId)
            } else {
                LOG.lifecycle('Keyring file has been specified. Using java to sign.')
                extraPropertiesExtension.set('signing.secretKeyRingFile', secringFile)

                String signingPassphrase = findProjectProperty(project, 'signing.password') ?: System.getenv('SIGNING_PASSPHRASE')
                if (signingPassphrase) {
                    extraPropertiesExtension.set('signing.password', signingPassphrase)
                }
            }
        }

        if (isRelease || useNexusPublish) {
            if (project.pluginManager.hasPlugin(SIGNING_PLUGIN_ID)) {
                LOG.debug('Signing Plugin already applied to project {}', project.name)
            } else {
                projectPluginManager.apply(SigningPlugin)
            }

            project.tasks.withType(Sign).configureEach { Sign task ->
                task.onlyIf { isRelease }
            }
        }


        if (useNexusPublish) {
            // only read by builds publishing through Nexus
            final String nexusPublishUrl = findProjectProperty(project, 'nexusPublishUrl') ?: System.getenv('NEXUS_PUBLISH_URL') ?: ''
            final String nexusPublishSnapshotUrl = findProjectProperty(project, 'nexusPublishSnapshotUrl') ?: System.getenv('NEXUS_PUBLISH_SNAPSHOT_URL') ?: ''
            final String nexusPublishUsername = findProjectProperty(project, 'nexusPublishUsername') ?: System.getenv('NEXUS_PUBLISH_USERNAME') ?: ''
            final String nexusPublishPassword = findProjectProperty(project, 'nexusPublishPassword') ?: System.getenv('NEXUS_PUBLISH_PASSWORD') ?: ''
            final String nexusPublishStagingProfileId = findProjectProperty(project, 'nexusPublishStagingProfileId') ?: System.getenv('NEXUS_PUBLISH_STAGING_PROFILE_ID') ?: ''
            final String nexusPublishDescription = findProjectProperty(project, 'nexusPublishDescription') ?: System.getenv('NEXUS_PUBLISH_DESCRIPTION') ?: ''

            // The nexus plugin is special since it must always be applied to the root project.
            // Handle when multiple subprojects exist and grailsPublish is defined in each one instead of at the root.
            final PluginManager rootProjectPluginManager = project.rootProject.pluginManager
            boolean hasNexusPublishApplied = rootProjectPluginManager.hasPlugin(NEXUS_PUBLISH_PLUGIN_ID)
            if (hasNexusPublishApplied) {
                LOG.debug('Nexus Publish Plugin already applied to root project')
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

                final def mavenPublishUrl = findProjectProperty(project, 'mavenPublishUrl') ?: System.getenv('MAVEN_PUBLISH_URL')
                if (useMavenPublish) {
                    System.setProperty('org.gradle.internal.publish.checksums.insecure', true as String)

                    pe.repositories { RepositoryHandler repoHandler ->
                        repoHandler.maven { MavenArtifactRepository repo ->
                            final String mavenPublishUsername = findProjectProperty(project, 'mavenPublishUsername') ?: System.getenv('MAVEN_PUBLISH_USERNAME')
                            final String mavenPublishPassword = findProjectProperty(project, 'mavenPublishPassword') ?: System.getenv('MAVEN_PUBLISH_PASSWORD')
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
                    for (AdditionalPublication additional : gpe.additionalPublications) {
                        if (additional.name == gpe.publicationName.get()) {
                            throw new InvalidUserDataException("Additional publication `${additional.name}` conflicts with the primary publication name. Rename one of the publications.")
                        }
                    }

                    List<String> primaryConfigurations = ['compileClasspath', 'runtimeClasspath',
                                                          'testFixturesCompileClasspath', 'testFixturesRuntimeClasspath']
                    Action<MavenPublication> configurePrimaryPublication = { MavenPublication publication ->
                        publication.artifactId = gpe.artifactId.get()
                        publication.groupId = gpe.groupId.get()

                        if (gpe.addComponents.get()) {
                            doAddArtefact(project, publication)
                            addExtraArtifact(project, gpe, publication)

                            configureVersionMapping(project, publication, 'runtimeClasspath')
                        }

                        configurePom(project, gpe, publication, gpe.title, gpe.desc, gpe.pomCustomization, primaryConfigurations)
                    } as Action<MavenPublication>

                    // the java-gradle-plugin creates its pluginMaven publication itself when it is applied before this
                    // plugin, so configure that one instead of failing on the duplicate name
                    String primaryPublicationName = gpe.publicationName.get()
                    MavenPublication existingPublication = isComponentAddedByJavaGradlePlugin(project, primaryPublicationName) ?
                            publications.findByName(primaryPublicationName) as MavenPublication : null
                    if (existingPublication) {
                        configurePrimaryPublication.execute(existingPublication)
                    } else {
                        publications.create(primaryPublicationName, MavenPublication, configurePrimaryPublication)
                    }
                    configureModuleMetadataVersions(project, gpe, primaryPublicationName, primaryConfigurations)

                    for (AdditionalPublication additional : gpe.additionalPublications) {
                        publications.create(additional.name, MavenPublication) { MavenPublication publication ->
                            publication.artifactId = additional.artifactId.get()
                            publication.groupId = gpe.groupId.get()

                            if (gpe.addComponents.get()) {
                                String componentName = additional.componentName.get()
                                def component = project.components.findByName(componentName)
                                if (component == null) {
                                    throw new InvalidUserDataException("Additional publication `${additional.name}` of project `${project.name}` requires a software component named `${componentName}`, but none exists. Create the component (e.g. via SoftwareComponentFactory.adhoc) before the project is evaluated, or set `componentName` to an existing component.")
                                }
                                // check once configuration is complete, since reading the component's variants locks
                                // it against changes made later, such as in another afterEvaluate block
                                project.gradle.taskGraph.whenReady {
                                    requireNoDirectoryArtifacts(project, additional.name, component)
                                }
                                publication.from(component)
                                attachDocsJars(project, publication, additional)

                                configureVersionMapping(project, publication, additional.runtimeClasspathName.get())
                            }

                            configurePom(project, gpe, publication, additional.title, additional.desc, additional.pomCustomization,
                                    [additional.compileClasspathName.get(), additional.runtimeClasspathName.get()])
                        }
                        configureModuleMetadataVersions(project, gpe, additional.name,
                                [additional.compileClasspathName.get(), additional.runtimeClasspathName.get()])
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

                project.tasks.withType(Sign).configureEach {
                    it.doFirst {
                        if (!signingKeyId) {
                            throw new InvalidUserDataException('A signing key is required to sign a release. Set GRAILS_PUBLISH_RELEASE=false to bypass signing.')
                        }
                    }
                }
                // When publications share an artifact, their sign tasks write the same signature file, and Gradle rejects a
                // publish task reading another publication's signature without an ordering, see
                // https://github.com/gradle/gradle/issues/26091. The plugin's own publications no longer share artifacts,
                // but a build can still add one to several publications. This only orders the tasks and adds no work.
                project.tasks.withType(AbstractPublishToMaven).configureEach {
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

    /**
     * A component built with addVariantsFromConfiguration includes the class and resource directory variants of the
     * configuration unless they are skipped. Maven publishing silently leaves the directories out, publishing variants
     * without files, and signing fails on them, so fail early with a message explaining the fix.
     */
    static void requireNoDirectoryArtifacts(Project project, String publicationName, SoftwareComponent component) {
        if (!(component instanceof SoftwareComponentInternal)) {
            return
        }
        for (SoftwareComponentVariant variant : ((SoftwareComponentInternal) component).usages) {
            PublishArtifact directory = variant.artifacts.find { PublishArtifact artifact -> artifact.type in DIRECTORY_ARTIFACT_TYPES }
            if (directory) {
                throw new InvalidUserDataException("Publication `${publicationName}` of ${project} contains the directory " +
                        "`${project.relativePath(directory.file)}` from variant `${variant.name}` of component `${component.name}`, " +
                        'which cannot be published. When adding variants with addVariantsFromConfiguration, skip the variants ' +
                        "whose artifact type is one of ${DIRECTORY_ARTIFACT_TYPES.join(', ')}, as the java component does.")
            }
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

    /**
     * Configures the pom metadata shared by every publication (primary and additional) from the
     * extension, with the title, description, and pom customization supplied per publication.
     */
    protected void configurePom(Project project, GrailsPublishExtension gpe, MavenPublication publication,
                                Provider<String> title, Provider<String> desc, Provider<Closure> pomCustomization,
                                List<String> versionResolutionConfigurations) {
        publication.pom { MavenPom pom ->
            pom.name.set(title.get())
            pom.description.set(desc.get())
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
            def concreteLicense = License.LICENSES.get(license?.name)
            if (concreteLicense) {
                pom.licenses { MavenPomLicenseSpec licenses ->
                    licenses.license { MavenPomLicense pomLicense ->
                        pomLicense.name.set(concreteLicense.name)
                        pomLicense.url.set(concreteLicense.url)
                        pomLicense.distribution.set(concreteLicense.distribution)
                    }
                }
            } else if (license?.name && license?.url) {
                pom.licenses { MavenPomLicenseSpec licenses ->
                    licenses.license { MavenPomLicense pomLicense ->
                        pomLicense.name.set(license.name)
                        pomLicense.url.set(license.url)
                        pomLicense.distribution.set(license.distribution)
                    }
                }
            } else {
                // a known license name, or an explicit name + url pair, is required so the
                // published pom always carries a <licenses> section
                throw new InvalidUserDataException(createErrorMessage('license'))
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

            List<MavenPomDeveloper> developers = gpe.developers.getOrElse([])
            if (developers) {
                pom.developers { MavenPomDeveloperSpec devs ->
                    for (MavenPomDeveloper source : developers) {
                        devs.developer { MavenPomDeveloper target ->
                            cloneDeveloper(source, target)
                        }
                    }
                }
            } else {
                throw new InvalidUserDataException(createErrorMessage('developers'))
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

                if (pomCustomization.isPresent()) {
                    Closure customization = pomCustomization.get()
                    customization.delegate = pom
                    customization.resolveStrategy = Closure.DELEGATE_FIRST
                    customization.call(xml)
                }

                // fix dependencies without a version, this can occur when the spring dependency management plugin is used
                // disabling that plugin will cause gradle to fail on any unresolved, or by disabling the check with:
                // https://github.com/gradle/gradle/issues/23030
                //tasks.withType(GenerateModuleMetadata).configureEach {
                //    suppressedValidationErrors.add('dependencies-without-versions')
                //}
                if (gpe.transitiveDependencies.get()) {
                    setDependencyVersions(pomNode, project, versionResolutionConfigurations)
                }
            }
        }
    }

    /**
     * Ensure Gradle module metadata includes resolved versions for all dependencies. Without this,
     * dependencies declared without an explicit version (relying on a platform/BOM) are published
     * with no version in the .module file, causing resolution failures for consumers since Gradle
     * prefers .module over .pom.
     */
    protected static void configureVersionMapping(Project project, MavenPublication publication, String runtimeClasspathName) {
        if (!project.extensions.findByType(JavaPlatformExtension)) {
            publication.versionMapping { strategy ->
                strategy.usage('java-api') { variant ->
                    variant.fromResolutionOf(runtimeClasspathName)
                }
                strategy.usage('java-runtime') { variant ->
                    variant.fromResolutionResult()
                }
            }
        }
    }

    /**
     * Creates (or reuses) the sources and javadoc jars for an additional publication's source set
     * and attaches them to the publication with the `sources`/`javadoc` classifiers required by
     * Maven Central. The additional publication's software component must not already contain
     * sources or javadoc variants.
     */
    protected void attachDocsJars(Project project, MavenPublication publication, AdditionalPublication additional) {
        SourceSetContainer sourceSets = findSourceSets(project)
        String sourceSetName = additional.sourceSetName.get()
        SourceSet sourceSet = sourceSets.findByName(sourceSetName)
        if (sourceSet == null) {
            throw new InvalidUserDataException("Additional publication `${additional.name}` of project `${project.name}` requires source set `${sourceSetName}` to build its sources and javadoc jars, but it does not exist. Set `sourceSetName` if the sources live in a differently named source set.")
        }

        TaskContainer tasks = project.tasks

        TaskProvider<Jar> sourcesJarTask
        if (tasks.names.contains(sourceSet.sourcesJarTaskName)) {
            sourcesJarTask = tasks.named(sourceSet.sourcesJarTaskName, Jar)
        } else {
            sourcesJarTask = tasks.register(sourceSet.sourcesJarTaskName, Jar) { Jar jar ->
                configureReproducibleJar(jar)
                jar.group = BUILD_GROUP
                jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                jar.archiveBaseName.set(additional.artifactId)
                jar.archiveClassifier.set('sources')
                jar.from(sourceSet.allSource)
            }
        }
        publication.artifact(sourcesJarTask) { MavenArtifact artifact ->
            artifact.classifier = 'sources'
        }

        TaskProvider<Jar> javadocJarTask
        if (tasks.names.contains(sourceSet.javadocJarTaskName)) {
            javadocJarTask = tasks.named(sourceSet.javadocJarTaskName, Jar)
        } else {
            TaskProvider<? extends Task> docTask = registerDocTask(project, sourceSet)
            javadocJarTask = tasks.register(sourceSet.javadocJarTaskName, Jar) { Jar jar ->
                configureReproducibleJar(jar)
                jar.group = BUILD_GROUP
                jar.archiveBaseName.set(additional.artifactId)
                jar.archiveClassifier.set('javadoc')
                jar.from(docTask)
            }
        }
        publication.artifact(javadocJarTask) { MavenArtifact artifact ->
            artifact.classifier = 'javadoc'
        }
    }

    /**
     * Registers the documentation task backing an additional publication's javadoc jar —
     * groovydoc when the source set has Groovy sources (matching how the primary javadoc jar
     * packages groovydoc), plain javadoc otherwise.
     */
    private TaskProvider<? extends Task> registerDocTask(Project project, SourceSet sourceSet) {
        TaskContainer tasks = project.tasks
        def groovySources = sourceSet.extensions.findByType(GroovySourceDirectorySet)
        if (groovySources != null) {
            String taskName = "${sourceSet.name}Groovydoc"
            if (tasks.names.contains(taskName)) {
                return tasks.named(taskName)
            }
            return tasks.register(taskName, Groovydoc) { Groovydoc groovydoc ->
                groovydoc.source(groovySources)
                // Ensure the java sources are included in the groovydoc, matching the primary javadoc jar
                groovydoc.source(project.files(sourceSet.java.srcDirs))
                groovydoc.classpath = sourceSet.compileClasspath
                groovydoc.destinationDir = project.layout.buildDirectory.dir("docs/${taskName}").get().asFile
            }
        }

        String taskName = sourceSet.javadocTaskName
        if (tasks.names.contains(taskName)) {
            return tasks.named(taskName)
        }
        return tasks.register(taskName, Javadoc) { Javadoc javadoc ->
            javadoc.source = sourceSet.allJava
            javadoc.classpath = sourceSet.compileClasspath.plus(sourceSet.output)
            javadoc.destinationDir = project.layout.buildDirectory.dir("docs/${taskName}").get().asFile
        }
    }

    protected static void configureReproducibleJar(Jar jar) {
        jar.reproducibleFileOrder = true
        jar.preserveFileTimestamps = false
        // to avoid platform specific defaults, set the permissions consistently
        jar.filePermissions { permissions ->
            permissions.unix(0644)
        }
        jar.dirPermissions { permissions ->
            permissions.unix(0755)
        }
    }

    protected void setDependencyVersions(Node pomNode, Project project, List<String> configurationNames) {
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
        for (String configurationName : configurationNames) {
            def configuration = project.configurations.findByName(configurationName)
            if (configuration != null) {
                resolvedArtifacts.addAll(configuration.resolvedConfiguration.resolvedArtifacts)
            }
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
                throw new InvalidUserDataException("No version found for dependency $groupId:$artifactId.")
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
            if (gpe.additionalPublications) {
                throw new InvalidUserDataException('Additional publications are not supported for BOM publishes.')
            }
            publication.from(project.components.named('javaPlatform').get())

            if (gpe.publishTestSources.get()) {
                throw new InvalidUserDataException('BOM publishes may only contain dependencies.')
            }

            return
        }

        def javaComponent = project.components.named('java').get()
        if (isComponentAddedByJavaGradlePlugin(project, publication.name)) {
            if (gpe.additionalPublications) {
                throw new InvalidUserDataException("Additional publications are not supported for project `${project.name}`, " +
                        "since the java-gradle-plugin adds the java component to its `${publication.name}` publication, and " +
                        'additional publications require this plugin to add a component that links to them.')
            }
            // the java-gradle-plugin adds the java component itself
        } else if (gpe.additionalPublications) {
            if (!(javaComponent instanceof SoftwareComponentInternal)) {
                throw new GradleException("Additional publications require the `java` component to implement SoftwareComponentInternal, but it is a ${javaComponent.class.name}. This Gradle version is not supported for additional publications.")
            }
            // Declare the additional publications' components as children of the primary
            // component so the project's published components form a single tree — the only
            // shape Gradle can resolve a project dependency against when one project publishes
            // multiple coordinates (see GrailsRootSoftwareComponent).
            List<SoftwareComponent> childComponents = gpe.additionalPublications.collect { AdditionalPublication additional ->
                String componentName = additional.componentName.get()
                SoftwareComponent component = project.components.findByName(componentName)
                if (component == null) {
                    throw new InvalidUserDataException("Additional publication `${additional.name}` of project `${project.name}` requires a software component named `${componentName}`, but none exists. Create the component (e.g. via SoftwareComponentFactory.adhoc) before the project is evaluated, or set `componentName` to an existing component.")
                }
                component
            }
            publication.from(new GrailsRootSoftwareComponent((SoftwareComponentInternal) javaComponent, childComponents))
        } else {
            publication.from(javaComponent)
        }

        if (gpe.publishTestSources.get()) {
            publication.artifact(project.tasks.named('testSourcesJar', Jar))
        }
    }

    /**
     * Whether the java-gradle-plugin adds the java component to the publication, which it does for its pluginMaven
     * publication unless its automated publishing is disabled.
     */
    protected static boolean isComponentAddedByJavaGradlePlugin(Project project, String publicationName) {
        GradlePluginDevelopmentExtension gradlePlugin = project.extensions.findByType(GradlePluginDevelopmentExtension)
        publicationName == 'pluginMaven' && gradlePlugin != null && gradlePlugin.automatedPublishing
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

    /**
     * The descriptor the Grails compiler writes while compiling the main Groovy sources of a Grails plugin project.
     * The compiler only writes it when it compiles a (non-abstract) plugin class, i.e. a class named
     * {@code *GrailsPlugin}, so the default is present only when the Grails plugin Gradle plugin is applied and
     * such a Groovy source exists; a project applying the plugin for other reasons (grails-gsp-spring-boot in
     * grails-core, for instance) publishes no descriptor. The provider is derived from the compile task's output
     * directory (not from a mapped task output, which Gradle refuses to query before the task ran) and carries the
     * task as its producer, so publishing builds the descriptor first.
     */
    protected Provider<RegularFile> defaultPluginDescriptor(Project project) {
        project.provider {
            GRAILS_PLUGIN_IDS.any { String id -> project.pluginManager.hasPlugin(id) } &&
                    project.tasks.names.contains(MAIN_GROOVY_COMPILE_TASK) &&
                    hasGrailsPluginClass(project)
        }.flatMap { Boolean grailsPlugin ->
            grailsPlugin ?
                    project.tasks.named(MAIN_GROOVY_COMPILE_TASK, GroovyCompile).flatMap { GroovyCompile compile ->
                        compile.destinationDirectory.file(PLUGIN_DESCRIPTOR_PATH)
                    } :
                    project.provider { (RegularFile) null }
        }
    }

    /** Whether the main Groovy sources declare a plugin descriptor class ({@code *GrailsPlugin.groovy}) */
    private static boolean hasGrailsPluginClass(Project project) {
        JavaPluginExtension java = project.extensions.findByType(JavaPluginExtension)
        SourceSet main = java?.sourceSets?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
        GroovySourceDirectorySet groovy = main?.extensions?.findByType(GroovySourceDirectorySet)
        groovy != null && !groovy.matching { PatternFilterable pattern -> pattern.include('**/*GrailsPlugin.groovy') }.isEmpty()
    }

    /**
     * Attaches the extra artifact of the primary publication: the configured plugin descriptor, built by whatever
     * produces it, or (for builds that create the descriptor some other way) a descriptor that already exists.
     */
    protected void addExtraArtifact(Project project, GrailsPublishExtension gpe, MavenPublication publication) {
        if (project.extensions.findByType(JavaPlatformExtension)) {
            return
        }
        if (gpe.pluginDescriptor.isPresent()) {
            publishExtraArtifactCopy(project, publication, gpe.pluginDescriptor, 'grails-plugin.xml', getDefaultClassifier(), 'xml')
            return
        }

        Map<String, String> extraArtifact = getDefaultExtraArtifact(project)
        if (extraArtifact) {
            File source = new File(extraArtifact.source)
            publishExtraArtifactCopy(project, publication, source, source.name, extraArtifact.classifier, extraArtifact.extension)
        }
    }

    /**
     * The extra artifact usually lives in a classes directory, such as META-INF/grails-plugin.xml. Publishing it from
     * there would write its signature into that directory, which other tasks read, such as the jar tasks, so publish a
     * copy instead. The source may be a file or a provider carrying the task that produces it.
     */
    private static void publishExtraArtifactCopy(Project project, MavenPublication publication, Object source, String fileName, String classifier, String extension) {
        TaskProvider<Copy> copyTask = project.tasks.register('grailsPublishExtraArtifact', Copy) { Copy copy ->
            copy.from(source, { CopySpec spec ->
                spec.rename { String name -> fileName }
            } as Action<? super CopySpec>)
            copy.into(project.layout.buildDirectory.dir('grails-publish/extra-artifact'))
            // the file is written while building the main classes: grails-plugin.xml by compileGroovy, and
            // grails-core's profile.yml by compileProfile, which its profile plugin adds to `classes`
            copy.dependsOn(project.tasks.named('classes'))
        }
        Provider<File> copied = copyTask.map { Copy copy -> new File(copy.destinationDir, fileName) }
        publication.artifact(copied) { MavenArtifact artifact ->
            artifact.classifier = classifier
            artifact.extension = extension
            artifact.builtBy(copyTask)
        }
    }

    /**
     * Gradle module metadata is generated from version mapping, which resolves every `java-api` variant from the
     * main runtime classpath. Dependencies of other variants that are managed by a platform and absent from that
     * classpath (test fixtures, for example) are therefore written without a version. Fill them in from the same
     * resolved classpaths the pom is completed from.
     */
    protected void configureModuleMetadataVersions(Project project, GrailsPublishExtension gpe, String publicationName, List<String> configurationNames) {
        project.tasks.withType(GenerateModuleMetadata).configureEach { GenerateModuleMetadata task ->
            if (task.publication.orNull?.name != publicationName) {
                return
            }
            task.doLast {
                if (!gpe.transitiveDependencies.get()) {
                    return
                }
                File moduleFile = task.outputFile.get().asFile
                Map<String, String> resolvedVersions = resolvedVersions(project, configurationNames)
                Map module = new JsonSlurper().parse(moduleFile, 'UTF-8') as Map
                boolean changed = false
                for (Map variant : (module.variants ?: []) as List<Map>) {
                    for (Map dependency : (variant.dependencies ?: []) as List<Map>) {
                        Map version = dependency.version as Map
                        if (version?.requires || version?.strictly || version?.prefers) {
                            continue
                        }
                        String resolved = resolvedVersions["${dependency.group}:${dependency.module}" as String]
                        if (resolved == null) {
                            throw new InvalidUserDataException("No version found for dependency ${dependency.group}:${dependency.module} of variant ${variant.name} in the module metadata of publication ${publicationName}.")
                        }
                        dependency.version = [requires: resolved]
                        changed = true
                    }
                }
                if (changed) {
                    moduleFile.setText(JsonOutput.prettyPrint(JsonOutput.toJson(module)), 'UTF-8')
                }
            }
        }
    }

    /** The versions of the artifacts resolved by the named configurations, keyed by `group:name` */
    protected static Map<String, String> resolvedVersions(Project project, List<String> configurationNames) {
        Map<String, String> versions = [:]
        for (String configurationName : configurationNames) {
            def configuration = project.configurations.findByName(configurationName)
            if (configuration != null) {
                for (ResolvedArtifact artifact : configuration.resolvedConfiguration.resolvedArtifacts) {
                    versions.putIfAbsent("${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}" as String, artifact.moduleVersion.id.version)
                }
            }
        }
        versions
    }

    protected String getDefaultClassifier() {
        'plugin'
    }

    protected validateProjectPublishable(Project project) {
        def javaPlugin = project.extensions.findByType(JavaPluginExtension)
        def javaPlatform = project.extensions.findByType(JavaPlatformExtension)

        if (!javaPlugin && !javaPlatform) {
            throw new InvalidUserCodeException('Grails Publish Plugin requires the Java Platform or Java Plugin to be applied to the project.')
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
                LOG.info('Configuring javadocJar task for project {} to include groovydoc', project.name)
                it.enabled = false
            }
        }

        tasks.named('javadocJar', Jar).configure { Jar jar ->
            configureReproducibleJar(jar)

            Groovydoc groovyDocTask = tasks.findByName('groovydoc') as Groovydoc
            if (groovyDocTask) {
                jar.dependsOn(groovyDocTask)

                // Ensure the java source set is included in the groovydoc source set
                SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
                groovyDocTask.source(project.files(sourceSets.named('main').get().java.srcDirs))

                // Read the destination lazily so the jar follows a `groovydoc.destinationDir` that is
                // retargeted after this block has configured the jar
                Provider<File> groovyDocDir = project.provider { groovyDocTask.destinationDir }
                ConfigurableFileCollection groovyDocFiles = project.files(groovyDocDir)
                jar.from(groovyDocFiles)

                // The `javadoc` task's output is wired into this jar by `withJavadocJar()` - either the
                // call above, or one the consumer made in their own build script before applying this
                // plugin - and that wiring cannot be removed afterwards. Keeping the standard tasks in
                // place is deliberate, since it is what lets the rest of Gradle depend on them; only the
                // jar's content is swapped for the groovydoc. But `javadoc` is disabled above, and a
                // disabled task never cleans its output directory, so anything an earlier build left in
                // `build/docs/javadoc` would still be packaged next to the groovydoc - shipping stale
                // pages, and failing the jar outright for consumers that set `DuplicatesStrategy.FAIL`.
                //
                // A javadoc task that does not run has nothing to contribute, so its contribution is
                // dropped and the groovydoc replaces it outright. What is dropped is the javadoc tree's
                // own view of a file, not every file that happens to sit under its destination: within
                // that tree an element's path is its path below `javadoc.destinationDir`, whereas the
                // groovydoc `from()` above presents its files at its own root. Comparing the two is what
                // keeps this exact even when one destination contains the other - excluding by location
                // alone would either take the groovydoc down with the javadoc and leave an empty jar, or
                // package it twice. Read lazily, because `javadoc` is disabled after this block has
                // configured the jar.
                TaskProvider<Task> javadocTask = tasks.named('javadoc')
                Provider<Boolean> javadocRuns = project.provider { javadocTask.get().enabled }
                Provider<File> javadocDir = project.provider {
                    Task task = javadocTask.get()
                    task instanceof Javadoc ? ((Javadoc) task).destinationDir : null
                }
                jar.exclude { FileTreeElement element ->
                    if (javadocRuns.get()) {
                        return false
                    }
                    File javadocDestination = javadocDir.orNull
                    if (javadocDestination == null) {
                        return false
                    }
                    Path javadocRoot = javadocDestination.absoluteFile.toPath().normalize()
                    Path candidate = element.file.absoluteFile.toPath().normalize()
                    return candidate == javadocRoot.resolve(element.relativePath.pathString)
                }
            }
        }

        tasks.named('sourcesJar', Jar).configure { Jar jar ->
            SourceSetContainer sourceSets = GrailsPublishGradlePlugin.findSourceSets(project)
            configureReproducibleJar(jar)
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

            // don't only include main, but any source set — except source sets that are
            // published separately by an additional publication with their own sources jar
            GrailsPublishExtension gpe = project.extensions.getByType(GrailsPublishExtension)
            Set<String> additionalPublicationSourceSets = gpe.additionalPublications
                    .collect { it.sourceSetName.get() } as Set<String>
            Collection<SourceSet> publishedSourceSets = sourceSets.matching { SourceSet sourceSet ->
                !(sourceSet.name in additionalPublicationSourceSets)
            }
            jar.from publishedSourceSets.collect { it.allSource }
        }

        project.tasks.register('testSourcesJar', Jar).configure { Jar jar ->
            // capture the provider at configuration time, the project is not available to onlyIf with the configuration cache
            Provider<Boolean> publishTestSources = project.extensions.getByType(GrailsPublishExtension).publishTestSources
            jar.onlyIf { Task task ->
                publishTestSources.get() && !(task as Jar).source.files.isEmpty()
            }
            jar.dependsOn('testClasses')
            configureReproducibleJar(jar)
            SourceSetContainer sourceSets = GrailsPublishGradlePlugin.findSourceSets(project)
            def testSourceSet = sourceSets.named('test').get()
            jar.from(testSourceSet.output)
            jar.archiveClassifier.set('tests')
            jar.group = BUILD_GROUP
        }

        // TODO: Revisit this as an optional feature instead of forced, see @PendingFeature test case
        // it's valid to publish boms, profiles, and projects that export only dependencies without any code
        // so for now remove this and let the maven publish plugin fail if conditions aren't met
//        SourceSetContainer sourceSets = findSourceSets(project)
//        Collection<SourceSet> publishedSources = sourceSets.matching { SourceSet sourceSet ->
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
                    throw new InvalidUserDataException('Groovydoc task is disabled. Please enable it to ensure javadoc can be published correctly with the Grails Publish Plugin.')
                }
            }
        }
    }
}

