package org.sapidroid.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The TTS service's handle on the engine worker process. */
internal object SapiDroidWorkers {

    private const val TAG = "SapiDroidWorkers"
    private const val BIND_TIMEOUT_S = 20L
    private const val DIE_TIMEOUT_S = 5L

    private val lock = Any()

    private class Connection(val context: Context) : ServiceConnection {
        private val gate = Any()
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var api: IZiraWorker? = null
        @Volatile var retired = false
        var bound = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(gate) {
                if (retired) return
                api = IZiraWorker.Stub.asInterface(binder)
                try {
                    binder.linkToDeath({ disconnected() }, 0)
                } catch (e: DeadObjectException) {
                    disconnected()
                }
                ready.countDown()
            }
        }

        private fun unbind() {
            if (bound) {
                try {
                    context.unbindService(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "unbind failed", t)
                }
                bound = false
            }
        }

        private fun disconnected() {
            synchronized(gate) {
                retired = true
                unbind()
                ready.countDown()
                closed.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = disconnected()
        override fun onNullBinding(name: ComponentName?) = disconnected()

        override fun onBindingDied(name: ComponentName?) {
            synchronized(gate) {
                api = null
                retire()
            }
        }

        fun retire() {
            if (retired) return
            retired = true
            unbind()
            ready.countDown()
            val target = api
            api = null
            if (target == null || !target.asBinder().isBinderAlive) {
                closed.countDown()
                return
            }
            try {
                target.shutdown()
            } catch (e: DeadObjectException) {
                closed.countDown()
            } catch (t: Throwable) {
                Log.w(TAG, "shutdown failed", t)
                closed.countDown()
            }
        }
    }

    private var connection: Connection? = null

    fun acquire(context: Context): IZiraWorker {
        check(Looper.myLooper() != Looper.getMainLooper()) { "worker binding on main thread" }
        val ctx = context.applicationContext
        val conn: Connection
        synchronized(lock) {
            val existing = connection
            if (existing != null && !existing.retired) {
                existing.api?.let { if (it.asBinder().isBinderAlive) return it }
                existing.retire()
            }
            conn = Connection(ctx).also { c ->
                c.bound = ctx.bindService(
                    Intent(ctx, SapiDroidWorkerService::class.java),
                    c,
                    Context.BIND_AUTO_CREATE
                )
                if (!c.bound) {
                    c.ready.countDown()
                    c.closed.countDown()
                }
                connection = c
            }
        }
        if (!conn.ready.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
            retireConnection(conn)
            throw IllegalStateException("engine worker bind timed out")
        }
        val api = conn.api
        if (conn.retired || api == null || !api.asBinder().isBinderAlive) {
            retireConnection(conn)
            throw IllegalStateException("engine worker unavailable")
        }
        return api
    }

    fun retire(api: IZiraWorker?) {
        if (api == null) return
        val conn = synchronized(lock) {
            connection?.takeIf { it.api?.asBinder() === api.asBinder() }
        } ?: return
        retireConnection(conn)
    }

    private fun retireConnection(conn: Connection) {
        synchronized(lock) { conn.retire() }
        if (!conn.closed.await(DIE_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "worker did not report death")
        }
        synchronized(lock) { if (connection === conn) connection = null }
    }

    fun replace(context: Context, old: IZiraWorker?): IZiraWorker {
        retire(old)
        return acquire(context)
    }
}
