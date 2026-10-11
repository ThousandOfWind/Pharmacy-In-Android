package com.thousandofwind.pharmacy

import java.util.UUID

/** Never merge same-named groups. A UUID/name conflict gets a deterministic separate identity. */
fun resolveBackupGroups(records: List<BackupRecord>, existing: Map<String, String>): List<BackupRecord> {
    val occupied = existing.toMutableMap()
    val remapped = mutableMapOf<Pair<String, String>, String>()
    return records.map { record ->
        if (record.groupKey.isBlank()) record else {
            val identity = record.groupKey to record.groupName
            val target = remapped.getOrPut(identity) {
                var key = record.groupKey
                var attempt = 0
                while (occupied.containsKey(key) && occupied[key] != record.groupName) {
                    key = UUID.nameUUIDFromBytes("pharmacy-group:${record.groupKey}:${record.groupName}:${attempt++}".toByteArray()).toString()
                }
                occupied[key] = record.groupName
                key
            }
            record.copy(groupKey = target)
        }
    }
}
