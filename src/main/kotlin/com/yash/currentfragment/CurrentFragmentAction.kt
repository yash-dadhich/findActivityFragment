package com.yash.currentfragment

import com.android.ddmlib.IDevice
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBEmptyBorder
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.awt.Dimension
import javax.swing.*

data class ActivityFragmentResult(
    val activityClass: String?,
    val fragmentClasses: List<String>,
    val formattedText: String
)

data class FragmentParseResult(
    val qualifiedClasses: List<String>,
    val simpleClasses: List<String>,
    val formattedText: String
)

class CurrentFragmentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val device = getConnectedDevice(project)
        if (device == null) {
            showNotification(project, "No ADB device connected", NotificationType.WARNING)
            return
        }

        val activityInfo = getCurrentActivityInfo(device)
        if (activityInfo == null) {
            showNotification(project, "Could not retrieve activity information", NotificationType.ERROR)
            return
        }

        ActivityInfoDialog(project, activityInfo) { className ->
            navigateToClass(project, className)
        }.show()
    }

    private fun getConnectedDevice(project: Project): IDevice? {
        val bridge = AndroidSdkUtils.getDebugBridge(project) ?: return null
        val devices = bridge.devices
        return devices.firstOrNull { it.isOnline }
    }

    private fun getCurrentActivityInfo(device: IDevice): ActivityFragmentResult? {
        val formatted = StringBuilder()

        try {
            // Get current activity (works on Android 7-14)
            val activityCmd = "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity|topResumedActivity'"
            val activityOutput = executeShellCommand(device, activityCmd)

            val (activityClass, activityText) = parseActivityInfo(activityOutput)
            formatted.append("=== Current Activity ===\n")
            formatted.append(activityText)
            formatted.append("\n\n")

            // Get fragments
            val fragmentCmd = "dumpsys activity top"
            val fragmentOutput = executeShellCommand(device, fragmentCmd)

            val fragmentResult = parseFragmentInfo(fragmentOutput)
            formatted.append("=== Fragments ===\n")
            formatted.append(fragmentResult.formattedText)

            val basePackage = activityClass?.substringBeforeLast('.', missingDelimiterValue = "")

            val candidateFragments = LinkedHashSet<String>().apply {
                addAll(fragmentResult.qualifiedClasses)
                if (!basePackage.isNullOrEmpty()) {
                    fragmentResult.simpleClasses.forEach { simple ->
                        add("$basePackage.$simple")
                    }
                }
            }.toList()

            // Heuristic: only treat fragments from the same app package as "project" fragments
            val filteredFragments = if (!basePackage.isNullOrEmpty()) {
                candidateFragments.filter { it.startsWith(basePackage) }
            } else {
                candidateFragments
            }

            return ActivityFragmentResult(activityClass, filteredFragments, formatted.toString())

        } catch (e: Exception) {
            formatted.append("Error: ${e.message}")
        }

        return ActivityFragmentResult(null, emptyList(), formatted.toString())
    }

    private fun executeShellCommand(device: IDevice, command: String): String {
        val receiver = CollectingOutputReceiver()
        device.executeShellCommand(command, receiver, 5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return receiver.output.toString()
    }

    private fun parseActivityInfo(output: String): Pair<String?, String> {
        val result = StringBuilder()
        val lines = output.split("\n")
        var detectedClass: String? = null

        for (line in lines) {
            when {
                line.contains("mResumedActivity") ||
                        line.contains("mFocusedActivity") ||
                        line.contains("topResumedActivity") -> {

                    // Extract package and activity name
                    val regex = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_./$]+)")
                    val match = regex.find(line)

                    if (match != null) {
                        val packageName = match.groupValues[1]
                        val activityName = match.groupValues[2]

                        val fqcn = when {
                            activityName.startsWith(".") -> packageName + activityName
                            activityName.contains(".") -> activityName
                            else -> "$packageName.$activityName"
                        }

                        if (detectedClass == null) {
                            detectedClass = fqcn
                        }

                        result.append("Package: $packageName\n")
                        result.append("Activity: $fqcn\n")
                    } else {
                        result.append(line.trim()).append("\n")
                    }
                }
            }
        }

        if (result.isEmpty()) {
            result.append("No activity found")
        }

        return detectedClass to result.toString()
    }

    private fun parseFragmentInfo(output: String): FragmentParseResult {
        val result = StringBuilder()
        val lines = output.split("\n")
        var inFragmentSection = false
        var fragmentCount = 0
        val qualified = linkedSetOf<String>()
        val simple = linkedSetOf<String>()
        val classRegex = Regex("([a-zA-Z_]\\w*(?:\\.[a-zA-Z_]\\w*)+)")
        val simpleRegex = Regex("\\b([A-Z][A-Za-z0-9_]*Fragment)\\b")

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

                    val fqcn = classRegex.find(fragmentInfo)?.value
                    if (fqcn != null) {
                        qualified.add(fqcn)
                    } else {
                        simpleRegex.findAll(fragmentInfo)
                            .map { it.groupValues[1] }
                            .filter { !it.contains('.') }
                            .forEach { simple.add(it) }
                    }
                }
                line.trim().isEmpty() && inFragmentSection -> {
                    inFragmentSection = false
                }
            }
        }

        if (fragmentCount == 0) {
            result.append("No fragments found or not using FragmentManager")
        }

        return FragmentParseResult(
            qualifiedClasses = qualified.toList(),
            simpleClasses = simple.toList(),
            formattedText = result.toString()
        )
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

    private fun navigateToClass(project: Project, className: String) {
        ApplicationManager.getApplication().invokeLater {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
            if (psiClass != null) {
                PsiNavigationSupport.getInstance()
                    .createNavigatable(project, psiClass.containingFile.virtualFile, psiClass.textOffset)
                    .navigate(true)
            } else {
                showNotification(project, "Class $className not found in project", NotificationType.WARNING)
            }
        }
    }
}

