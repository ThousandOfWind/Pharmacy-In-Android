package com.thousandofwind.pharmacy

import java.io.File

/** New captures are temporary. Existing albums survive ordinary edits until explicitly reduced. */
fun retainedMedicinePhotos(
    photos: List<String>, existing: List<String>, cover: String, keepExisting: Boolean,
    rotatedExisting: List<String> = emptyList()
): List<String> {
    val selected = cover.takeIf { it in photos } ?: photos.firstOrNull() ?: return emptyList()
    if (!keepExisting) return listOf(selected)
    val remaining = existing.filter { it in photos } + rotatedExisting.filter { it in photos }
    val index = remaining.indexOf(selected)
    // Only retain still-linked originals; removed/rotated files must not reappear on save.
    // Move just one occurrence to the front, preserving legal backup order and multiplicity.
    return if (index == 0) remaining else listOf(selected) + remaining.filterIndexed { i, _ -> i != index }
}

/** Preserve cover identity when replacing its bytes; baseline protects the original until save. */
fun replaceDraftPhoto(draft: MedicineDraft, old: String, replacement: String, wasExisting: Boolean = false): MedicineDraft = draft.copy(
    photos = draft.photos.map { if (it == old) replacement else it },
    coverPhoto = if (draft.coverPhoto == old) replacement else draft.coverPhoto,
    rotatedExistingPhotos = (draft.rotatedExistingPhotos - old) + if (wasExisting) listOf(replacement) else emptyList(),
    reviewed = false
)

fun removeDraftPhoto(draft: MedicineDraft, photo: String): MedicineDraft {
    val remaining = draft.photos - photo
    return draft.copy(photos = remaining, coverPhoto = if (draft.coverPhoto == photo) remaining.firstOrNull().orEmpty() else draft.coverPhoto,
        candidates = OcrCandidates(), rotatedExistingPhotos = draft.rotatedExistingPhotos - photo, reviewed = false)
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
