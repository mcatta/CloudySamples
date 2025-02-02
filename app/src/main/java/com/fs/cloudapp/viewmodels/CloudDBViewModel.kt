package com.fs.cloudapp.viewmodels

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fs.cloudapp.data.*
import com.huawei.agconnect.AGCRoutePolicy
import com.huawei.agconnect.AGConnectInstance
import com.huawei.agconnect.AGConnectOptionsBuilder
import com.huawei.agconnect.auth.AGConnectAuth
import com.huawei.agconnect.auth.AGConnectUser
import com.huawei.agconnect.cloud.database.*
import com.huawei.agconnect.cloud.database.exceptions.AGConnectCloudDBException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.Exception

typealias Message = messages
typealias User = users
typealias FullMessage = full_message

class CloudDBViewModel : ViewModel() {

    private lateinit var DBInstance: AGConnectCloudDB
    private var DBZone: CloudDBZone? = null

    private val mState = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState>
        get() = mState

    var userID: String = ""
        private set

    private var messageId: Long = 0L

    private var messages: MutableLiveData<List<FullMessage>> = MutableLiveData()

    private var loadingProgress: MutableState<Boolean> = mutableStateOf(false)

    private val mSnapshotListener = OnSnapshotListener<FullMessage> { cloudDBZoneSnapshot, e ->
        if (e != null) {
            Log.w(TAG, "onSnapshot: " + e.message)
            return@OnSnapshotListener
        }
        val snapshotObjects = cloudDBZoneSnapshot.snapshotObjects
        val messagesList: MutableList<FullMessage> = ArrayList()
        try {
            if (snapshotObjects != null) {
                while (snapshotObjects.hasNext()) {
                    val message = snapshotObjects.next()

                    if (messageId <= message.id) {
                        messageId = message.id
                    }

                    messagesList.add(message)
                }
            }
            this.messages.postValue(messagesList.sortedBy { it.date_ins })
        } catch (snapshotException: AGConnectCloudDBException) {
            Log.w(TAG, "onSnapshot:(getObject) " + snapshotException.message)
        } finally {
            cloudDBZoneSnapshot.release()
        }
    }

    var messageToEdit: MutableState<FullMessage?> = mutableStateOf(null)

    fun initAGConnectCloudDB(
        context: Context,
        authInstance: AGConnectAuth
    ) {
        this.userID = authInstance.currentUser.uid

        if (DBZone == null) {
            AGConnectCloudDB.initialize(context)
            val agcConnectOptions = AGConnectOptionsBuilder()
                .setRoutePolicy(AGCRoutePolicy.GERMANY)
                .build(context)
            val agConnectInstance = AGConnectInstance.buildInstance(agcConnectOptions)
            this.DBInstance = AGConnectCloudDB.getInstance(
                agConnectInstance,
                authInstance
            )
            this.DBInstance.createObjectType(ObjectTypeInfoHelper.getObjectTypeInfo())
            openCloudZone()
        }
    }

    private fun openCloudZone() {
        val mConfig = CloudDBZoneConfig(
            "ChatDemo",
            CloudDBZoneConfig.CloudDBZoneSyncProperty.CLOUDDBZONE_CLOUD_CACHE,
            CloudDBZoneConfig.CloudDBZoneAccessProperty.CLOUDDBZONE_PUBLIC
        ).apply {
            persistenceEnabled = true
        }

        this.DBInstance.openCloudDBZone2(mConfig, true).addOnSuccessListener {
            DBZone = it
            updateState(mState.value.copy(dbReady = true))
        }.addOnFailureListener {
            Log.e(TAG, "${it.message}")
        }
    }

    fun saveUser(credentials: AGConnectUser) {
        val user = User().apply {
            id = credentials.uid
            nickname = credentials.displayName
            email = credentials.email
            phone_number = credentials.phone
            picture_url = credentials.photoUrl
            provider_id = credentials.providerId
        }

        val upsertTask = this.DBZone!!.executeUpsert(user)
        upsertTask.addOnSuccessListener { cloudDBZoneResult ->
            Log.i(TAG, "Upsert users $cloudDBZoneResult records")
        }.addOnFailureListener {
            val err = it as AGConnectCloudDBException
            Log.e(TAG, "${err.code}: ${err.message}")
        }
    }

