<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

Grails Publish - Example Projects
=================================

Each directory is a complete, standalone Gradle project that publishes with the Grails Publish plugin. They
document what the plugin supports, and the functional tests of this repository run every one of them
(`plugin/src/functionalTest/groovy/org/apache/grails/gradle/publish/examples`), so they are guaranteed to work with
the plugin at the same commit.

Running an example
------------------

The examples resolve the plugin from the local Maven repository, so publish it there first, from the root of
this repository:

```shell
./gradlew publishToMavenLocal
```

`grailsPublishVersion` in each example's `gradle.properties` must match the version that was published. Then run
any example with the wrapper of this repository, for instance a snapshot publish to a directory:

```shell
cd examples/simple-library
GRAILS_PUBLISH_RELEASE=false MAVEN_PUBLISH_URL=file:///tmp/example-repo ../../gradlew publish
```

or a signed release, staged and released on Nexus:

```shell
cd examples/simple-library
export NEXUS_PUBLISH_URL=https://ossrh-staging-api.central.sonatype.com/service/local/
export NEXUS_PUBLISH_USERNAME=... NEXUS_PUBLISH_PASSWORD=... NEXUS_PUBLISH_STAGING_PROFILE_ID=...
export SIGNING_KEY=... SIGNING_KEYRING=/path/to/secring.gpg SIGNING_PASSPHRASE=...
../../gradlew -PprojectVersion=1.2.3 publishToSonatype closeAndReleaseSonatypeStagingRepository
```

