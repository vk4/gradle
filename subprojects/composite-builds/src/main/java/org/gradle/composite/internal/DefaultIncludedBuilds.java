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

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.artifacts.component.DefaultBuildIdentifier;
import org.gradle.initialization.IncludedBuilds;
import org.gradle.internal.component.local.model.DefaultProjectComponentSelector;
import org.gradle.internal.resolve.ModuleVersionResolveException;

import java.util.List;
import java.util.Map;

public class DefaultIncludedBuilds implements IncludedBuilds {
    private final Map<String, IncludedBuild> builds = Maps.newHashMap();
    private final Multimap<BuildIdentifier, BuildIdentifier> buildDependencies = LinkedHashMultimap.create();


    public void registerBuild(IncludedBuild build) {
        builds.put(build.getName(), build);
    }

    @Override
    public Iterable<IncludedBuild> getBuilds() {
        return builds.values();
    }

    @Override
    public IncludedBuild getBuild(String name) {
        return builds.get(name);
    }

    @Override
    public void addTask(String sourceBuildName, String targetBuildName, String taskPath) {
        BuildIdentifier sourceBuildId = new DefaultBuildIdentifier(sourceBuildName);
        DefaultBuildIdentifier targetBuildId = new DefaultBuildIdentifier(targetBuildName);
        buildDependencies.put(sourceBuildId, targetBuildId);

        List<BuildIdentifier> candidateCycle = Lists.newArrayList();
        checkNoCycles(sourceBuildId, targetBuildId, candidateCycle);

        IncludedBuildInternal targetBuild = (IncludedBuildInternal) getBuild(targetBuildName);

        targetBuild.addTaskToExecute(taskPath);
    }

    private void checkNoCycles(BuildIdentifier sourceBuild, BuildIdentifier targetBuild, List<BuildIdentifier> candidateCycle) {
        candidateCycle.add(targetBuild);
        for (BuildIdentifier nextTarget : buildDependencies.get(targetBuild)) {
            if (sourceBuild.equals(nextTarget)) {
                candidateCycle.add(nextTarget);
                ProjectComponentSelector selector = DefaultProjectComponentSelector.newSelector(candidateCycle.get(0), ":");
                throw new ModuleVersionResolveException(selector, "Included build dependency cycle: " + reportCycle(candidateCycle));
            }

            checkNoCycles(sourceBuild, nextTarget, candidateCycle);

        }
        candidateCycle.remove(targetBuild);
    }


    private String reportCycle(List<BuildIdentifier> cycle) {
        StringBuilder cycleReport = new StringBuilder();
        for (BuildIdentifier buildIdentifier : cycle) {
            cycleReport.append(buildIdentifier);
            cycleReport.append(" -> ");
        }
        cycleReport.append(cycle.get(0));
        return cycleReport.toString();
    }
}
