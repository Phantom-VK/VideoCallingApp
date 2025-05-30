package com.developerspace.webrtcsample

import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

@ExperimentalCoroutinesApi
class SignalingClient(
    private val meetingID : String,
    private val listener: SignalingClientListener
) : CoroutineScope {

    companion object {
        private const val HOST_ADDRESS = "192.168.0.12"
    }

    var jsonObject : JSONObject?= null

    private val job = Job()

    val tag = "SignallingClient"

    val db = Firebase.firestore

    private val gson = Gson()

    var sdpTYpe : String? = null
    override val coroutineContext = Dispatchers.IO + job

//    private val client = HttpClient(CIO) {
//        install(WebSockets)
//        install(JsonFeature) {
//            serializer = GsonSerializer()
//        }
//    }


    init {
        connect()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun connect() = launch {
        db.enableNetwork().addOnSuccessListener {
            listener.onConnectionEstablished()
        }

        try {
            db.collection("calls")
                .document(meetingID)
                .addSnapshotListener { snapshot, e ->

                    if (e != null) {
                        Log.w(tag, "listen:error", e)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data
                        if (data?.containsKey("type")!! &&
                            data.getValue("type").toString() == "OFFER") {
                                listener.onOfferReceived(SessionDescription(
                                    SessionDescription.Type.OFFER,data["sdp"].toString()))
                            sdpTYpe = "Offer"
                        } else if (data.containsKey("type") == true &&
                            data.getValue("type").toString() == "ANSWER") {
                                listener.onAnswerReceived(SessionDescription(
                                    SessionDescription.Type.ANSWER,data["sdp"].toString()))
                            sdpTYpe = "Answer"
                        } else if (!Constants.isIntiatedNow && data.containsKey("type") &&
                            data.getValue("type").toString() == "END_CALL") {
                            listener.onCallEnded()
                            sdpTYpe = "End Call"

                        }
                        Log.d(tag, "Current data: ${snapshot.data}")
                    } else {
                        Log.d(tag, "Current data: null")
                    }
                }
            db.collection("calls").document(meetingID)
                    .collection("candidates").addSnapshotListener{ querysnapshot,e->
                        if (e != null) {
                            Log.w(tag, "listen:error", e)
                            return@addSnapshotListener
                        }

                        if (querysnapshot != null && !querysnapshot.isEmpty) {
                            for (dataSnapShot in querysnapshot) {

                                val data = dataSnapShot.data
                                if (sdpTYpe == "Offer" && data.containsKey("type") && data.get("type")=="offerCandidate") {
                                    listener.onIceCandidateReceived(
                                            IceCandidate(data["sdpMid"].toString(),
                                                    Math.toIntExact(data["sdpMLineIndex"] as Long),
                                                    data["sdpCandidate"].toString()))
                                } else if (sdpTYpe == "Answer" && data.containsKey("type") && data.get("type")=="answerCandidate") {
                                    listener.onIceCandidateReceived(
                                            IceCandidate(data["sdpMid"].toString(),
                                                    Math.toIntExact(data["sdpMLineIndex"] as Long),
                                                    data["sdpCandidate"].toString()))
                                }
                                Log.e(tag, "candidateQuery: $dataSnapShot" )
                            }
                        }
                    }
//            db.collection("calls").document(meetingID)
//                    .get()
//                    .addOnSuccessListener { result ->
//                        val data = result.data
//                        if (data?.containsKey("type")!! && data.getValue("type").toString() == "OFFER") {
//                            Log.e(TAG, "connect: OFFER - $data")
//                            listener.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER,data["sdp"].toString()))
//                        } else if (data?.containsKey("type") && data.getValue("type").toString() == "ANSWER") {
//                            Log.e(TAG, "connect: ANSWER - $data")
//                            listener.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER,data["sdp"].toString()))
//                        }
//                    }
//                    .addOnFailureListener {
//                        Log.e(TAG, "connect: $it")
//                    }

        } catch (exception: Exception) {
            Log.e(tag, "connectException: $exception")

        }
    }

    fun sendIceCandidate(candidate: IceCandidate?,isJoin : Boolean) = runBlocking {
        val type = when {
            isJoin -> "answerCandidate"
            else -> "offerCandidate"
        }
        val candidateConstant = hashMapOf(
                "serverUrl" to candidate?.serverUrl,
                "sdpMid" to candidate?.sdpMid,
                "sdpMLineIndex" to candidate?.sdpMLineIndex,
                "sdpCandidate" to candidate?.sdp,
                "type" to type
        )
        db.collection("calls")
            .document(meetingID).collection("candidates").document(type)
            .set(candidateConstant as Map<*, *>)
            .addOnSuccessListener {
                Log.e(tag, "sendIceCandidate: Success" )
            }
            .addOnFailureListener {
                Log.e(tag, "sendIceCandidate: Error $it" )
            }
    }

    fun destroy() {
//        client.close()
        job.complete()
    }
}
