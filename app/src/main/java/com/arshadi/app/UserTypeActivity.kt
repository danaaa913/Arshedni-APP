package com.arshadi.app

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class UserTypeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_type)
        tts = TextToSpeech(this, this)

        findViewById<View>(R.id.btnSighted).setOnClickListener {
            tts.stop()
            startActivity(Intent(this, SightedActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.btnBlind).setOnClickListener {
            tts.stop()
            startActivity(Intent(this, BlindActivity::class.java))
            finish()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ar", "SA")
            tts.setSpeechRate(0.42f)
            ttsReady = true
            tts.speak(
                "أهلاً بك في أرشِدني. " +
                        "إذا كنت مبصراً اضغط على النصف العلوي من الشاشة. " +
                        "إذا كنت كفيفاً اضغط على النصف السفلي من الشاشة.",
                TextToSpeech.QUEUE_FLUSH, null, "welcome"
            )
        }
    }

    override fun onDestroy() {
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
