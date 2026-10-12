package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhotoRetentionTest {
    @Test fun newRecordRetainsOnlySelectedCoverWithSafeFallback() {
        val photos = listOf("front", "expiry", "leaflet")
        assertEquals(listOf("front"), retainedMedicinePhotos(photos, emptyList(), "", true))
        assertEquals(listOf("expiry"), retainedMedicinePhotos(photos, emptyList(), "expiry", true))
        assertEquals(listOf("front"), retainedMedicinePhotos(photos, emptyList(), "missing", false))
        assertTrue(retainedMedicinePhotos(emptyList(), emptyList(), "missing", true).isEmpty())
    }

    @Test fun ordinaryEditPreservesLegacyAlbumUntilExplicitReduction() {
        val old = listOf("front", "expiry")
        val draft = old + "new-leaflet"
        assertEquals(old, retainedMedicinePhotos(draft, old, "front", true))
        assertEquals(listOf("new-leaflet") + old, retainedMedicinePhotos(draft, old, "new-leaflet", true))
        assertEquals(listOf("expiry"), retainedMedicinePhotos(draft, old, "expiry", false))
        assertEquals(listOf("new-leaflet"), retainedMedicinePhotos(draft, old, "new-leaflet", false))
        assertEquals(listOf("front"), retainedMedicinePhotos(listOf("front"), old, "front", true))
        val rotation = replaceDraftPhoto(MedicineDraft(photos = old, coverPhoto = "front", reviewed = true), "front", "rotated", true)
        assertEquals("rotated", rotation.coverPhoto)
        assertFalse(rotation.reviewed)
        assertEquals(listOf("rotated", "expiry"), retainedMedicinePhotos(rotation.photos, old, rotation.coverPhoto, true, rotation.rotatedExistingPhotos))
        val nonCover = replaceDraftPhoto(MedicineDraft(photos = old, coverPhoto = "front"), "expiry", "rotatedExpiry", true)
        assertEquals(listOf("front", "rotatedExpiry"), retainedMedicinePhotos(nonCover.photos, old, "front", true, nonCover.rotatedExistingPhotos))
        val removedCover = removeDraftPhoto(rotation, "rotated")
        assertEquals("expiry", removedCover.coverPhoto)
        assertEquals(listOf("expiry"), retainedMedicinePhotos(removedCover.photos, old, removedCover.coverPhoto, true, removedCover.rotatedExistingPhotos))
        val duplicateAlbum = listOf("front", "expiry", "front", "expiry")
        assertEquals(duplicateAlbum, retainedMedicinePhotos(duplicateAlbum, duplicateAlbum, "front", true))
        assertEquals(listOf("expiry", "front", "front", "expiry"),
            retainedMedicinePhotos(duplicateAlbum, duplicateAlbum, "expiry", true))
    }

    @Test fun cleanupProtectsSharedSavedDraftAndUndoPhotos() {
        assertEquals(listOf("temporary"), unreferencedPhotos(
            listOf("cover", "shared", "temporary", "temporary", "undo", "draft"),
            listOf("cover", "shared", "undo", "draft")
        ))
        // Discarding an edit nominates only new imports, never the baseline album.
        assertEquals(listOf("temporary"), unreferencedPhotos(listOf("cover", "temporary"), listOf("cover")))
    }

    @Test fun deletionOnlyTouchesNominatedFilesDirectlyInsideOwnedRoot() {
        val temp = Files.createTempDirectory("pharmacy-photo-test").toFile()
        try {
            val root = File(temp, "medicine_photos").apply { mkdir() }
            val owned = File(root, "temporary.jpg").apply { writeText("test") }
            val cover = File(root, "cover.jpg").apply { writeText("test") }
            val outside = File(temp, "gallery.jpg").apply { writeText("test") }
            val nested = File(root, "nested").apply { mkdir() }
            val nestedPhoto = File(nested, "photo.jpg").apply { writeText("test") }
            assertFalse(deleteOwnedPhotoFile(outside, root))
            assertFalse(deleteOwnedPhotoFile(File(root, "../gallery.jpg"), root))
            assertFalse(deleteOwnedPhotoFile(nestedPhoto, root))
            assertFalse(deleteOwnedPhotoFile(nested, root))
            assertTrue(deleteOwnedPhotoFile(owned, root))
            assertTrue(deleteOwnedPhotoFile(owned, root)) // idempotent cleanup
            assertFalse(owned.exists())
            assertTrue(cover.exists())
            assertTrue(outside.exists())
            assertTrue(nestedPhoto.exists())
        } finally { temp.deleteRecursively() }
    }
}
