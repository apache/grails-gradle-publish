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
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * An additional publication published alongside the primary publication under its own Maven
 * coordinate with its own dependency graph — for example a companion `-cli` artifact built from
 * a dedicated source set of the same project.
 */
@CompileStatic
class AdditionalPublication {

    /**
     * The name of the Gradle publication; must be unique within the project
     */
    final String name

    /**
     * The artifactId of the published artifact, defaults to "${project.name}-${name}"
     */
    final Property<String> artifactId

    /**
     * The software component to publish; the component must exist by the time the project is
     * evaluated (e.g. created via SoftwareComponentFactory.adhoc). Defaults to the publication name.
     */
    final Property<String> componentName

    /**
     * The source set the publication is built from; used to create the sources & javadoc jars.
     * Defaults to the publication name.
     */
    final Property<String> sourceSetName

    /**
     * The compile classpath configuration used to resolve dependency versions in the pom;
     * derived from the source set name by default
     */
    final Property<String> compileClasspathName

    /**
     * The runtime classpath configuration used for version mapping and to resolve dependency
     * versions in the pom; derived from the source set name by default
     */
    final Property<String> runtimeClasspathName

    /**
     * Title of the publication, defaults to the primary publication's title
     */
    final Property<String> title

    /**
     * Description of the publication, defaults to the primary publication's description
     */
    final Property<String> desc

    /**
     * An optional closure to be invoked via pom.withXml { } to allow further customization of
     * this publication's pom
     */
    final Property<Closure> pomCustomization

    AdditionalPublication(String name, ObjectFactory objects, Project project, GrailsPublishExtension extension) {
        this.name = name
        artifactId = objects.property(String).convention(project.provider {
            "${project.name}-${name}" as String
        })
        componentName = objects.property(String).convention(name)
        sourceSetName = objects.property(String).convention(name)
        compileClasspathName = objects.property(String).convention(sourceSetName.map { String sourceSet ->
            sourceSet + 'CompileClasspath'
        })
        runtimeClasspathName = objects.property(String).convention(sourceSetName.map { String sourceSet ->
            sourceSet + 'RuntimeClasspath'
        })
        title = objects.property(String).convention(extension.title)
        desc = objects.property(String).convention(extension.desc)
        pomCustomization = objects.property(Closure).convention(null as Closure)
    }
}
