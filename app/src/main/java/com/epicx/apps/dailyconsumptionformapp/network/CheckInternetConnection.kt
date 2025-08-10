import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay

object CheckInternetConnection {

    fun observeNetworkConnectivity(snackbar: Snackbar, context: Context): Flow<Boolean> = callbackFlow {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    launch {
                        trySend(checkInternetAccess(connectivityManager, network))
                    }
                }

                override fun onLost(network: Network) {
                    launch {
                        checkInternetUntilRestored(snackbar, connectivityManager)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    launch {
                        trySend(checkInternetAccess(connectivityManager, network))
                    }
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, callback)

            // Initial check
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                launch { trySend(checkInternetAccess(connectivityManager, activeNetwork)) }
            } else {
                trySend(false)
            }

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        } else {
            // Handle devices below API level 23
            val checkInternetJob = launch {
                while (true) {
                    delay(2000)
                    trySend(hasInternetAccess())
                }
            }

            // Ensure awaitClose is called
            awaitClose {
                checkInternetJob.cancel()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun checkInternetAccess(
        connectivityManager: ConnectivityManager,
        network: Network
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            val hasInternetCapability = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isValidated = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            hasInternetCapability && isValidated && hasInternetAccess()
        }
    }

    private suspend fun hasInternetAccess(): Boolean {
        return withContext(Dispatchers.IO){
            try {
                val timeoutMs = 1500
                val socket = Socket()
                val socketAddress = InetSocketAddress("8.8.8.8", 53)
                socket.connect(socketAddress, timeoutMs)
                socket.close()
                true
            } catch (e: IOException) {
                false
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun checkInternetUntilRestored(snackbar: Snackbar, connectivityManager: ConnectivityManager) {
        flow {
            while (true) {
                delay(2000) // Check every 2 seconds
                val activeNetwork = connectivityManager.activeNetwork
                val hasInternet = activeNetwork != null && checkInternetAccess(connectivityManager, activeNetwork)
                emit(hasInternet)
                if (hasInternet) break
            }
        }.collect { isConnected ->
            if (!isConnected) {
                snackbar.show()
            } else {
                snackbar.dismiss()
            }
        }
    }
}

