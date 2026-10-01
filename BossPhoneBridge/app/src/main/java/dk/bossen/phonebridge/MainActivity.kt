package dk.bossen.phonebridge

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.graphics.Typeface
import android.view.View
import android.widget.*
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var phone: EditText
    private lateinit var message: EditText
    private lateinit var status: TextView
    private lateinit var actionList: LinearLayout
    private lateinit var fetchActionsButton: Button
    private val actionRows = mutableMapOf<String, ActionRow>()
    private val smsProgress = mutableMapOf<String, SmsProgress>()
    private var pendingPermissionAction: DeviceAction? = null
    private var pendingPermissionCommand: DeviceCommand? = null
    private var nextSmsRequestCode = 10000
    private val sentAction = "dk.bossen.phonebridge.SMS_SENT"
    private val deliveredAction = "dk.bossen.phonebridge.SMS_DELIVERED"

    private data class ActionRow(
        val status: TextView,
        val approve: Button?,
        val reject: Button
    )

    private data class SmsProgress(
        val partCount: Int,
        val sentParts: MutableSet<Int> = mutableSetOf()
    )

    private sealed class DeviceCommand {
        data class SendSms(val number: String, val text: String) : DeviceCommand()
        data class StartCall(val number: String) : DeviceCommand()
        data class OpenApp(val packageName: String) : DeviceCommand()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                sentAction -> {
                    val actionId = intent.getStringExtra(EXTRA_DEVICE_ACTION_ID)
                    if (actionId == null) {
                        status.text = if (resultCode == RESULT_OK) "SMS afleveret til mobilnettet" else "SMS kunne ikke sendes (kode $resultCode)"
                    } else {
                        handleDeviceSmsResult(actionId, intent.getIntExtra(EXTRA_SMS_PART, 0))
                    }
                }
                deliveredAction -> status.text = if (resultCode == RESULT_OK) "SMS leveret til modtager" else "Leveringskvittering ikke bekræftet"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        phone = findViewById(R.id.phone)
        message = findViewById(R.id.message)
        status = findViewById(R.id.status)
        actionList = findViewById(R.id.actionList)
        findViewById<Button>(R.id.sendSms).setOnClickListener { confirmSms() }
        findViewById<Button>(R.id.call).setOnClickListener { confirmCall() }
        val prefs = getSharedPreferences("boss_phone_bridge", MODE_PRIVATE)
        val deviceToken = findViewById<EditText>(R.id.deviceToken)
        deviceToken.setText(prefs.getString("device_token", ""))
        findViewById<Button>(R.id.saveDeviceToken).setOnClickListener {
            prefs.edit().putString("device_token", deviceToken.text.toString().trim()).apply()
            status.text = "Device-token gemt lokalt."
        }
        fetchActionsButton = findViewById(R.id.fetchActions)
        fetchActionsButton.setOnClickListener { fetchPendingActions() }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply { addAction(sentAction); addAction(deliveredAction) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        importIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); importIntent(intent) }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }

    private fun importIntent(i: Intent?) {
        val number = i?.getStringExtra("phone") ?: i?.data?.getQueryParameter("phone")
        val text = i?.getStringExtra("message") ?: i?.data?.getQueryParameter("message")
        if (!number.isNullOrBlank()) phone.setText(number)
        if (!text.isNullOrBlank()) message.setText(text)
        if (!number.isNullOrBlank() || !text.isNullOrBlank()) status.text = "Kommando indlæst – kontrollér og tryk Send/Ring"
    }

    private fun normalizeNumber(value: String) = value.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' }
    private fun normalizedNumber() = normalizeNumber(phone.text.toString())
    private fun validNumber(n: String) = n.matches(Regex("^\\+?[0-9]{6,15}$"))

    private fun deviceToken() = getSharedPreferences("boss_phone_bridge", MODE_PRIVATE)
        .getString("device_token", "").orEmpty()

    private fun fetchPendingActions() {
        val token = deviceToken()
        if (token.isBlank()) {
            status.text = "Indtast og gem først BOSS_DEVICE_TOKEN."
            return
        }
        fetchActionsButton.isEnabled = false
        status.text = "Henter ventende handlinger…"
        Thread {
            try {
                val actions = BossConnector.getActions(token)
                runOnUiThread {
                    displayActions(actions)
                    status.text = if (actions.isEmpty()) "Ingen ventende handlinger." else "${actions.size} handling(er) afventer din godkendelse."
                    fetchActionsButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Connector-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    fetchActionsButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun displayActions(actions: List<DeviceAction>) {
        actionRows.clear()
        actionList.removeAllViews()
        if (actions.isEmpty()) {
            actionList.addView(TextView(this).apply {
                text = "Ingen ventende handlinger."
                textSize = 16f
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        actions.forEachIndexed { index, action ->
            if (index > 0) actionList.addView(View(this).apply {
                setBackgroundColor(0xFFD8D8D8.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            })
            actionList.addView(createActionRow(action))
        }
    }

    private fun createActionRow(action: DeviceAction): View {
        val command = commandFor(action)
        val details = actionDetails(action)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(TextView(this).apply {
            text = details.first
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = details.second
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        })
        val rowStatus = TextView(this).apply {
            text = if (command == null) "Ukendt handling eller ugyldige data; kan kun afvises." else "Afventer din godkendelse."
            textSize = 14f
        }
        container.addView(rowStatus)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rejectButton = Button(this).apply {
            text = "Afvis"
            setOnClickListener { rejectAction(action) }
        }
        val approveButton = if (command == null) null else Button(this).apply {
            text = "Godkend og udfør"
            setOnClickListener { approveAction(action, command) }
        }
        if (approveButton != null) {
            buttons.addView(approveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
        buttons.addView(rejectButton, LinearLayout.LayoutParams(
            if (approveButton == null) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (approveButton == null) 0f else 1f
        ))
        container.addView(buttons)
        actionRows[action.id] = ActionRow(rowStatus, approveButton, rejectButton)
        return container
    }

    private fun actionDetails(action: DeviceAction): Pair<String, String> = when (action.type) {
        "send_sms" -> "SMS" to "Til: ${action.payload.optString("to", "(mangler nummer)")}\n\nBesked:\n${action.payload.optString("message", "(mangler besked)")}"
        "start_call" -> "Telefonopkald" to "Ring til: ${action.payload.optString("to", "(mangler nummer)")}"
        "open_chatgpt" -> "Åbn ChatGPT" to "App: com.openai.chatgpt"
        "open_app" -> "Åbn app" to "Pakkenavn: ${action.payload.optString("package_name", "(mangler pakkenavn)")}"
        else -> "Ukendt handling: ${action.type}" to action.payload.toString(2)
    }

    private fun commandFor(action: DeviceAction): DeviceCommand? = when (action.type) {
        "send_sms" -> {
            val number = normalizeNumber(action.payload.optString("to", ""))
            val text = action.payload.optString("message", "").trim()
            if (validNumber(number) && text.isNotBlank() && text.length <= 5000) DeviceCommand.SendSms(number, text) else null
        }
        "start_call" -> {
            val number = normalizeNumber(action.payload.optString("to", ""))
            if (validNumber(number)) DeviceCommand.StartCall(number) else null
        }
        "open_chatgpt" -> DeviceCommand.OpenApp("com.openai.chatgpt")
        "open_app" -> {
            val packageName = action.payload.optString("package_name", "")
            if (packageName.matches(Regex("^[A-Za-z0-9._]+$"))) DeviceCommand.OpenApp(packageName) else null
        }
        else -> null
    }

    private fun approveAction(action: DeviceAction, command: DeviceCommand) {
        if (command is DeviceCommand.OpenApp && packageManager.getLaunchIntentForPackage(command.packageName) == null) {
            setActionStatus(action.id, "Appen er ikke installeret eller kan ikke åbnes.", true)
            return
        }
        val permission = when (command) {
            is DeviceCommand.SendSms -> Manifest.permission.SEND_SMS to DEVICE_SMS_PERMISSION_REQUEST
            is DeviceCommand.StartCall -> Manifest.permission.CALL_PHONE to DEVICE_CALL_PERMISSION_REQUEST
            is DeviceCommand.OpenApp -> null
        }
        if (permission != null && ContextCompat.checkSelfPermission(this, permission.first) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = action
            pendingPermissionCommand = command
            setActionStatus(action.id, "Android-tilladelse kræves før godkendelsen kan sendes.", false)
            ActivityCompat.requestPermissions(this, arrayOf(permission.first), permission.second)
            return
        }
        approveAndExecute(action, command)
    }

    private fun approveAndExecute(action: DeviceAction, command: DeviceCommand) {
        val token = deviceToken()
        if (token.isBlank()) {
            setActionStatus(action.id, "Token mangler. Gem tokenet og hent handlingerne igen.", true)
            return
        }
        setActionStatus(action.id, "Sender godkendelse…", false)
        Thread {
            try {
                BossConnector.updateAction(token, action.id, "approved")
                runOnUiThread {
                    setActionStatus(action.id, "Godkendt; udfører handlingen…", false)
                    try {
                        executeAction(action.id, command)
                    } catch (e: Exception) {
                        setActionStatus(action.id, "Godkendt, men kunne ikke udføres: ${e.localizedMessage ?: e.javaClass.simpleName}", false)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionStatus(action.id, "Godkendelsesstatus ukendt. Hent handlingerne igen før forsøg igen.", false)
                    status.text = "Connector-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun rejectAction(action: DeviceAction) {
        val token = deviceToken()
        if (token.isBlank()) {
            setActionStatus(action.id, "Token mangler. Gem tokenet og hent handlingerne igen.", true)
            return
        }
        setActionStatus(action.id, "Sender afvisning…", false)
        Thread {
            try {
                BossConnector.updateAction(token, action.id, "rejected")
                runOnUiThread { setActionStatus(action.id, "Afvist.", false) }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionStatus(action.id, "Afvisningsstatus ukendt. Hent handlingerne igen.", false)
                    status.text = "Connector-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun executeAction(actionId: String, command: DeviceCommand) {
        when (command) {
            is DeviceCommand.SendSms -> sendSms(command.number, command.text, actionId)
            is DeviceCommand.StartCall -> callNumber(command.number, actionId)
            is DeviceCommand.OpenApp -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(command.packageName)
                    ?: throw IllegalStateException("Appen er ikke installeret eller kan ikke åbnes")
                startActivity(launchIntent)
                setActionStatus(actionId, "App åbnet.", false)
                reportCompleted(actionId)
            }
        }
    }

    private fun setActionStatus(actionId: String, text: String, enableButtons: Boolean) {
        actionRows[actionId]?.let { row ->
            row.status.text = text
            row.approve?.isEnabled = enableButtons
            row.reject.isEnabled = enableButtons
        }
    }

    private fun reportCompleted(actionId: String) {
        setActionStatus(actionId, "Udført på telefonen; rapporterer til connectoren…", false)
        val token = deviceToken()
        Thread {
            try {
                BossConnector.updateAction(token, actionId, "completed")
                runOnUiThread { setActionStatus(actionId, "Fuldført og rapporteret.", false) }
            } catch (e: Exception) {
                runOnUiThread {
                    setActionStatus(actionId, "Udført på telefonen, men status kunne ikke rapporteres.", false)
                    status.text = "Connector-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun handleDeviceSmsResult(actionId: String, part: Int) {
        val progress = smsProgress[actionId] ?: return
        if (resultCode != RESULT_OK) {
            smsProgress.remove(actionId)
            setActionStatus(actionId, "SMS kunne ikke sendes via SIM (kode $resultCode).", false)
            status.text = "SMS-fejl: kode $resultCode"
            return
        }
        progress.sentParts.add(part)
        if (progress.sentParts.size == progress.partCount) {
            smsProgress.remove(actionId)
            status.text = "SMS afleveret til mobilnettet"
            reportCompleted(actionId)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEVICE_SMS_PERMISSION_REQUEST || requestCode == DEVICE_CALL_PERMISSION_REQUEST) {
            val action = pendingPermissionAction
            val command = pendingPermissionCommand
            pendingPermissionAction = null
            pendingPermissionCommand = null
            if (action != null && command != null) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    approveAndExecute(action, command)
                } else {
                    setActionStatus(action.id, "Tilladelse afvist; handlingen er stadig afventende.", true)
                }
            }
        }
    }

    private fun confirmSms() {
        val n = normalizedNumber(); val t = message.text.toString().trim()
        if (!validNumber(n)) { status.text = "Indtast et gyldigt telefonnummer"; return }
        if (t.isBlank()) { status.text = "Skriv en besked"; return }
        AlertDialog.Builder(this).setTitle("Send SMS?").setMessage("Til: $n\n\n$t")
            .setNegativeButton("Annuller", null).setPositiveButton("Send") { _, _ -> sendSms(n, t) }.show()
    }

    private fun sendSms(number: String, text: String, deviceActionId: String? = null) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), MANUAL_SMS_PERMISSION_REQUEST); status.text = "Tillad SMS og tryk Send igen"; return
        }
        try {
            val sms = getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(text)
            if (deviceActionId != null) smsProgress[deviceActionId] = SmsProgress(parts.size)
            val sent = parts.indices.map { index ->
                val callback = Intent(sentAction).setPackage(packageName)
                if (deviceActionId != null) {
                    callback.putExtra(EXTRA_DEVICE_ACTION_ID, deviceActionId)
                    callback.putExtra(EXTRA_SMS_PART, index)
                }
                PendingIntent.getBroadcast(this, nextSmsRequestCode++, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val delivered = parts.indices.map {
                PendingIntent.getBroadcast(this, nextSmsRequestCode++, Intent(deliveredAction).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            sms.sendMultipartTextMessage(number, null, parts, ArrayList(sent), ArrayList(delivered))
            status.text = "Sender SMS…"
        } catch (e: Exception) {
            if (deviceActionId != null) {
                smsProgress.remove(deviceActionId)
                setActionStatus(deviceActionId, "SMS kunne ikke startes: ${e.localizedMessage ?: e.javaClass.simpleName}", false)
            }
            status.text = "SMS-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    private fun confirmCall() {
        val n = normalizedNumber()
        if (!validNumber(n)) { status.text = "Indtast et gyldigt telefonnummer"; return }
        AlertDialog.Builder(this).setTitle("Ring op?").setMessage("Ring til $n?")
            .setNegativeButton("Annuller", null).setPositiveButton("Ring") { _, _ -> callNumber(n) }.show()
    }

    private fun callNumber(number: String, deviceActionId: String? = null) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), MANUAL_CALL_PERMISSION_REQUEST); status.text = "Tillad opkald og tryk Ring igen"; return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")))
            status.text = "Starter opkald…"
            if (deviceActionId != null) reportCompleted(deviceActionId)
        } catch (e: Exception) {
            if (deviceActionId != null) setActionStatus(deviceActionId, "Opkaldet kunne ikke startes: ${e.localizedMessage ?: e.javaClass.simpleName}", false)
            status.text = "Opkaldsfejl: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val MANUAL_SMS_PERMISSION_REQUEST = 101
        private const val MANUAL_CALL_PERMISSION_REQUEST = 102
        private const val DEVICE_SMS_PERMISSION_REQUEST = 103
        private const val DEVICE_CALL_PERMISSION_REQUEST = 104
        private const val EXTRA_DEVICE_ACTION_ID = "device_action_id"
        private const val EXTRA_SMS_PART = "sms_part"
    }
}
