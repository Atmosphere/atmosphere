/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.admin.a2a;

import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.runtime.TaskManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read operations for A2A task lifecycle management.
 *
 * @since 4.0
 */
public final class TaskController {

    private final TaskManager taskManager;

    public TaskController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * List tasks, optionally filtered by context ID.
     */
    public List<Map<String, Object>> listTasks(String contextId) {
        var tasks = taskManager.listTasks(contextId);
        var result = new ArrayList<Map<String, Object>>(tasks.size());
        for (TaskContext task : tasks) {
            result.add(taskSummary(task));
        }
        return result;
    }

    /**
     * Get detailed information about a specific task.
     */
    public Optional<Map<String, Object>> getTask(String taskId) {
        return taskManager.getTask(taskId).map(this::taskDetail);
    }

    /**
     * Cancel an in-flight task.
     *
     * @return true if the task was found in WORKING state and canceled
     */
    public boolean cancelTask(String taskId) {
        return taskManager.cancelTask(taskId);
    }

    private Map<String, Object> taskSummary(TaskContext task) {
        var info = new LinkedHashMap<String, Object>();
        info.put("taskId", task.taskId());
        info.put("contextId", task.contextId());
        info.put("state", task.state().name());
        info.put("statusMessage", task.statusMessage());
        info.put("createdAt", task.createdAtMillis());
        return info;
    }

    private Map<String, Object> taskDetail(TaskContext task) {
        var info = taskSummary(task);
        info.put("messageCount", task.messages().size());
        info.put("artifactCount", task.artifacts().size());
        info.put("metadata", task.metadata());
        return info;
    }
}
