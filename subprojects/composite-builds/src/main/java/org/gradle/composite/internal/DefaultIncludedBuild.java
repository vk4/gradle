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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.BuildResult;
import org.gradle.api.Action;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DefaultDependencySubstitutions;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionsInternal;
import org.gradle.api.tasks.TaskReference;
import org.gradle.initialization.GradleLauncher;
import org.gradle.initialization.ReportedException;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 1. Add Task Reference to graph (already done)
 * 2. When resolving task reference
 *      - Register 'addTasksToExecute' for included build (do this directly on the build?)
 *      - Create a delegating task that will call 'waitForCompletion' on included build
 * 3. When delegating task calls 'waitForCompletion' it will trigger build, returning when complete
 *      - 'addTasksToExecute' for an executing build is OK. Will require a second build execution
 * 4. Later, we'll kick off the build sooner, once all of the 'addTasksToExecute' have been called
 *      - Then, 'waitForCompletion' will do exactly that. Might wait for complete build, not just task requested.
 * 5. Improvements
 *      - Only wait for a particular task to be complete, not the entire build
 *      - Allow more tasks to be added to the graph of an executing build?
 *      - Detect cycles
 */
public class DefaultIncludedBuild implements IncludedBuildInternal {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIncludedBuild.class);

    private final File projectDir;
    private final Factory<GradleLauncher> gradleLauncherFactory;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final List<Action<? super DependencySubstitutions>> dependencySubstitutionActions = Lists.newArrayList();

    // Fields guarded by lock
    private final Lock lock = new ReentrantLock();
    private final Condition buildCompleted = lock.newCondition();
    private boolean currentlyExecuting;

    private final List<String> tasksToExecute = Lists.newArrayList();
    private final Set<String> executedTasks = Sets.newLinkedHashSet();
    private DefaultDependencySubstitutions dependencySubstitutions;

    private GradleLauncher gradleLauncher;
    private SettingsInternal settings;
    private GradleInternal gradle;

    public DefaultIncludedBuild(File projectDir, Factory<GradleLauncher> launcherFactory, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.projectDir = projectDir;
        this.gradleLauncherFactory = launcherFactory;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    public File getProjectDir() {
        return projectDir;
    }

    @Override
    public TaskReference task(String path) {
        Preconditions.checkArgument(path.startsWith(":"), "Task path '%s' is not a qualified task path (e.g. ':task' or ':project:task').", path);
        return new IncludedBuildTaskReference(getName(), path);
    }

    @Override
    public synchronized String getName() {
        return getLoadedSettings().getRootProject().getName();
    }

    @Override
    public void dependencySubstitution(Action<? super DependencySubstitutions> action) {
        if (dependencySubstitutions != null) {
            throw new IllegalStateException("Cannot configure included build after dependency substitutions are resolved.");
        }
        dependencySubstitutionActions.add(action);
    }

    public DependencySubstitutionsInternal resolveDependencySubstitutions() {
        if (dependencySubstitutions == null) {
            dependencySubstitutions = DefaultDependencySubstitutions.forIncludedBuild(this, moduleIdentifierFactory);

            for (Action<? super DependencySubstitutions> action : dependencySubstitutionActions) {
                action.execute(dependencySubstitutions);
            }
        }
        return dependencySubstitutions;
    }

    @Override
    public SettingsInternal getLoadedSettings() {
        if (settings == null) {
            GradleLauncher gradleLauncher = getGradleLauncher();
            gradleLauncher.load();
            settings = gradleLauncher.getSettings();
        }
        return settings;
    }

    @Override
    public GradleInternal getConfiguredBuild() {
        if (gradle == null) {
            GradleLauncher gradleLauncher = getGradleLauncher();
            gradleLauncher.getBuildAnalysis();
            settings = gradleLauncher.getSettings();
            gradle = gradleLauncher.getGradle();
        }
        return gradle;
    }

    private GradleLauncher getGradleLauncher() {
        if (gradleLauncher == null) {
            gradleLauncher = gradleLauncherFactory.create();
            reset();
        }
        return gradleLauncher;
    }

    private void reset() {
        gradle = null;
        settings = null;
    }

    @Override
    public void addTasksToExecute(Collection<String> tasks) {
        System.out.println("Adding task " + tasks + " to build " + getName());
        lock.lock();
        try {
            tasksToExecute.addAll(tasks);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void awaitCompletion() {
        List<String> taskNames = buildStarted();
        try {
            doBuild(taskNames);
        } finally {
            buildCompleted();
        }
    }

    private List<String> buildStarted() {
        lock.lock();
        try {
            waitForExistingBuildToComplete();
            List<String> tasksForBuild = Lists.newArrayList(tasksToExecute);
            tasksToExecute.clear();
            currentlyExecuting = true;
            return tasksForBuild;
        } finally {
            lock.unlock();
        }
    }


    private void waitForExistingBuildToComplete() {
        try {
            while (currentlyExecuting) {
                buildCompleted.await();
            }
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private BuildResult doBuild(Iterable<String> taskPaths)  throws ReportedException {
        List<String> tasksToExecute = Lists.newArrayList();
        for (String taskPath : taskPaths) {
            if (executedTasks.add(taskPath)) {
                tasksToExecute.add(taskPath);
            }
        }
        if (tasksToExecute.isEmpty()) {
            return null;
        }
        LOGGER.info("Executing " + getName() + " tasks " + taskPaths);
        return execute(tasksToExecute);
    }


    public BuildResult execute(Iterable<String> tasks) {
        GradleLauncher launcher = getGradleLauncher();
        launcher.getGradle().getStartParameter().setTaskNames(tasks);
        try {
            return launcher.run();
        } finally {
            markAsNotReusable();
        }
    }

    private void buildCompleted() {
        lock.lock();
        try {
            currentlyExecuting = false;
            buildCompleted.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void markAsNotReusable() {
        gradleLauncher = null;
    }

    @Override
    public String toString() {
        return String.format("includedBuild[%s]", projectDir.getPath());
    }
}
