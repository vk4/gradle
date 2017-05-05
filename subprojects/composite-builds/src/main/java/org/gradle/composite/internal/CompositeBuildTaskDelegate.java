/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.composite.internal;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.artifacts.component.DefaultBuildIdentifier;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.initialization.IncludedBuilds;
import org.gradle.initialization.ReportedException;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.internal.exceptions.LocationAwareException;

// TODO:DAZ Any other task should be able to run at the same time as these, even tasks that are _not_ parallel safe
// Not having a 'task' is probably the right solution to this.

// These 'tasks' should also not consume a worker thread resource: currently can easily hang due to thread-starvation

// TODO:DAZ Make a separate delegating task per target task in the included build
// so that we can wait for the specific task required.
// Or get rid of the delegating task altogether
public class CompositeBuildTaskDelegate extends DefaultTask {
    private String build;

    @Input
    public String getBuild() {
        return build;
    }

    public void setBuild(String build) {
        this.build = build;
    }

    @TaskAction
    public void executeTasksInOtherBuild() {
        IncludedBuilds includedBuilds = getServices().get(IncludedBuilds.class);
        IncludedBuildInternal includedBuild = (IncludedBuildInternal) includedBuilds.getBuild(build);
        BuildIdentifier buildId = new DefaultBuildIdentifier(includedBuild.getName());
        try {
            includedBuild.awaitCompletion();
        } catch (ReportedException e) {
            throw contextualizeFailure(buildId, e);
        }
    }

    private RuntimeException contextualizeFailure(BuildIdentifier buildId, ReportedException e) {
        if (e.getCause() instanceof LocationAwareException) {
            LocationAwareException lae = (LocationAwareException) e.getCause();
            IncludedBuildArtifactException wrappedCause = new IncludedBuildArtifactException("Failed to build artifacts for " + buildId, lae.getCause());
            LocationAwareException newLae = new LocationAwareException(wrappedCause, lae.getSourceDisplayName(), lae.getLineNumber());
            return new ReportedException(newLae);
        }
        return e;
    }

    @Contextual
    private static class IncludedBuildArtifactException extends GradleException {
        public IncludedBuildArtifactException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
