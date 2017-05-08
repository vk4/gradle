/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.logging.console;

import org.gradle.internal.logging.events.BatchOutputEventListener;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WorkInProgressRenderer extends BatchOutputEventListener {
    private final OutputEventListener listener;
    private final ProgressOperations operations = new ProgressOperations();
    private final BuildProgressArea progressArea;
    private final DefaultWorkInProgressFormatter labelFormatter;
    private final ConsoleLayoutCalculator consoleLayoutCalculator;

    // Track all unused labels to display future progress operation
    private final Deque<StyledLabel> unusedProgressLabels;

    // Track currently associated label with its progress operation
    private final Map<OperationIdentifier, AssociationLabel> operationIdToAssignedLabels = new HashMap<OperationIdentifier, AssociationLabel>();

    // Track any progress operation that either can't be display due to label shortage or child progress operation is already been displayed
    private final Deque<ProgressOperation> unassignedProgressOperations = new ArrayDeque<ProgressOperation>();

    // Track the parent-children relation between progress operation to avoid displaying a parent when children are been displayed
    private final ParentRegistry registry = new ParentRegistry();

    public WorkInProgressRenderer(OutputEventListener listener, BuildProgressArea progressArea, DefaultWorkInProgressFormatter labelFormatter, ConsoleLayoutCalculator consoleLayoutCalculator) {
        this.listener = listener;
        this.progressArea = progressArea;
        this.labelFormatter = labelFormatter;
        this.consoleLayoutCalculator = consoleLayoutCalculator;
        this.unusedProgressLabels = new ArrayDeque<StyledLabel>(progressArea.getBuildProgressLabels());
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event instanceof ProgressStartEvent) {
            progressArea.setVisible(true);
            ProgressStartEvent startEvent = (ProgressStartEvent) event;
            ProgressOperation op = operations.start(startEvent.getShortDescription(), startEvent.getStatus(), startEvent.getCategory(), startEvent.getProgressOperationId(), startEvent.getParentProgressOperationId());
            attach(op);
        } else if (event instanceof ProgressCompleteEvent) {
            ProgressCompleteEvent completeEvent = (ProgressCompleteEvent) event;
            detach(operations.complete(completeEvent.getProgressOperationId()));
        } else if (event instanceof ProgressEvent) {
            ProgressEvent progressEvent = (ProgressEvent) event;
            operations.progress(progressEvent.getStatus(), progressEvent.getProgressOperationId());
        } else if (event instanceof EndOutputEvent) {
            progressArea.setVisible(false);
        }

        listener.onOutput(event);
    }

    @Override
    public void onOutput(Iterable<OutputEvent> events) {
        super.onOutput(events);
        renderNow();
    }

    private void resizeTo(int newBuildProgressLabelCount) {
        int previousBuildProgressLabelCount = progressArea.getBuildProgressLabels().size();
        newBuildProgressLabelCount = consoleLayoutCalculator.calculateNumWorkersForConsoleDisplay(newBuildProgressLabelCount);
        if (previousBuildProgressLabelCount >= newBuildProgressLabelCount) {
            // We don't support shrinking at the moment
            return;
        }

        progressArea.resizeBuildProgressTo(newBuildProgressLabelCount);

        // Add new labels to the unused queue
        for (int i = newBuildProgressLabelCount - 1; i >= previousBuildProgressLabelCount; --i) {
            unusedProgressLabels.push(progressArea.getBuildProgressLabels().get(i));
        }
    }

    private void attach(ProgressOperation operation) {
        // Skip attach if a children is already present
        if (registry.find(operation.getOperationId()).hasChildren()) {
            return;
        }

        // Reuse parent label if possible
        if (operation.getParent() != null) {
            registry.find(operation.getParent().getOperationId()).add(operation.getOperationId());
            detach(operation.getParent().getOperationId());
        }

        // No more unused label? Try to resize.
        if (unusedProgressLabels.isEmpty()) {
            int newValue = operationIdToAssignedLabels.size() + 1;
            resizeTo(newValue);
        }

        // Try to use a new label
        if (unusedProgressLabels.isEmpty()) {
            unassignedProgressOperations.add(operation);
        } else {
            attach(operation, unusedProgressLabels.pop());
        }
    }

    private void attach(ProgressOperation operation, StyledLabel label) {
        AssociationLabel association = new AssociationLabel(operation, label);
        operationIdToAssignedLabels.put(operation.getOperationId(), association);
    }

    private void detach(ProgressOperation operation) {
        if (operation.getParent() != null) {
            registry.find(operation.getParent().getOperationId()).remove(operation.getOperationId());
        }

        detach(operation.getOperationId());
        unassignedProgressOperations.remove(operation);

        if (operation.getParent() != null) {
            attach(operation.getParent());
        } else if (!unassignedProgressOperations.isEmpty()) {
            attach(unassignedProgressOperations.pop());
        }
    }

    private void detach(OperationIdentifier operationId) {
        AssociationLabel association = operationIdToAssignedLabels.remove(operationId);
        if (association != null) {
            unusedProgressLabels.push(association.label);
        }
    }

    private void renderNow() {
        for (AssociationLabel associatedLabel : operationIdToAssignedLabels.values()) {
            associatedLabel.renderNow();
        }
        for (StyledLabel emptyLabel : unusedProgressLabels) {
            emptyLabel.setText(labelFormatter.format());
        }
    }

    private class AssociationLabel {
        final ProgressOperation operation;
        final StyledLabel label;

        AssociationLabel(ProgressOperation operation, StyledLabel label) {
            this.operation = operation;
            this.label = label;
        }

        void renderNow() {
            label.setText(labelFormatter.format(operation));
        }
    }

    private static class ParentRegistry {
        private final Map<OperationIdentifier, Set<OperationIdentifier>> parentIdToChildrenIds = new HashMap<OperationIdentifier, Set<OperationIdentifier>>();

        public ChildrenRegistry find(final OperationIdentifier parentId) {
            return new ChildrenRegistry() {
                @Override
                public void add(OperationIdentifier childId) {
                    Set<OperationIdentifier> children = parentIdToChildrenIds.get(parentId);
                    if (children == null) {
                        children = new HashSet<OperationIdentifier>();
                        parentIdToChildrenIds.put(parentId, children);
                    }
                    children.add(childId);
                }

                @Override
                public void remove(OperationIdentifier childId) {
                    Set<OperationIdentifier> children = parentIdToChildrenIds.get(parentId);
                    if (children == null) {
                        return;
                    }
                    children.remove(childId);
                    if (children.isEmpty()) {
                        parentIdToChildrenIds.remove(parentId);
                    }
                }

                @Override
                public boolean hasChildren() {
                    Set<OperationIdentifier> children = parentIdToChildrenIds.get(parentId);
                    return children != null && !children.isEmpty();
                }
            };
        }

        public interface ChildrenRegistry {
            void add(OperationIdentifier childId);

            void remove(OperationIdentifier childId);

            boolean hasChildren();
        }
    }
}