class ActivityInfoDialog(
    project: Project,
    private val result: ActivityFragmentResult,
    private val onClassSelected: (String) -> Unit
) : DialogWrapper(project) {

    init {
        title = "Current Activity & Fragments"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBEmptyBorder(10)
            isOpaque = false
        }

        val activityContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = JPanel.LEFT_ALIGNMENT
        }
        if (result.activityClass != null) {
            activityContent.add(createLink(result.activityClass))
        } else {
            activityContent.add(JLabel("Not detected"))
        }

        val fragmentContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = JPanel.LEFT_ALIGNMENT
        }
        if (result.fragmentClasses.isNotEmpty()) {
            result.fragmentClasses.forEach { fqcn ->
                fragmentContent.add(createLink(fqcn))
                fragmentContent.add(Box.createVerticalStrut(6))
            }
        } else {
            fragmentContent.add(JLabel("No fragments detected or FragmentManager not in use"))
        }

        val textArea = JTextArea(result.formattedText).apply {
            isEditable = false
            font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
            lineWrap = false
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(600, 250)
        }

        container.add(createTile("Activity", activityContent))
        container.add(Box.createVerticalStrut(12))
        container.add(createTile("Fragments", fragmentContent))
        container.add(Box.createVerticalStrut(12))
        container.add(createTile("ADB Output", scrollPane))

        return container
    }

    private fun createLink(fqcn: String): JComponent {
        val link = LinkLabel.create(fqcn, Runnable { onClassSelected(fqcn) })
        link.toolTipText = "Open $fqcn"
        return link
    }

    private fun createTile(title: String, content: JComponent): JComponent {
        val panel = JPanel(java.awt.BorderLayout()).apply {
            border = JBEmptyBorder(12)
            background = JBColor(0xF7F9FC, 0x2D2F31)
            alignmentX = JPanel.LEFT_ALIGNMENT
        }

        val header = JLabel(title.uppercase()).apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 1f)
        }

        panel.add(header, java.awt.BorderLayout.NORTH)
        panel.add(content, java.awt.BorderLayout.CENTER)

        return panel
    }
}

// File: build.gradle.kts
