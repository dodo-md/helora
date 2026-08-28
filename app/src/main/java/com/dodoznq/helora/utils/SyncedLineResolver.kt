package com.dodoznq.helora.utils

import com.dodoznq.helora.data.model.SyncedLine

object SyncedLineResolver {

    /**
     * Index of the line active at [positionMs]; -1 if [positionMs] is before the first line.
     * [lines] must be sorted ascending by time. Runs in O(log n) via binary search.
     */
    fun activeLineIndex(lines: List<SyncedLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].time.toLong() <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }
}
