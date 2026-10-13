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
import org.gradle.api.component.ComponentWithVariants
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.component.SoftwareComponentVariant
import org.gradle.api.internal.component.SoftwareComponentInternal

/**
 * Wraps the primary software component when additional publications exist, declaring the
 * additional publications' components as child components.
 *
 * Gradle can only resolve a project dependency on a project that publishes multiple coordinates
 * when the published components form a single tree: one top-level component whose
 * {@link ComponentWithVariants#getVariants() child components} carry the other coordinates
 * (see {@code DefaultProjectDependencyPublicationResolver}). Without this wrapper, any project
 * dependency on a project with additional publications fails metadata generation with
 * "Publishing is not able to resolve a dependency on a project with multiple publications that
 * have different coordinates". This is the same publication model used by Kotlin Multiplatform.
 *
 * As a consequence the primary publication's Gradle module metadata lists the child components'
 * variants as {@code available-at} redirects to their own coordinates. Those variants carry the
 * child's capabilities, so consumers that do not explicitly request them are unaffected.
 */
@CompileStatic
class GrailsRootSoftwareComponent implements SoftwareComponentInternal, ComponentWithVariants {

    private final SoftwareComponentInternal delegate
    private final Set<SoftwareComponent> childComponents

    GrailsRootSoftwareComponent(SoftwareComponentInternal delegate, Collection<SoftwareComponent> childComponents) {
        this.delegate = delegate
        this.childComponents = new LinkedHashSet<>(childComponents)
    }

    @Override
    String getName() {
        delegate.name
    }

    @Override
    Set<? extends SoftwareComponentVariant> getUsages() {
        delegate.usages
    }

    @Override
    Set<? extends SoftwareComponent> getVariants() {
        childComponents
    }
}
