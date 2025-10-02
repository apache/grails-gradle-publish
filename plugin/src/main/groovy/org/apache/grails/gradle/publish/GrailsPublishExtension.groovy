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

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

import javax.inject.Inject

@CompileStatic
class GrailsPublishExtension {

    /**
     * The organization that produces the project
     */
    @Nested
    final Organization organization

    /**
     * The slug from github
     */
    final Property<String> githubSlug

    /**
     * The website URL of the published project; defaulted by the github slug if not set
     */
    final Property<String> websiteUrl

    /**
     * The SCM url, defaults based on the github slug to https://github.com/${githubSlug}
     */
    final Property<String> scmUrl

    /**
     * The url to connect via SCM tool, defaults based on the github slug to "scm:git@github.com:${githubSlug}.git"
     */
    final Property<String> scmUrlConnection

    /**
     * The source control URL of the project
     */
    final Property<String> vcsUrl

    /**
     * The license of the plugin
     */
    License license = new License()

    /**
     * The developers of the project
     */
    final MapProperty<String, String> developers

    /**
     * Title of the project, defaults to the project name
     */
    final Property<String> title

    /**
     * Description of the plugin
     */
    final Property<String> desc

    /**
     * The issue tracker name; github if github slug is set and not overridden
     */
    final Property<String> issueTrackerName

    /**
     * The issue tracker URL
     */
    final Property<String> issueTrackerUrl

    /**
     * Overrides the artifactId of the published artifact
     */
    final Property<String> artifactId

    /**
     * Overrides the groupId of the published artifact
     */
    final Property<String> groupId

    /**
     * Whether to publish test sources with a "tests" classifier
     */
    final Property<Boolean> publishTestSources

    /**
     * An optional closure to be invoked via pom.withXml { } to allow further customization
     */
    Closure pomCustomization

    /**
     * If another process will add the components set this to false so only the publication is created
     */
    final Property<Boolean> addComponents

    /**
     * The name of the publication
     */
    final Property<String> publicationName

    /**
     * If set, a local repository will be setup for the given path with the name 'TestCaseMavenRepo'. This can be useful
     * when testing plugins locally with builds that can't make use of includedBuild
     */
    final Property<Directory> testRepositoryPath

    /**
     * Validate that any transitive dependency has a version set in the pom & try to set it if not defined
     */
    final Property<Boolean> transitiveDependencies

    @Inject
    GrailsPublishExtension(ObjectFactory objects, Project project) {
        githubSlug = objects.property(String).convention(
                project.provider {
                    project.findProperty('githubSlug') as String
                }
        )
        websiteUrl = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? "https://github.com/$githubSlug" as String : null
        })
        scmUrl = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? "https://github.com/$githubSlug" as String : null
        })
        scmUrlConnection = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? "scm:git@github.com:${githubSlug}.git" as String : null
        })
        vcsUrl = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? "git@github.com:${githubSlug}.git" as String : null
        })
        developers = objects.mapProperty(String, String).convention([:])
        title = objects.property(String).convention(project.provider { project.name })
        desc = objects.property(String).convention(project.provider {
            title.getOrNull()
        })
        issueTrackerName = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? 'GitHub Issues' : 'Issues'
        })
        issueTrackerUrl = objects.property(String).convention(project.provider {
            String githubSlug = githubSlug.getOrNull()
            githubSlug ? "https://github.com/$githubSlug/issues" as String : null
        })
        artifactId = objects.property(String).convention(project.provider {
            project.name
        })
        groupId = objects.property(String).convention(project.provider {
            project.group as String
        })
        publishTestSources = objects.property(Boolean).convention(false)
        testRepositoryPath = objects.directoryProperty().convention(null as Directory)
        addComponents = objects.property(Boolean).convention(true)
        publicationName = objects.property(String).convention('maven')
        transitiveDependencies = objects.property(Boolean).convention(true)
        organization = objects.newInstance(Organization)
    }

    void organization(Action<? super Organization> action) {
        action.execute(organization)
    }

    License getLicense() {
        return license
    }

    /**
     * Configures the license
     *
     * @param configurer The configurer
     * @return the license instance
     */
    License license(@DelegatesTo(License) Closure configurer) {
        configurer.delegate = license
        configurer.resolveStrategy = Closure.DELEGATE_FIRST
        configurer.call()
        return license
    }

    void setLicense(License license) {
        this.license = license
    }

    void setLicense(String license) {
        this.license.name = license
    }

}

