package com.example

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class FirebaseBridge(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FirebaseBridge"
    }

    private val auth: FirebaseAuth get() = Firebase.auth
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(activity.getString(R.string.firestore_database_id))

    @JavascriptInterface
    fun getCurrentUser(): String {
        val user = auth.currentUser
        val json = JSONObject()
        if (user != null) {
            json.put("isLoggedIn", true)
            json.put("uid", user.uid)
            json.put("email", user.email ?: "")
            json.put("displayName", user.displayName ?: "")
        } else {
            json.put("isLoggedIn", false)
        }
        return json.toString()
    }

    @JavascriptInterface
    fun signInWithGoogle() {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            Log.e(TAG, "Missing default_web_client_id: ${e.message}")
            evalJs("window.onGoogleSignInError && window.onGoogleSignInError('شناسه کلاینت گوگل در برنامه یافت نشد.');")
            return
        }

        val credentialManager = CredentialManager.create(activity)
        val googleOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()

        scope.launch(Dispatchers.Main) {
            try {
                evalJs("window.onGoogleSignInStart && window.onGoogleSignInStart();")
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCred = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(authCred).await()
                    val user = authResult.user
                    if (user != null) {
                        // Save or update profile in Firestore
                        try {
                            val profile = mapOf(
                                "email" to (user.email ?: ""),
                                "displayName" to (user.displayName ?: ""),
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                            db.collection("users").document(user.uid).set(profile, SetOptions.merge())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to save user profile: ${e.message}")
                        }

                        Log.d(TAG, "Google Sign-In successful for: ${user.email}")
                        val safeEmail = JSONObject.quote(user.email ?: "")
                        val safeUid = JSONObject.quote(user.uid)
                        val safeName = JSONObject.quote(user.displayName ?: "")
                        evalJs("window.onGoogleSignInSuccess && window.onGoogleSignInSuccess($safeEmail, $safeUid, $safeName);")
                        loadFarmData()
                    }
                } else {
                    evalJs("window.onGoogleSignInError && window.onGoogleSignInError('نوع گواهی نامعتبر است.');")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled by user")
                evalJs("window.onGoogleSignInCancelled && window.onGoogleSignInCancelled();")
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed: ${e.message}", e)
                val raw = (e.localizedMessage ?: e.message ?: "").lowercase()
                val friendlyMsg = when {
                    raw.contains("403") || raw.contains("identitytoolkit") || raw.contains("configuration_not_found") ->
                        "سرویس گوگل در این شبکه پاسخ نداد. برای انتقال اطلاعات به گوشی دیگر، می‌توانید از خروجی فایل پشتیبان استفاده کنید."
                    raw.contains("network") || raw.contains("timeout") || raw.contains("unknownhost") ->
                        "خطا در اتصال به اینترنت. لطفاً شبکه خود را بررسی کنید."
                    raw.contains("12500") || raw.contains("developer_error") ->
                        "ورود با این حساب گوگل موقتاً در دسترس نیست. می‌توانید به عنوان مهمان یا با نام کاربری وارد شوید."
                    raw.contains("cancel") -> ""
                    else -> "عدم موفقیت در اتصال به حساب گوگل. لطفاً اتصال اینترنت را بررسی کنید."
                }
                if (friendlyMsg.isNotBlank()) {
                    val errMsg = JSONObject.quote(friendlyMsg)
                    evalJs("window.onGoogleSignInError && window.onGoogleSignInError($errMsg);")
                } else {
                    evalJs("window.onGoogleSignInCancelled && window.onGoogleSignInCancelled();")
                }
            }
        }
    }

    @JavascriptInterface
    fun signOut() {
        auth.signOut()
        evalJs("window.onGoogleSignOut && window.onGoogleSignOut();")
    }

    @JavascriptInterface
    fun saveFarmData(recordsJson: String) {
        val user = auth.currentUser ?: return
        if (recordsJson.isBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val data = mapOf(
                    "userId" to user.uid,
                    "recordsJson" to recordsJson,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("farms").document(user.uid).set(data).await()
                Log.d(TAG, "Farm data successfully synced to Firestore for ${user.uid}")
                evalJs("window.onFarmDataSynced && window.onFarmDataSynced(true);")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save farm data: ${e.message}", e)
                evalJs("window.onFarmDataSynced && window.onFarmDataSynced(false);")
            }
        }
    }

    @JavascriptInterface
    fun loadFarmData() {
        val user = auth.currentUser ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val snapshot = db.collection("farms").document(user.uid).get().await()
                if (snapshot.exists()) {
                    val recordsJson = snapshot.getString("recordsJson")
                    if (!recordsJson.isNullOrBlank()) {
                        val safeRecords = JSONObject.quote(recordsJson)
                        evalJs("window.onFarmDataLoaded && window.onFarmDataLoaded($safeRecords);")
                    } else {
                        evalJs("window.onFarmDataLoaded && window.onFarmDataLoaded(null);")
                    }
                } else {
                    evalJs("window.onFarmDataLoaded && window.onFarmDataLoaded(null);")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load farm data from Firestore: ${e.message}", e)
                evalJs("window.onFarmDataLoaded && window.onFarmDataLoaded(null);")
            }
        }
    }

    @JavascriptInterface
    fun isNetworkAvailable(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun evalJs(script: String) {
        activity.runOnUiThread {
            webViewProvider()?.evaluateJavascript(script, null)
        }
    }
}
