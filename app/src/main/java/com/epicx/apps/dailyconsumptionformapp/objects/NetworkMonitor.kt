package com.epicx.apps.dailyconsumptionformapp.objects

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.lifecycle.LiveData

object NetworkMonitor : LiveData<Boolean>() {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isRegistered = false

    fun init(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                postValue(true)
            }

            override fun onLost(network: Network) {
                postValue(false)
            }

            override fun onUnavailable() {
                postValue(false)
            }
        }
    }

    override fun onActive() {
        super.onActive()
        if (!::connectivityManager.isInitialized || !::networkCallback.isInitialized) {
            // Optionally: throw or log an error
            return
        }
        if (!isRegistered) {
            val request = NetworkRequest.Builder().build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true

            val isConnectedNow = connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
            postValue(isConnectedNow)
        }
    }

    override fun onInactive() {
        super.onInactive()
        if (::connectivityManager.isInitialized && ::networkCallback.isInitialized && isRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // log error if you want
            }
            isRegistered = false
        }
    }
}

