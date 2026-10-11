package com.thousandofwind.pharmacy

import java.io.File

/** New captures are temporary. Existing albums survive ordinary edits until explicitly reduced. */
fun retainedMedicinePhotos(
    photos: List<String>, existing: List<String>, cover: String, keepExisting: Boolean
): List<String> {
    val selected = cover.takeIf { it in photos } ?: photos.firstOrNull() ?: return emptyList()
    return (listOf(selected) + if (keepExisting) existing else emptyList()).distinct()
}

fun unreferencedPhotos(candidates: List<String>, protected: Collection<String>): List<String> {
    val references = protected.toHashSet()
    return candidates.distinct().filterNot { it in references }
}

/** Only an explicitly nominated, regular file directly inside the owned directory may be deleted. */
internal fun deleteOwnedPhotoFile(file: File, root: File): Boolean {
    val canonical = file.canonicalFile
    if (canonical.parentFile != root.canonicalFile || file.isDirectory) return false
    return !file.exists() || file.delete()
}
