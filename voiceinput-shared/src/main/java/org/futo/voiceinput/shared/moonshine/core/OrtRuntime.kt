package org.futo.voiceinput.shared.moonshine.core

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.EnumSet

/** Execution provider used for the ONNX Runtime sessions. */
enum class OrtAccelerator {
    /** Default ONNX Runtime CPU (MLAS) kernels. */
    CPU,

    /** XNNPACK execution provider, and unsupported nodes fall back to CPU. */
    XNNPACK,

    /** Android NNAPI execution provider, and unsupported nodes fall back to CPU. */
    NNAPI
}

data class OrtRuntimeOptions(
    val accelerator: OrtAccelerator = OrtAccelerator.CPU,
    /** Intra-op threads. 0 = automatic ([CpuTopology.autoThreadCount]). */
    val threads: Int = 0
) {
    val effectiveThreads: Int
        get() = if (threads > 0) threads else CpuTopology.autoThreadCount
}

/**
 * Picks a thread count for big.LITTLE CPUs. ONNX Runtime splits each operator
 * evenly across its threads, so a thread that lands on a little core makes
 * every parallel section wait for it (measured on a hybrid desktop CPU, where
 * 4 threads on 2 fast + 2 slow cores were slower than 2 threads on the 2 fast
 * cores). So use the number of "big" cores, clamped to 2..4.
 */
object CpuTopology {
    val autoThreadCount: Int by lazy { compute() }

    private fun readInt(path: String): Long? = try {
        java.io.File(path).readText().trim().toLong()
    } catch (e: Exception) {
        null
    }

    private fun compute(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        // Linux/Android use per-core capacity (EAS) or max frequency.
        val capacities = (0 until n).mapNotNull { readInt("/sys/devices/system/cpu/cpu$it/cpu_capacity") }
        val freqs = (0 until n).mapNotNull { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        val big = when {
            capacities.size == n && capacities.max() > 0 -> capacities.count { it * 2 >= capacities.max() }
            freqs.size == n && freqs.max() > 0 -> freqs.count { it * 100 >= freqs.max() * 85 }
            else -> n / 2
        }
        return big.coerceIn(2, 4)
    }
}

/**
 * Creates sessions for one model with a given accelerator. Model files are
 * memory-mapped and handed to ONNX Runtime with `use_ort_model_bytes_directly`
 * (like the moonshine C++ library does), so the weights stay in clean,
 * file-backed pages instead of being copied onto the heap. The mappings must
 * outlive the sessions, so this object keeps them until [close].
 */
internal class OrtSessionFactory(
    val env: OrtEnvironment,
    val accelerator: OrtAccelerator,
    val threads: Int
) : AutoCloseable {
    private val mappings = mutableListOf<ByteBuffer>()
    private val sessions = mutableListOf<OrtSession>()

    private fun createOptions(acceleratorForSession: OrtAccelerator, threadsForSession: Int): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(threadsForSession)
            options.setInterOpNumThreads(1)
            // Keep ORT's CPU memory arena enabled (unlike the moonshine C++
            // library), because without it every decoder step does large malloc/free
            // pairs, which measured ~2x slower decoding.
            options.addConfigEntry("session.load_model_format", "ORT")
            options.addConfigEntry("session.use_ort_model_bytes_directly", "1")
            options.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
            // Each session has its own intra-op pool and they run one after
            // another, and spinning workers of idle pools steal cores from the
            // active one (8 threads measured 10x slower with spinning).
            options.addConfigEntry("session.intra_op.allow_spinning", "0")

            when (acceleratorForSession) {
                OrtAccelerator.CPU -> {}
                OrtAccelerator.XNNPACK -> {
                    // XNNPACK has its own thread pool, and nodes it can't take still
                    // run on the CPU EP pool.
                    options.addXnnpack(mapOf("intra_op_num_threads" to threadsForSession.toString()))
                }
                OrtAccelerator.NNAPI -> {
                    options.addNnapi(EnumSet.noneOf(NNAPIFlags::class.java))
                }
            }
        } catch (e: Throwable) {
            options.close()
            throw e
        }
        return options
    }

    private fun map(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    /**
     * Opens a model file. [cpuOnly] forces the CPU EP (used for tiny graphs
     * such as the VAD or one-off weight extraction).
     */
    @Throws(OrtException::class)
    fun open(file: File, cpuOnly: Boolean = false, threadsOverride: Int? = null): OrtSession {
        if (!file.isFile) throw MoonshineException("Missing model file: ${file.name}")
        val buffer = map(file)
        val options = createOptions(
            if (cpuOnly) OrtAccelerator.CPU else accelerator,
            threadsOverride ?: threads
        )
        try {
            val session = env.createSession(buffer, options)
            mappings.add(buffer)
            sessions.add(session)
            return session
        } finally {
            options.close()
        }
    }

    /** Releases a session created by [open] early (its mapping is kept until [close]). */
    fun release(session: OrtSession) {
        if (sessions.remove(session)) session.close()
    }

    override fun close() {
        sessions.forEach {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
        sessions.clear()
        // Mapped buffers are unmapped by the GC once unreachable.
        mappings.clear()
    }
}
