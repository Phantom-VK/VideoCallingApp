package com.developerspace.webrtcsample

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.dwarsh.webrtcsample.databinding.ActivityStartBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.ExperimentalCoroutinesApi

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStartBinding
    val db = Firebase.firestore

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Constants.isIntiatedNow = true
        Constants.isCallEnded = true

        binding.startMeeting.setOnClickListener {
            if (binding.meetingId.text.toString().trim().isEmpty()) {
                binding.meetingId.error = "Please enter meeting id"
            } else {
                db.collection("calls")
                    .document(binding.meetingId.text.toString())
                    .get()
                    .addOnSuccessListener { document ->
                        if (document["type"] == "OFFER" || document["type"] == "ANSWER" || document["type"] == "END_CALL") {
                            binding.meetingId.error = "Please enter new meeting ID"
                        } else {
                            val intent = Intent(this@MainActivity, RTCActivity::class.java)
                            intent.putExtra("meetingID", binding.meetingId.text.toString())
                            intent.putExtra("isJoin", false)
                            startActivity(intent)
                        }
                    }
                    .addOnFailureListener {
                        binding.meetingId.error = "Please enter new meeting ID"
                    }
            }
        }

        binding.joinMeeting.setOnClickListener {
            if (binding.meetingId.text.toString().trim().isEmpty()) {
                binding.meetingId.error = "Please enter meeting id"
            } else {
                val intent = Intent(this@MainActivity, RTCActivity::class.java)
                intent.putExtra("meetingID", binding.meetingId.text.toString())
                intent.putExtra("isJoin", true)
                startActivity(intent)
            }
        }
    }
}