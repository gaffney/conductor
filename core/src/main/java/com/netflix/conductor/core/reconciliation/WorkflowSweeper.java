/*
 * Copyright 2022 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.reconciliation;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.exception.NotFoundException;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

import static com.netflix.conductor.core.config.SchedulerConfiguration.SWEEPER_EXECUTOR_NAME;
import static com.netflix.conductor.core.utils.Utils.DECIDER_QUEUE;

@Component
public class WorkflowSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowSweeper.class);

    private final ConductorProperties properties;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRepairService workflowRepairService;
    private final QueueDAO queueDAO;

    private static final String CLASS_NAME = WorkflowSweeper.class.getSimpleName();

    @Autowired
    public WorkflowSweeper(
            WorkflowExecutor workflowExecutor,
            Optional<WorkflowRepairService> workflowRepairService,
            ConductorProperties properties,
            QueueDAO queueDAO) {
        this.properties = properties;
        this.queueDAO = queueDAO;
        this.workflowExecutor = workflowExecutor;
        this.workflowRepairService = workflowRepairService.orElse(null);
        LOGGER.info("WorkflowSweeper initialized.");
    }

    @Async(SWEEPER_EXECUTOR_NAME)
    public CompletableFuture<Void> sweepAsync(String workflowId) {
        sweep(workflowId);
        return CompletableFuture.completedFuture(null);
    }

    public void sweep(String workflowId) {
        try {
            WorkflowContext workflowContext = new WorkflowContext(properties.getAppId());
            WorkflowContext.set(workflowContext);
            LOGGER.debug("Running sweeper for workflow {}", workflowId);

            if (workflowRepairService != null) {
                // Verify and repair tasks in the workflow.
                workflowRepairService.verifyAndRepairWorkflowTasks(workflowId);
            }

            boolean done = workflowExecutor.decide(workflowId);
            if (done) {
                queueDAO.remove(DECIDER_QUEUE, workflowId);
                return;
            }
        } catch (NotFoundException nfe) {
            queueDAO.remove(DECIDER_QUEUE, workflowId);
            LOGGER.info(
                    "Workflow NOT found for id:{}. Removed it from decider queue", workflowId, nfe);
            return;
        } catch (Exception e) {
            Monitors.error(CLASS_NAME, "sweep");
            LOGGER.error("Error running sweep for " + workflowId, e);
        }
        queueDAO.setUnackTimeout(
                DECIDER_QUEUE, workflowId, properties.getWorkflowOffsetTimeout().toMillis());
    }
}
