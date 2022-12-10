package org.opendc.workflow.service.internal

import org.opendc.workflow.api.WORKFLOW_TASK_DEADLINE
import org.opendc.workflow.api.WORKFLOW_TASK_MINIMAL_START_TIME
import org.opendc.workflow.api.WORKFLOW_TASK_SLACK
import java.util.UUID
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.max

public fun JobState.computeSlack() {
    this.computeMinimalStartTimes()
    for (t in this.tasks) {
        if (t.dependencies.isEmpty())  {
            t.task.metadata[WORKFLOW_TASK_SLACK] = 0L
        } else {
            val cmin = t.dependencies.minWith(Comparator.comparingLong {it.task.metadata.getOrDefault(WORKFLOW_TASK_SLACK, 0L) as Long})
            t.task.metadata[WORKFLOW_TASK_SLACK] = cmin.task.metadata.getOrDefault(WORKFLOW_TASK_SLACK, 0L) as Long -
                t.task.metadata[WORKFLOW_TASK_MINIMAL_START_TIME] as Long + t.task.metadata[WORKFLOW_TASK_DEADLINE] as Long
        }
    }
}

private fun JobState.computeMinimalStartTimes() {
    val tasks = LinkedHashMap<UUID, TaskState>()
    for (t in this.tasks) {
        t.task.metadata[WORKFLOW_TASK_MINIMAL_START_TIME] = this.submittedAt
        tasks[t.task.uid] = t
    }

    // Toposort the dependency graph
    val waves = hashSetOf<HashSet<UUID>>()

    val depCount = HashMap<UUID, Int>()
    val children = HashMap<UUID, HashSet<UUID>>()
    tasks.forEach { (_, t) ->
        depCount[t.task.uid] = t.dependencies.size
        t.dependencies.forEach {
            children.getOrPut(it.task.uid) { HashSet() }.add(t.task.uid)
        }
    }

    while (true) {
        val wave = HashSet<UUID>()
        for ((k, v) in depCount) {
            if (v == 0) {
                wave.add(k)
            }
        }

        if (wave.isEmpty()) {
            break
        }

        for (t in wave) {
            depCount.remove(t)
            children[t]?.forEach { c ->
                if (depCount.containsKey(c)) {
                    depCount[c] = depCount[c]!! - 1
                }
            }
        }
        waves.add(wave)
    }

    // Now we go in waves through the topologically sorted graph
    // and set each task's minimal start time to the end time of its slowest children
    for (wave in waves) {
        for (t in wave) {
            val curTask = tasks[t]!!
            // Update all children
            children[t]?.forEach { c ->
                val childTask = tasks[c]!!
                childTask.task.metadata[WORKFLOW_TASK_MINIMAL_START_TIME] =
                    max(childTask.task.metadata[WORKFLOW_TASK_MINIMAL_START_TIME] as Long,
                        (curTask.task.metadata[WORKFLOW_TASK_MINIMAL_START_TIME] as Long) +
                            (curTask.task.metadata[WORKFLOW_TASK_DEADLINE] as Long))
            }
        }
    }
}