    fun savePushToken(pushToken: user_push_tokens) {
        val upsertTask = this.DBZone!!.executeUpsert(pushToken)
        upsertTask.addOnSuccessListener { cloudDBZoneResult ->
            Log.i(TAG, "Upsert $cloudDBZoneResult records")
            userID = pushToken.user_id
        }.addOnFailureListener {
            val err = it as AGConnectCloudDBException
            Log.e(TAG, "${err.code}: ${err.message}")
        }
    }

    fun sendMessage(text: String) {
        messageId++

        val message = Message().apply {
            this.id = messageId
            this.text = text
            this.user_id = userID
            this.type = 0
        }

        sendMessageOnCloud(message)
    }

    fun editMessage(text: String, fullMessage: FullMessage) {
        val message = Message().apply {
            this.id = fullMessage.id
            this.text = text
            this.user_id = fullMessage.user_id
            this.type = fullMessage.type
        }

        this.messageToEdit.value = null

        sendMessageOnCloud(message)
    }

    private fun sendMessageOnCloud(message: Message) {
        val upsertTask = this.DBZone!!.executeUpsert(message)
        upsertTask.addOnSuccessListener { cloudDBZoneResult ->
            Log.i(TAG, "Upsert $cloudDBZoneResult records")
        }.addOnFailureListener {
            val err = it as AGConnectCloudDBException
            Log.e(TAG, "${err.code}: ${err.message}")
        }
    }

    fun getAllMessages() {
        val query = CloudDBZoneQuery.where(FullMessage::class.java)
            .equalTo("type", 0)
        //not supported by the subscription
        //.orderByDesc("date_ins")

        val queryTask = this.DBZone!!.executeQuery(
            query,
            CloudDBZoneQuery.CloudDBZoneQueryPolicy.POLICY_QUERY_DEFAULT
        )
        queryTask.addOnSuccessListener { snapshot -> processQueryResult(snapshot) }
            .addOnFailureListener {
                updateState(state.value.copy(failureOutput = it))
            }

        this.DBZone!!.subscribeSnapshot(
            query,
            CloudDBZoneQuery.CloudDBZoneQueryPolicy.POLICY_QUERY_FROM_CLOUD_ONLY, mSnapshotListener
        )
    }

    private fun processQueryResult(snapshot: CloudDBZoneSnapshot<FullMessage>) {
        val messagesCursor = snapshot.snapshotObjects
        val messagesList: MutableList<FullMessage> = ArrayList()
        try {
            while (messagesCursor.hasNext()) {
                val message = messagesCursor.next()

                if (messageId <= message.id) {
                    messageId = message.id
                }

                messagesList.add(message)
            }
        } catch (e: AGConnectCloudDBException) {
            Log.w(TAG, "processQueryResult: " + e.message)
        } finally {
            snapshot.release()
        }

        messages.value = messagesList.sortedBy { it.date_ins }
    }

    fun deleteMessage(message: FullMessage) {
        val messToDelete = Message().apply {
            id = message.id
            text = message.text
            type = message.type
            user_id = message.user_id
        }

        val deleteTask = this.DBZone!!.executeDelete(messToDelete)
        deleteTask.addOnSuccessListener {
            Log.i(TAG, "Delete message ${message.id} succeed!")
        }.addOnFailureListener {
            Log.e(TAG, "Delete message error: ", it)
        }
    }

    fun deleteUser(id: String) {
        val userToDelete = User().apply {
            this.id = id
        }

        val deleteTask = this.DBZone!!.executeDelete(userToDelete)
        deleteTask.addOnSuccessListener {
            Log.i(TAG, "Delete user ${userToDelete.id} succeed!")
        }.addOnFailureListener {
            Log.e(TAG, "Delete user error: ", it)
        }
    }

    fun resetFailureOutput() {
        updateState(state.value.copy(failureOutput = null))
    }

    fun getChatMessages(): LiveData<List<FullMessage>> {
        return this.messages
    }

    fun getLoadingProgress(): MutableState<Boolean> {
        return loadingProgress
    }

    fun closeDB() {
        if (this::DBInstance.isInitialized && this.DBZone != null) {
            this.DBInstance.closeCloudDBZone(this.DBZone)
        }
    }

    private fun updateState(newState: CloudState) {
        viewModelScope.launch {
            mState.emit(value = newState)
        }
    }

    companion object {
        const val TAG = "CloudDBViewModel"
    }

    data class CloudState(
        val dbReady: Boolean = false,
        val failureOutput: Exception? = null
    )
}