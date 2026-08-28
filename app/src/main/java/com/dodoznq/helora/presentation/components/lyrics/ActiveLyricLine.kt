package com.dodoznq.helora.presentation.components.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.dodoznq.helora.data.model.SyncedLine
import com.dodoznq.helora.utils.SyncedLineResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

/**
 * Aktif satır indeksini [State] olarak döndürür. Composition yalnızca indeks
 * DEĞİŞTİĞİNDE geçersiz olur; pozisyonun her tick'inde değil.
 */
@Composable
fun rememberActiveLineIndex(
    lines: ImmutableList<SyncedLine>,
    positionFlow: StateFlow<Long>,
    syncOffsetMs: Int,
    positionOverrideMs: Long?,
): State<Int> {
    return produceState(initialValue = -1, lines, positionFlow, syncOffsetMs, positionOverrideMs) {
        if (positionOverrideMs != null) {
            val resolved = SyncedLineResolver.activeLineIndex(lines, positionOverrideMs)
            if (value != resolved) value = resolved
            return@produceState
        }

        positionFlow.collect { rawPosition ->
            val position = (rawPosition + syncOffsetMs).coerceAtLeast(0L)
            val resolved = SyncedLineResolver.activeLineIndex(lines, position)
            if (value != resolved) value = resolved
        }
    }
}

/**
 * Pozisyonu composition'da OKUMADAN, çizim fazında okunabilecek bir sağlayıcı verir.
 * Kelime bazlı vurgulama gibi sürekli değerler bunu kullanmalıdır.
 */
@Composable
fun rememberPositionProvider(
    positionFlow: StateFlow<Long>,
    syncOffsetMs: Int,
): () -> Long {
    val state = remember { mutableLongStateOf(0L) }

    LaunchedEffect(positionFlow, syncOffsetMs) {
        positionFlow.collect { rawPosition ->
            state.longValue = rawPosition + syncOffsetMs
        }
    }

    return remember { { state.longValue } }
}
