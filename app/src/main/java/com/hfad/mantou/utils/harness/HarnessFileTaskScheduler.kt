package com.hfad.mantou.utils.harness

object HarnessFileTaskScheduler {

    fun schedule(tasks: List<HarnessFileTask>): List<HarnessFileTask> {
        if (tasks.isEmpty()) return emptyList()

        val indexedTasks = tasks.mapIndexed { index, task ->
            require(task.path.isNotBlank()) { "Harness file task path cannot be blank" }
            IndexedTask(index = index, task = task)
        }
        val tasksByPath = indexedTasks.associateBy { it.task.path }
        require(tasksByPath.size == indexedTasks.size) {
            "Harness file task paths must be unique"
        }

        val outgoing = indexedTasks.associate { it.task.path to mutableListOf<IndexedTask>() }
        val indegree = indexedTasks.associate { it.task.path to 0 }.toMutableMap()
        indexedTasks.forEach { indexedTask ->
            val task = indexedTask.task
            require(task.dependsOn.distinct().size == task.dependsOn.size) {
                "Harness file task ${task.path} contains duplicate dependencies"
            }
            task.dependsOn.forEach { dependency ->
                require(dependency != task.path) {
                    "Harness file task ${task.path} cannot depend on itself"
                }
                val dependencyTask = tasksByPath[dependency]
                    ?: throw IllegalArgumentException(
                        "Harness file task ${task.path} depends on missing task $dependency"
                    )
                outgoing.getValue(dependencyTask.task.path) += indexedTask
                indegree[task.path] = indegree.getValue(task.path) + 1
            }
        }

        val ready = indexedTasks
            .filter { indegree.getValue(it.task.path) == 0 }
            .toMutableList()
        val scheduled = mutableListOf<HarnessFileTask>()
        while (ready.isNotEmpty()) {
            val next = ready.removeAt(0)
            scheduled += next.task
            outgoing.getValue(next.task.path)
                .sortedBy(IndexedTask::index)
                .forEach { dependent ->
                    val remaining = indegree.getValue(dependent.task.path) - 1
                    indegree[dependent.task.path] = remaining
                    if (remaining == 0) {
                        val insertionIndex = ready.indexOfFirst { it.index > dependent.index }
                        if (insertionIndex == -1) {
                            ready += dependent
                        } else {
                            ready.add(insertionIndex, dependent)
                        }
                    }
                }
        }

        require(scheduled.size == tasks.size) {
            val blocked = indexedTasks
                .filter { indegree.getValue(it.task.path) > 0 }
                .joinToString(",") { it.task.path }
            "Harness file task dependency graph contains a cycle: $blocked"
        }
        return scheduled
    }

    private data class IndexedTask(
        val index: Int,
        val task: HarnessFileTask
    )
}
