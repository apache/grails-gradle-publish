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

Grails Publish Gradle Plugin
========

Grails Publish is a Gradle plugin to ease publishing with the maven publish plugin or the nexus publish plugin.

Artifacts published by this plugin include sources, the jar file, and a javadoc jar that contains both the groovydoc &
javadoc.

Limitations
---

This plugin currently acts as a wrapper around the `maven-publish` & `nexus-publish` plugins. There are known
limitations with the `nexus-publish` plugin - specifically, when it can be applied in multiproject setups. Check out the
functional test resources for specific scenarios that work and do not work.

Setup
---
If obtaining the source from the source distribution and you intend to build from source, you also need to download and
install Gradle and use it to execute the bootstrap step so the correct version of Gradle is used. This command will bootstrap gradle: 

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

Installation
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

Configuration
---
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

By default, the release or snapshot state is determined by the project.version or projectVersion gradle property. To
override this behavior, use the environment variable `GRAILS_PUBLISH_RELEASE` with a boolean value to decide if it's a
release or snapshot.

## Release Verification

To verify a reproducible build from a staged release, you can use a containerized environment such as docker to run in an environment equivalent to GitHub actions. First, ensure the gradle wrapper is downloaded by running:

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
