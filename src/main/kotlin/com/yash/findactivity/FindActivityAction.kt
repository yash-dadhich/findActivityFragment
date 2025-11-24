package com.yash.findactivity

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.awt.Dimension
import javax.swing.*

class FindActivityAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val device = getConnectedDevice(project)
        if (device == null) {
            showNotification(project, "No ADB device connected", NotificationType.WARNING)
            return
        }

        val activityInfo = getCurrentActivityInfo(device)
        if (activityInfo.isEmpty()) {
            showNotification(project, "Could not retrieve activity information", NotificationType.ERROR)
            return
        }

        ActivityInfoDialog(project, activityInfo).show()
    }

    private fun getConnectedDevice(project: Project): IDevice? {
        val bridge = AndroidSdkUtils.getDebugBridge(project) ?: return null
        val devices = bridge.devices
        return devices.firstOrNull { it.isOnline }
    }

    private fun getCurrentActivityInfo(device: IDevice): String {
        val result = StringBuilder()

        try {
            // Get current activity (works on Android 7-14)
            val activityCmd = "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity|topResumedActivity'"
            val activityOutput = executeShellCommand(device, activityCmd)

            result.append("=== Current Activity ===\n")
            result.append(parseActivityInfo(activityOutput))
            result.append("\n\n")

            // Get fragments
            val fragmentCmd = "dumpsys activity top"
            val fragmentOutput = executeShellCommand(device, fragmentCmd)

            result.append("=== Fragments ===\n")
            result.append(parseFragmentInfo(fragmentOutput))

        } catch (e: Exception) {
            result.append("Error: ${e.message}")
        }

        return result.toString()
    }

    private fun executeShellCommand(device: IDevice, command: String): String {
        val receiver = CollectingOutputReceiver()
        device.executeShellCommand(command, receiver, 5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return receiver.output.toString()
    }

    private fun parseActivityInfo(output: String): String {
        val result = StringBuilder()
        val lines = output.split("\n")

        for (line in lines) {
            when {
                line.contains("mResumedActivity") ||
                        line.contains("mFocusedActivity") ||
                        line.contains("topResumedActivity") -> {

                    // Extract package and activity name
                    val regex = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)")
                    val match = regex.find(line)

                    if (match != null) {
                        val packageName = match.groupValues[1]
                        val activityName = match.groupValues[2]

                        result.append("Package: $packageName\n")
                        result.append("Activity: $activityName\n")
                    } else {
                        result.append(line.trim()).append("\n")
                    }
                }
            }
        }

        if (result.isEmpty()) {
            result.append("No activity found")
        }

        return result.toString()
    }

    private fun parseFragmentInfo(output: String): String {
        val result = StringBuilder()
        val lines = output.split("\n")
        var inFragmentSection = false
        var fragmentCount = 0

        for (line in lines) {
            when {
                line.contains("Active Fragments") -> {
                    inFragmentSection = true
                    result.append("Active Fragments:\n")
                }
                line.contains("Added Fragments") -> {
                    inFragmentSection = true
                    result.append("\nAdded Fragments:\n")
                }
                inFragmentSection && line.trim().startsWith("#") -> {
                    // Parse fragment entry
                    val fragmentInfo = line.trim()
                    result.append("  $fragmentInfo\n")
                    fragmentCount++
                }
                line.trim().isEmpty() && inFragmentSection -> {
                    inFragmentSection = false
                }
            }
        }

        if (fragmentCount == 0) {
            result.append("No fragments found or not using FragmentManager")
        }

        return result.toString()
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("FindActivity")
            .createNotification(content, type)
            .notify(project)
    }

    class CollectingOutputReceiver : com.android.ddmlib.IShellOutputReceiver {
        val output = StringBuilder()

        override fun addOutput(data: ByteArray, offset: Int, length: Int) {
            output.append(String(data, offset, length))
        }

        override fun flush() {}
        override fun isCancelled() = false
    }
}

class ActivityInfoDialog(project: Project, private val info: String) : DialogWrapper(project) {

    init {
        title = "Current Activity & Fragments"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JTextArea(info).apply {
            isEditable = false
            font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
            lineWrap = false
        }

        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(600, 400)

        return scrollPane
    }
}

// File: build.gradle.kts
