package dk.bossen.phonebridge

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var phone: EditText
    private lateinit var message: EditText
    private lateinit var status: TextView
    private val sentAction = "dk.bossen.phonebridge.SMS_SENT"
    private val deliveredAction = "dk.bossen.phonebridge.SMS_DELIVERED"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                sentAction -> status.text = if (resultCode == RESULT_OK) "SMS afleveret til mobilnettet" else "SMS kunne ikke sendes (kode $resultCode)"
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
        findViewById<Button>(R.id.sendSms).setOnClickListener { confirmSms() }
        findViewById<Button>(R.id.call).setOnClickListener { confirmCall() }
        val prefs = getSharedPreferences("boss_phone_bridge", MODE_PRIVATE)
        val deviceToken = findViewById<EditText>(R.id.deviceToken)
        deviceToken.setText(prefs.getString("device_token", ""))
        findViewById<Button>(R.id.saveDeviceToken).setOnClickListener {
            prefs.edit().putString("device_token", deviceToken.text.toString().trim()).apply()
            status.text = "Device-token gemt lokalt."
        }
        findViewById<Button>(R.id.fetchActions).setOnClickListener {
            val token = prefs.getString("device_token", "").orEmpty()
            if (token.isBlank()) {
                status.text = "Indtast først BOSS_DEVICE_TOKEN."
            } else {
                Thread {
                    try {
                        val result = BossConnector.getActions(token)
                        runOnUiThread { status.text = "Connector svar:\n$result" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Connector-fejl: ${e.message}" }
                    }
                }.start()
            }
        }
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

    private fun normalizedNumber() = phone.text.toString().filterNot { it.isWhitespace() || it == '-' }
    private fun validNumber(n: String) = n.matches(Regex("^\\+?[0-9]{6,15}$"))

    private fun confirmSms() {
        val n = normalizedNumber(); val t = message.text.toString().trim()
        if (!validNumber(n)) { status.text = "Indtast et gyldigt telefonnummer"; return }
        if (t.isBlank()) { status.text = "Skriv en besked"; return }
        AlertDialog.Builder(this).setTitle("Send SMS?").setMessage("Til: $n\n\n$t")
            .setNegativeButton("Annuller", null).setPositiveButton("Send") { _, _ -> sendSms(n, t) }.show()
    }

    private fun sendSms(number: String, text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101); status.text = "Tillad SMS og tryk Send igen"; return
        }
        try {
            val sms = getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(text)
            val sent = parts.indices.map { PendingIntent.getBroadcast(this, 1000 + it, Intent(sentAction).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE) }
            val delivered = parts.indices.map { PendingIntent.getBroadcast(this, 2000 + it, Intent(deliveredAction).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE) }
            sms.sendMultipartTextMessage(number, null, parts, ArrayList(sent), ArrayList(delivered))
            status.text = "Sender SMS…"
        } catch (e: Exception) { status.text = "SMS-fejl: ${e.localizedMessage ?: e.javaClass.simpleName}" }
    }

    private fun confirmCall() {
        val n = normalizedNumber()
        if (!validNumber(n)) { status.text = "Indtast et gyldigt telefonnummer"; return }
        AlertDialog.Builder(this).setTitle("Ring op?").setMessage("Ring til $n?")
            .setNegativeButton("Annuller", null).setPositiveButton("Ring") { _, _ -> callNumber(n) }.show()
    }

    private fun callNumber(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 102); status.text = "Tillad opkald og tryk Ring igen"; return
        }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))); status.text = "Starter opkald…" }
        catch (e: Exception) { status.text = "Opkaldsfejl: ${e.localizedMessage ?: e.javaClass.simpleName}" }
    }
}
