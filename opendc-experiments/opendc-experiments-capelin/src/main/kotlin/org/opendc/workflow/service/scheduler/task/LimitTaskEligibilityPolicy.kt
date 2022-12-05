/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.workflow.service.scheduler.task

import org.opendc.workflow.service.internal.TaskState
import org.opendc.workflow.service.internal.WorkflowServiceImpl

/**
 * A [TaskEligibilityPolicy] that limits the total number of active tasks in the system.
 */
public data class LimitTaskEligibilityPolicy(val limit: Int) : TaskEligibilityPolicy {
    override fun invoke(scheduler: WorkflowServiceImpl): TaskEligibilityPolicy.Logic = object : TaskEligibilityPolicy.Logic {
        override fun invoke(
            task: TaskState
        ): TaskEligibilityPolicy.Advice =
            if (scheduler.activeTasks.size < limit) {
                TaskEligibilityPolicy.Advice.ADMIT
            } else {
                TaskEligibilityPolicy.Advice.STOP
            }
    }

    override fun toString(): String = "Limit-Active($limit)"
}
