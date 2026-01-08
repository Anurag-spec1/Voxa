package com.anurag.voxa

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object TaskScheduler {

    private const val TAG = "TaskScheduler"
    private val gson = Gson()

    data class ScheduledTask(
        val id: String,
        val command: String,
        val delayMillis: Long = 0,
        val repeatInterval: Long = 0,
        val conditions: Map<String, Any> = emptyMap()
    )

    fun scheduleTask(context: Context, task: ScheduledTask) {
        val workRequest = createWorkRequest(task)

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                task.id,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Scheduled task: ${task.command} in ${task.delayMillis}ms")
    }

    fun scheduleAtTime(context: Context, command: String, hour: Int, minute: Int) {
        val currentTime = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
        }

        var delay = calendar.timeInMillis - currentTime
        if (delay < 0) {
            // Schedule for tomorrow
            delay += 24 * 60 * 60 * 1000
        }

        val task = ScheduledTask(
            id = "time_${hour}_${minute}",
            command = command,
            delayMillis = delay
        )

        scheduleTask(context, task)
    }

    fun scheduleRepeating(context: Context, command: String, intervalMinutes: Long) {
        val task = ScheduledTask(
            id = "repeat_${System.currentTimeMillis()}",
            command = command,
            delayMillis = 0,
            repeatInterval = intervalMinutes * 60 * 1000
        )

        scheduleTask(context, task)
    }

    private fun createWorkRequest(task: ScheduledTask): WorkRequest {
        val inputData = workDataOf(
            "task_json" to gson.toJson(task)
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        return if (task.repeatInterval > 0) {
            PeriodicWorkRequestBuilder<JarvisWorker>(
                task.repeatInterval, TimeUnit.MILLISECONDS
            )
                .setInputData(inputData)
                .setConstraints(constraints)
                .build()
        } else {
            OneTimeWorkRequestBuilder<JarvisWorker>()
                .setInputData(inputData)
                .setInitialDelay(task.delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
        }
    }

    fun cancelTask(context: Context, taskId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(taskId)
    }

    fun getAllScheduledTasks(context: Context): List<ScheduledTask> {
        // This would require querying WorkManager's database
        // Simplified implementation
        return emptyList()
    }
}

class JarvisWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val taskJson = inputData.getString("task_json")
            val task = Gson().fromJson(taskJson, TaskScheduler.ScheduledTask::class.java)

            Log.d("JarvisWorker", "Executing scheduled task: ${task.command}")

            // Execute the command
            CoroutineScope(Dispatchers.IO).launch {
                val actions = GeminiPlanner.planActions(task.command)
                if (actions.isNotEmpty()) {
                    ActionExecutor.execute(actions)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("JarvisWorker", "Task execution failed: ${e.message}")
            Result.failure()
        }
    }
}