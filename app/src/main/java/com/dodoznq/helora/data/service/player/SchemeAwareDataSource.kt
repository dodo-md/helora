package com.dodoznq.helora.data.service.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Picks between two ready-built [DataSource] chains at [open] time, based on the DataSpec's
 * URI scheme. [DataSource.Factory.createDataSource] runs before the URI is known, so the
 * choice can't be made any earlier than this.
 *
 * Remote/proxied schemes go through [cached]; everything else (local `content://`/`file://`)
 * goes through [direct] so a local file isn't copied disk-to-disk into the cache.
 */
internal class SchemeAwareDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
    private val cachedSchemes: Set<String>
) : DataSource {

    @Volatile
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme in cachedSchemes) cached else direct
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "SchemeAwareDataSource.read() called before open()" }
            .read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val cachedFactory: DataSource.Factory,
        private val directFactory: DataSource.Factory,
        private val cachedSchemes: Set<String>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeAwareDataSource(
            cached = cachedFactory.createDataSource(),
            direct = directFactory.createDataSource(),
            cachedSchemes = cachedSchemes
        )
    }
}