Every example accepts the same environment variables and project properties as any build using the plugin; see the
[plugin configuration](../README.md#plugin-configuration) section of the main README.

Project shapes
--------------

| Example                                                          | Demonstrates                                                                                                                                                                                     |
|------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [simple-library](simple-library)                                 | A Groovy and Java library: jar, sources jar, javadoc jar (built from the groovydoc), pom and Gradle module metadata. Used for every publishing target below. Shows how to disable signing.    |
| [java-library](java-library)                                     | A Java only project; the javadoc jar is built by the `javadoc` task.                                                                                                                             |
| [kotlin-dsl](kotlin-dsl)                                         | Configuring the plugin from a `build.gradle.kts` build script (`setLicense`, `developer { }`, `organization { }`).                                                                               |
| [multi-project-root-configured](multi-project-root-configured)   | The plugin applied and configured for every subproject from a `subprojects { }` block of the root build script; project dependencies; the `testRepositoryPath` repository and its aggregate task. |
| [multi-project-per-subproject](multi-project-per-subproject)     | Each subproject applying the plugin in its own `plugins { }` block. Shows the root project applying the Nexus publish plugin itself, which this layout needs for Nexus publishing.               |
| [multi-project-grails-core-layout](multi-project-grails-core-layout) | The layout of the grails-core build: the root applies the plugin to allow-listed subprojects before their own build scripts run; a shared convention script configures the extension; a Groovy module with `java-test-fixtures`, a BOM constraining the sibling modules (versions rewritten to Maven properties), a dependency-only starter without sources or javadoc, a Gradle plugin; a root aggregate task for the test repository; and a standalone `consumer/` build resolving everything that was published. |
| [extended-plugin](extended-plugin)                               | A subclass of the plugin overriding `getDefaultExtraArtifact` / `getDefaultClassifier` to publish an extra `-profile.yml` artifact, the way the Grails profile publishing plugin does.          |
| [bom](bom)                                                       | A `java-platform` bill of materials: a pom only publication that keeps its `dependencyManagement` section, with a `pomCustomization`.                                                            |
| [grails-plugin](grails-plugin)                                   | A project with a `META-INF/grails-plugin.xml` descriptor, published as the extra `-plugin.xml` artifact (classifier `plugin`).                                                                    |
| [additional-publication](additional-publication)                 | A `cli` companion artifact published under its own coordinate from a feature variant, with its own dependency graph, sources and javadoc.                                                       |
| [managed-dependency-versions](managed-dependency-versions)       | Versionless dependencies managed by a Gradle platform and by the Spring dependency management plugin; the pom and module metadata carry the resolved versions, `dependencyManagement` is removed. |
| [custom-pom](custom-pom)                                         | Overridden `groupId`, `artifactId` and `publicationName`; explicit urls instead of a `githubSlug`; a custom license; the full `developer { }` model; `organization`; `pomCustomization`.          |
| [test-sources](test-sources)                                     | `publishTestSources = true`: the compiled test classes are published as a `-tests.jar`.                                                                                                          |
| [gradle-plugin](gradle-plugin)                                   | A Gradle plugin (`java-gradle-plugin`) whose `pluginMaven` publication is completed by the plugin, in either application order.                                                               |

Publishing targets
------------------

All of these are exercised on `simple-library` by `PublishTargetsSpec`; the Nexus ones run against a local
WireMock endpoint (over TLS) implementing the Sonatype staging API, and the signed ones verify the signatures with
the key the test generated.

| Build state                      | Target                                   | How                                                                                                                          | Result                                                                                                    |
|----------------------------------|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| snapshot (`-SNAPSHOT` version)   | Maven repository (default)               | `MAVEN_PUBLISH_URL` (file, or https with `MAVEN_PUBLISH_USERNAME` / `MAVEN_PUBLISH_PASSWORD`); `./gradlew publish`           | timestamped snapshot artifacts, unsigned                                                                  |
| snapshot                         | Nexus snapshot repository                | `-PsnapshotPublishType=NEXUS_PUBLISH`, `NEXUS_PUBLISH_*`; `./gradlew publishToSonatype`                                       | uploaded to `NEXUS_PUBLISH_SNAPSHOT_URL`, no staging repository                                           |
| release                          | Nexus staging repository (default)       | `NEXUS_PUBLISH_*`, `SIGNING_KEY`, `SIGNING_KEYRING`, `SIGNING_PASSPHRASE`; `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository` | a staging repository described by `NEXUS_PUBLISH_DESCRIPTION`, signed artifacts, closed and released       |
| release, in separate builds      | Nexus staging repository                 | `initializeSonatypeStagingRepository`, then `-x initializeSonatypeStagingRepository findSonatypeStagingRepository publishToSonatype`, then `... closeSonatypeStagingRepository`, then `... releaseSonatypeStagingRepository` | the same, the way the grails-core release workflow does it                                                |
| release, several builds          | one Nexus staging repository             | every build uses the same `NEXUS_PUBLISH_DESCRIPTION`; the first runs `publishToSonatype`, the others `-x initializeSonatypeStagingRepository findSonatypeStagingRepository publishToSonatype` | all modules land in the one staging repository (grails-core, grails-gradle and grails-forge do this)      |
| release, signing disabled        | local Maven repository                   | `publishToMavenLocal` with the `Sign` tasks disabled                                                                         | the reproducible build check of grails-core: a release version, published locally, unsigned              |
| release                          | Maven repository                         | `-PreleasePublishType=MAVEN_PUBLISH`, `MAVEN_PUBLISH_URL`, `SIGNING_*`; `./gradlew publish`                                    | signed release artifacts with `.asc` signatures                                                           |
| release, signing disabled        | Maven repository                         | as above with the `Sign` tasks disabled (see `simple-library/build.gradle`)                                                  | unsigned release artifacts                                                                                |
| release, no signing key          | any                                      | `SIGNING_KEY` unset                                                                                                          | the build fails before publishing: `A signing key is required to sign a release`                          |
| release forced by the environment| Maven repository                         | `GRAILS_PUBLISH_RELEASE=true` with a `-SNAPSHOT` version                                                                     | the snapshot version is published signed                                                                  |
| snapshot forced by the environment| Maven repository                        | `GRAILS_PUBLISH_RELEASE=false` with a release version                                                                        | the release version is published unsigned                                                                 |
| any                              | local Maven repository (`~/.m2`)         | `./gradlew publishToMavenLocal` or its alias `./gradlew install`                                                             | unsigned artifacts in the local repository                                                                |
| release, in a clean container    | Nexus staging repository, signed by `gpg`| `SIGNING_KEY` without `SIGNING_KEYRING`, the key in the gpg keyring (`signing.gnupg.*` properties apply). `ContainerizedReleaseSpec`: a Gradle + gpg image (built with Testcontainers) imports a throwaway key and runs the release, staging into the test's Nexus through an exposed host port | the grails-core release path (gpg import plus `SIGNING_KEY`) in an isolated environment                    |

The tests run no external commands on the machine: keys and the TLS certificate are generated in process, and the
only gpg involved runs inside the container of `ContainerizedReleaseSpec`. Docker is therefore required to run the
functional tests, as it is for the grails-core build.

Things to know
--------------

- **Signing** applies to releases only. Release artifacts are signed with the keyring file when `SIGNING_KEYRING` is
  set, and with the local `gpg` command otherwise; either way `SIGNING_KEY` is required.
- **Nexus and multi-project builds.** The Nexus publish plugin can only be applied to the root project, and only
  before the root project has been evaluated. When the Grails Publish plugin is applied from a `subprojects { }`
  block this happens automatically. When it is applied in the build scripts of the subprojects, the root build
  script has to apply and configure `io.github.gradle-nexus.publish-plugin` itself (see
  `multi-project-per-subproject/build.gradle`). In both layouts the root project needs `version` set, because the
  Nexus plugin decides between staging and snapshot publishing from the root project's version.
- **Gradle plugins.** The plugin completes the `pluginMaven` publication of `java-gradle-plugin` on its own, in either
  application order (`gradle-plugin/build.gradle`).
- **Additional publications.** The jar of a feature variant carries the source set name as its classifier; publish it
  unclassified so the companion coordinate has a primary jar for Maven consumers (`additional-publication/build.gradle`).
- **Grails plugin descriptors.** With the Grails plugin Gradle plugin applied, the `-plugin.xml` artifact is built by
  `compileGroovy` and published from a clean checkout. A build producing the descriptor itself sets
  `grailsPublish.pluginDescriptor` (`grails-plugin/build.gradle`).
- **Kotlin DSL.** `license { }` and `developers = [:]` are Groovy closures and map based shorthands; from Kotlin use
  `setLicense("Apache-2.0")` (or `license.name`/`license.url`), `developer { }` and `organization { }`.
- **Applying from the root.** When the root build script applies the plugin to subprojects (as grails-core does) no
  subproject has a version yet, so the snapshot/release decision comes from the `projectVersion` Gradle property; the
  plugin logs this. A `gradle/*.gradle` script plugin cannot import `GrailsPublishExtension` (classes from a
  `plugins { }` block are not visible to script plugins), so configure the extension by name:
  `extensions.configure('grailsPublish') { it... }`, or use a binary convention plugin.
- **Dependency-only projects.** Disable the `sourcesJar` and `javadocJar` tasks and their artifacts are left out of
  the publication; call `java { withSourcesJar(); withJavadocJar() }` first so the tasks exist in the build script.
- **Test fixtures.** A `java-test-fixtures` project publishes its fixtures as the `<artifactId>-test-fixtures`
  capability variant. Versions of fixture-only dependencies are resolved from the test fixtures classpaths, in the
  pom and in the Gradle module metadata.
- **Repositories.** The test endpoints are served over TLS with a generated certificate that the builds trust through
  the standard `javax.net.ssl.trustStore` system properties, the way a Nexus behind a private CA is used. A plain
  http repository needs Gradle's `allowInsecureProtocol` opt-in, which the plugin does not set. Credentials
  (`MAVEN_PUBLISH_USERNAME` / `PASSWORD`) must not be set for a `file:` repository, since Gradle rejects
  authentication on the file protocol.
