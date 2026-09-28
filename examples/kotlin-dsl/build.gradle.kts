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

plugins {
    `java-library`
    groovy
    id("org.apache.grails.gradle.grails-publish")
}

val projectVersion: String by project
val groovyVersion: String by project

version = projectVersion
group = "org.grails.example"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.groovy:groovy:$groovyVersion")
}

grailsPublish {
    githubSlug.set("apache/grails-gradle-publish")
    // `license { }` takes a Groovy closure, so from Kotlin either set the license by its name...
    setLicense("Apache-2.0")
    // ...or configure it directly: license.name = "Apache-2.0"; license.url = "https://..."
    title.set("Kotlin DSL Library")
    desc.set("A library published from a Kotlin DSL build script with the Grails Publish plugin")
    // `developers = mapOf(...)` is Groovy only; use the developer { } action instead
    developer {
        id.set("jdaugherty")
        name.set("James Daugherty")
    }
    organization {
        name.set("Apache Software Foundation")
        url.set("https://www.apache.org/")
    }
}
