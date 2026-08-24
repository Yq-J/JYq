package com.yqj.onesignaltester

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var sdkVersionView: TextView
    private lateinit var oneSignalIdView: TextView
    private lateinit var externalIdView: TextView
    private lateinit var subscriptionIdView: TextView
    private lateinit var tokenView: TextView
    private lateinit var permissionView: TextView
    private lateinit var optedInView: TextView
    private lateinit var externalIdInput: EditText
    private lateinit var tagKeyInput: EditText
    private lateinit var tagValueInput: EditText

    private val stateListener: (OneSignalManager.State) -> Unit = { state ->
        render(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        OneSignalManager.addStateListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        OneSignalManager.attachActivity(this)
    }

    override fun onPause() {
        OneSignalManager.detachActivity(this)
        super.onPause()
    }

    override fun onDestroy() {
        OneSignalManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "OneSignal 推送测试"
            textSize = 28f
            setTextColor(Color.rgb(32, 33, 36))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "查看设备注册状态、复制 Subscription ID，并测试 External ID 与 Tag。"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(16))
        })

        statusView = valueText("正在初始化…")
        root.addView(sectionTitle("连接状态"))
        root.addView(statusView)
        root.addView(labelValue("OneSignal App ID", BuildConfig.ONESIGNAL_APP_ID))
        root.addView(labelValue("Firebase Project ID", BuildConfig.FIREBASE_PROJECT_ID))

        sdkVersionView = valueText("—")
        oneSignalIdView = valueText("—")
        externalIdView = valueText("—")
        subscriptionIdView = valueText("—")
        tokenView = valueText("—")
        permissionView = valueText("—")
        optedInView = valueText("—")

        root.addView(sectionTitle("设备信息"))
        root.addView(labeledView("SDK 版本", sdkVersionView))
        root.addView(labeledView("OneSignal ID", oneSignalIdView))
        root.addView(labeledView("External ID", externalIdView))
        root.addView(labeledView("Push Subscription ID", subscriptionIdView))
        root.addView(labeledView("FCM Token", tokenView))
        root.addView(labeledView("系统通知权限", permissionView))
        root.addView(labeledView("OneSignal 订阅状态", optedInView))

        root.addView(
            horizontalButtons(
                button("刷新状态") { OneSignalManager.refresh() },
                button("复制 Subscription ID") {
                    copyToClipboard("Push Subscription ID", subscriptionIdView.text.toString())
                },
            ),
        )

        root.addView(sectionTitle("External ID 测试"))
        externalIdInput = input("例如：test-user-001")
        root.addView(externalIdInput)
        root.addView(
            horizontalButtons(
                button("登录/绑定") {
                    OneSignalManager.login(externalIdInput.text.toString())
                    toast("已提交登录操作")
                },
                button("退出绑定") {
                    OneSignalManager.logout()
                    toast("已提交退出操作")
                },
            ),
        )

        root.addView(sectionTitle("Tag 测试"))
        tagKeyInput = input("Tag Key，例如 environment")
        tagValueInput = input("Tag Value，例如 local-test")
        root.addView(tagKeyInput)
        root.addView(tagValueInput)
        root.addView(
            horizontalButtons(
                button("保存 Tag") {
                    OneSignalManager.addTag(
                        tagKeyInput.text.toString(),
                        tagValueInput.text.toString(),
                    )
                    toast("已提交 Tag 操作")
                },
                button("删除 Tag") {
                    OneSignalManager.removeTag(tagKeyInput.text.toString())
                    toast("已提交删除操作")
                },
            ),
        )

        root.addView(TextView(this).apply {
            text = "提示：本 App 不包含 OneSignal REST API Key。请在 OneSignal 控制台向本设备发送测试消息。首次获得真实 Subscription ID 后，应用会弹出确认框；点击 Got it 后才会请求通知权限。"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun render(state: OneSignalManager.State) {
        runOnUiThread {
            statusView.text = when {
                state.error != null -> "异常：${state.error}"
                state.initializing -> "正在初始化 OneSignal SDK…"
                state.initialized && isServerAssigned(state.subscriptionId) -> "注册成功，可以发送测试推送"
                state.initialized -> "SDK 已初始化，等待 OneSignal 分配 Subscription ID…"
                else -> "尚未初始化"
            }
            statusView.setTextColor(
                when {
                    state.error != null -> Color.rgb(183, 28, 28)
                    state.initialized && isServerAssigned(state.subscriptionId) -> Color.rgb(27, 94, 32)
                    else -> Color.rgb(57, 73, 171)
                },
            )

            sdkVersionView.text = state.sdkVersion.ifBlank { "—" }
            oneSignalIdView.text = state.oneSignalId.ifBlank { "—" }
            externalIdView.text = state.externalId.ifBlank { "未绑定" }
            subscriptionIdView.text = state.subscriptionId.ifBlank { "—" }
            tokenView.text = state.pushToken.ifBlank { "—" }
            permissionView.text = if (state.notificationPermission) "已允许" else "未允许"
            optedInView.text = if (state.optedIn) "已订阅" else "未订阅"
        }
    }

    private fun isServerAssigned(id: String): Boolean {
        return id.isNotBlank() && !id.startsWith("local-")
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.rgb(32, 33, 36))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun valueText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(50, 50, 50))
        setTextIsSelectable(true)
        setPadding(0, dp(3), 0, dp(8))
    }

    private fun labelValue(label: String, value: String): LinearLayout {
        return labeledView(label, valueText(value))
    }

    private fun labeledView(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        addView(value)
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 0, 0, dp(8))
        }
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun horizontalButtons(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(6))
        buttons.forEach { addView(it) }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank() || text == "—") {
            toast("当前还没有可复制的 ID")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
