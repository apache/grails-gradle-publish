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

package org.grails.example.buildsrc

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.GroovyCompile

/**
 * Stands in for the Grails plugin Gradle plugin: the Grails compiler writes the plugin descriptor into the
 * compiled Groovy classes when it compiles a {@code *GrailsPlugin} class, which this plugin imitates from the
 * compile task.
 */
class StandInGrailsPluginGradlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.tasks.named('compileGroovy', GroovyCompile).configure { GroovyCompile compile ->
            compile.doLast {
                boolean pluginClassCompiled = compile.source.files.any { it.name.endsWith('GrailsPlugin.groovy') }
                if (!pluginClassCompiled) {
                    return
                }
                File descriptor = new File(compile.destinationDirectory.get().asFile, 'META-INF/grails-plugin.xml')
                descriptor.parentFile.mkdirs()
                descriptor.text = "<plugin name='descriptorPlugin' version='${project.version}' grailsVersion='7.0.0 &gt; *'><type>org.grails.example.DescriptorGrailsPlugin</type></plugin>"
            }
        }
    }
}
