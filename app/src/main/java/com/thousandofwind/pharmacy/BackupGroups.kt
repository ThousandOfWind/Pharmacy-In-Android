package com.thousandofwind.pharmacy

/** UUID is the only association identity. Never infer/remap an identity from a display name. */
fun resolveBackupGroups(records: List<BackupRecord>, existing: Map<String, String>): List<BackupRecord> {
    val groups = existing.toMutableMap()
    records.filter { it.groupKey.isNotBlank() }.forEach { record ->
        require(!groups.containsKey(record.groupKey) || groups[record.groupKey] == record.groupName) {
            "备份分组标识与现有药品组名称冲突，未恢复；请先核对分组，原药箱保留"
        }
        groups[record.groupKey] = record.groupName
    }
    return records
}
