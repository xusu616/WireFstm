package com.rD227.bToothFstm.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.rD227.bToothFstm.data.ConnectionPhase
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.ConnectionUiState
import com.rD227.bToothFstm.data.LogDirection
import com.rD227.bToothFstm.data.PendingSend
import com.rD227.bToothFstm.data.SendFailureReason
import com.rD227.bToothFstm.data.SendResult
import com.rD227.bToothFstm.data.TransportError
import com.rD227.bToothFstm.data.TransportErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Owns the Classic Bluetooth (RFCOMM) connection lifecycle: socket creation,
 * connect, the writer/reader coroutines, and per-write timeouts. Bonding and
 * device-list updates are left to the caller. It reports back through
 * callbacks, mirroring [BleConnectionManager].
 */
@SuppressLint("MissingPermission")
class ClassicConnectionManager(
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope,
    private val onState: (ConnectionUiState) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onSendComplete: (PendingSend, SendResult) -> Unit,
    private val onLog: (LogDirection, String, ConnectionType, String) -> Unit,
    private val isPending: (Long) -> Boolean,
    private val onFailed: (Long, TransportError) -> Unit,
) {
    @Volatile
    private var activeGeneration = 0L

    private var classicSocket: BluetoothSocket? = null
    private var classicSendChannel: Channel<PendingSend>? = null
    private var classicConnectJob: Job? = null
    private var classicWriterJob: Job? = null
    private var classicReaderJob: Job? = null
    private var classicSendTimeoutJob: Job? = null
    private var classicSendTimedOut = false
    private var classicDecoder: Utf8StreamDecoder? = null

    fun connect(device: BluetoothDevice, generation: Long, sppUuid: UUID) {
        activeGeneration = generation
        onState(ConnectionUiState(ConnectionPhase.CONNECTING, device.address, ConnectionType.CLASSIC))
        try {
            adapter?.cancelDiscovery()
        } catch (error: SecurityException) {
            fail(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Cannot cancel discovery")
            )
            return
        }

        classicConnectJob?.cancel()
        classicConnectJob = scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                withContext(Dispatchers.Main.immediate) {
                    if (generation != activeGeneration) {
                        safeCloseSocket(socket)
                        return@withContext
                    }
                    classicSocket = socket
                }
                if (generation != activeGeneration) {
                    safeCloseSocket(socket)
                    return@launch
                }
                socket.connect()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != activeGeneration || classicSocket !== socket) {
                        safeCloseSocket(socket)
                        return@withContext
                    }
                    startIo(socket, device.address, generation)
                }
            } catch (error: CancellationException) {
                safeCloseSocket(socket)
                throw error
            } catch (error: SecurityException) {
                safeCloseSocket(socket)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic connection permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                safeCloseSocket(socket)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                TransportErrorKind.CONNECTION_FAILED,
                                error.message ?: "Classic connection failed"
                            )
                        )
                    }
                }
            }
        }
    }

    fun enqueue(pending: PendingSend, generation: Long) {
        if (generation != activeGeneration) return
        val channel = classicSendChannel
        val queued = channel?.trySend(pending)
        if (queued == null || queued.isFailure) {
            onSendComplete(
                pending,
                SendResult.Failed(
                    pending.requestId,
                    if (channel == null || queued?.exceptionOrNull() != null) {
                        SendFailureReason.DISCONNECTED
                    } else {
                        SendFailureReason.QUEUE_FULL
                    },
                    if (channel == null || queued?.exceptionOrNull() != null) {
                        "Classic writer is unavailable"
                    } else {
                        "Send queue is full"
                    }
                )
            )
        }
    }

    fun close() {
        activeGeneration++
        classicSendChannel?.close()
        classicSendChannel = null
        classicConnectJob?.cancel()
        classicConnectJob = null
        classicWriterJob?.cancel()
        classicWriterJob = null
        classicReaderJob?.cancel()
        classicReaderJob = null
        cancelSendTimeout()
        classicSendTimedOut = false
        val socket = classicSocket
        classicSocket = null
        safeCloseSocket(socket)
        classicDecoder = null
    }

    fun hasSocket(): Boolean = classicSocket != null

    fun takeTail(): String = classicDecoder?.finish().orEmpty()

    private fun fail(generation: Long, error: TransportError) {
        if (generation != activeGeneration) return
        onFailed(generation, error)
    }

    private fun startIo(socket: BluetoothSocket, address: String, generation: Long) {
        val channel = Channel<PendingSend>(CHANNEL_CAPACITY)
        classicSendChannel = channel
        classicDecoder = Utf8StreamDecoder()
        onState(ConnectionUiState(ConnectionPhase.CONNECTED, address, ConnectionType.CLASSIC))
        onConnected(address)

        classicWriterJob = scope.launch(Dispatchers.IO) {
            try {
                for (pending in channel) {
                    withContext(Dispatchers.Main.immediate) {
                        startSendTimeout(socket, generation)
                    }
                    socket.outputStream.write(pending.payload)
                    socket.outputStream.flush()
                    withContext(Dispatchers.Main.immediate) {
                        if (classicSendTimedOut) {
                            fail(
                                generation,
                                TransportError(TransportErrorKind.WRITE_TIMEOUT, "Classic write timed out")
                            )
                            return@withContext
                        }
                        cancelSendTimeout()
                        if (generation == activeGeneration && isPending(pending.requestId)) {
                            onLog(LogDirection.SENT, pending.text, ConnectionType.CLASSIC, address)
                            onSendComplete(pending, SendResult.Sent(pending.requestId))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main.immediate) {
                    cancelSendTimeout()
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic write permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                withContext(Dispatchers.Main.immediate) {
                    val timedOut = classicSendTimedOut
                    cancelSendTimeout()
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                if (timedOut) TransportErrorKind.WRITE_TIMEOUT else TransportErrorKind.WRITE_FAILED,
                                error.message ?: if (timedOut) "Classic write timed out" else "Classic write failed"
                            )
                        )
                    }
                }
            }
        }

        classicReaderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CLASSIC_READ_BUFFER_SIZE)
            try {
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == activeGeneration) {
                                fail(
                                    generation,
                                    TransportError(
                                        TransportErrorKind.REMOTE_DISCONNECTED,
                                        "Classic device closed the connection"
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                    if (count == 0) continue
                    val copy = buffer.copyOf(count)
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == activeGeneration) {
                            val text = classicDecoder?.decode(copy).orEmpty()
                            if (text.isNotEmpty()) {
                                onLog(LogDirection.RECEIVED, text, ConnectionType.CLASSIC, address)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic read permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                withContext(Dispatchers.Main.immediate) {
                    val timedOut = classicSendTimedOut
                    if (generation == activeGeneration) {
                        fail(
                            generation,
                            TransportError(
                                if (timedOut) TransportErrorKind.WRITE_TIMEOUT else TransportErrorKind.IO_ERROR,
                                error.message ?: if (timedOut) "Classic write timed out" else "Classic read failed"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun startSendTimeout(socket: BluetoothSocket, generation: Long) {
        cancelSendTimeout()
        classicSendTimedOut = false
        classicSendTimeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(CLASSIC_SEND_TIMEOUT_MS)
            if (generation == activeGeneration && classicSocket === socket) {
                classicSendTimedOut = true
                classicSendTimeoutJob = null
                classicSocket = null
                safeCloseSocket(socket)
            }
        }
    }

    private fun cancelSendTimeout() {
        classicSendTimeoutJob?.cancel()
        classicSendTimeoutJob = null
    }

    private fun safeCloseSocket(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // The resource is already unusable.
        }
    }

    private companion object {
        const val CHANNEL_CAPACITY = 32
        const val CLASSIC_SEND_TIMEOUT_MS = 5_000L
        const val CLASSIC_READ_BUFFER_SIZE = 1024
    }
}