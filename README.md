<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

Apache Grails - Gradle Plugin - Grails Publish
========

Grails Publish is a Gradle plugin to ease publishing with the maven publish plugin or the nexus publish plugin. Artifacts published by this plugin include sources, the jar file, and a javadoc jar (that contains both the groovydoc & javadoc). This plugin is expected to be used with `snapshot` and `release` builds and assumes `releases` will require signed artifacts.

Limitations
---

This plugin currently acts as a wrapper around the `maven-publish` & `nexus-publish` plugins. There are known limitations with the `nexus-publish` plugin - specifically, when it can be applied in multiproject setups. Check out the functional test resources for specific scenarios that work and do not work.

Development Setup
---
If obtaining the source from the source distribution and you intend to build from source, you also need to download and install Gradle and use it to execute the bootstrap step so the correct version of Gradle is used. This command will bootstrap gradle: 

```shell
gradle -p gradle-bootstrap
```

Building
---
To build this project, execute the following command:

```shell
./gradlew clean build
```

Publishing Locally
---
This project can be published to your local Maven repository by running:

```shell
./gradlew publishToMavenLocal
```

Plugin Installation
---
To include this plugin in your project, add the following to your `build.gradle` file:

```groovy
buildscript {
    dependencies {
        classpath "org.apache.grails.gradle:grails-publish:$latestVersion"
    }
}
```

And then apply the plugin:

```groovy
apply plugin: 'org.apache.grails.gradle.grails-publish'
```

Plugin Configuration
---

### Initial Setup
Example Configuration:

    grailsPublish {
        websiteUrl = 'http://foo.com/myplugin'
        license {
            name = 'Apache-2.0'
        }
        issueTrackerUrl = 'https://github.com/myname/myplugin/issues'
        vcsUrl = 'https://github.com/myname/myplugin'
        title = 'My plugin title'
        desc = 'My plugin description'
        developers = [janedoe: 'Jane Doe', johndoe: 'John Doe']
    }

or

    grailsPublish {
        githubSlug = 'foo/bar'
        license {
            name = 'Apache-2.0'
        }
        title = 'My plugin title'
        desc = 'My plugin description'
        developers = [janedoe: 'Jane Doe', johndoe: 'John Doe']
        organization {
            name = 'My Company'
            url = 'http://mycompany.com'
        }
    }

or

    grailsPublish {
        githubSlug = 'foo/bar'
        license {
            name = 'Apache-2.0'
        }
        title = 'My plugin title'
        desc = 'My plugin description'
        developer {
            id = 'janedoe'
            name = 'Jane Doe'
        }
        developer {
            id = 'johndoe'
            name = 'John Doe'
        }
        organization {
            name = 'My Company'
            url = 'http://mycompany.com'
        }
    }

    

By default, this plugin will publish to the specified `MAVEN_PUBLISH` instance for snapshots, and `NEXUS_PUBLISH` for
releases. To change the snapshot publish behavior, set `snapshotRepoType` to `PublishType.NEXUS_PUBLISH`. To change the
release publish behavior, set `releaseRepoType` to `PublishType.MAVEN_PUBLISH`.

The credentials and connection url must be specified as a project property or an environment variable.

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

By default, a `release` or `snapshot` build is determined by the `project.version` or `projectVersion` gradle property. To override this behavior, use the environment variable `GRAILS_PUBLISH_RELEASE` with a boolean value to indicate if the build is a `release` or `snapshot`.

### Release Signing

`release` builds are expected to be signed by this build. To disable this behavior, add the following Gradle code: 

    project.pluginManager.withPlugin('signing') {
        project.tasks.withType(Sign).configureEach {
            it.enabled = false
        }
    }

Signing requires a valid GPG key. The key ID, or the abbreviated ID, must always be set using the property `signing.keyId` or environment variable `SIGNING_KEY`. This can be extracted with the following GPG command: `gpg --list-keys --keyid-format short | grep -Eo '[A-F0-9]{8}' | head -n 1`.

The [Gradle Signing Plugin](https://docs.gradle.org/current/userguide/signing_plugin.html) supports many different signing configurations, this plugin configures the Signing plugin by one of the following: 

1. Using a `secring.gpg` file: This file can be created with the command `gpg --keyring secring.gpg --export-secret-keys > ./secring.gpg`.  For CI environments, it's often stored in base64 format as a secret and decoded at runtime into a local file. To use this configuration specify the property `signing.secretKeyRingFile` or the environment variable `SIGNING_KEYRING` with path to the file. The property `signing.password` or environment variable `SIGNING_PASSPHRASE` can be set to specify a passphrase for the key.
2. Using the local GPG command with a private GPG or OpenPGP key. This behavior is the default if the secring file is not configured. In a CI environment, such as GitHub actions, GPG can be setup with the command: `echo "${{ secrets.MY_GPG_KEY }}" | gpg --batch --import` and the import can be confirmed successful by running the command `gpg --list-keys` to show the key ID.

Grails Publish Release Verification
---

To verify a staged release of `Grails Publish` is reproducible, you can use a containerized environment such as docker to run in an environment equivalent to GitHub actions. First, ensure the gradle wrapper is downloaded by running:

```shell
gradle -p gradle-bootstrap
```

Then, run the container that matches the CI environment:

```shell
docker build -t grails:testing -f etc/bin/Dockerfile . && docker run -it --rm -v $(pwd):/home/groovy/project -p 8080:8080 grails:testing bash
```

Once in an environment with similar settings to the CI environment, you can run the following commands to verify a release:

```shell
cd grails-verify
verify.sh v0.0.1 .
```
