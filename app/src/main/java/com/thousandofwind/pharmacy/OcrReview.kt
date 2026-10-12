package com.thousandofwind.pharmacy

/** Only undo a name actually adopted through this candidate control, never a matching manual name. */
fun toggleOcrName(draft: MedicineDraft, name: String): MedicineDraft = when {
    draft.name == name && draft.ocrSelectedName == name && name.isNotBlank() -> draft.copy(name = "", ocrSelectedName = "", reviewed = false)
    name.isBlank() || (name != draft.candidates.name && name !in draft.candidates.nameOptions) -> draft
    draft.name.isBlank() -> draft.copy(name = name, ocrSelectedName = name, reviewed = false)
    else -> draft
}

/** Adopt exactly one field beside its input. Leaflet safety fields are never bulk-adopted here. */
fun adoptOcrField(draft: MedicineDraft, target: String): MedicineDraft {
    val c = draft.candidates
    val filled = when (target) {
        OCR_TARGET_EXPIRY -> if (draft.expiry.isBlank() && c.expiry.isNotBlank() && expiryDay(c.expiry) != null) draft.copy(expiry = c.expiry) else draft
        OCR_TARGET_EFFICACY -> if (draft.efficacy.isBlank() && c.efficacy.isNotBlank() && c.efficacy.length <= 5000) draft.copy(efficacy = c.efficacy) else draft
        else -> {
            val field = MedicineInformationField.entries.firstOrNull { it.column == target && it != MedicineInformationField.STORAGE_LOCATION } ?: return draft
            val value = c.information[field]
            if (draft.information[field].isBlank() && value.isNotBlank() && medicineInformationFieldError(field, value) == null)
                draft.copy(information = draft.information.withValue(field, value)) else draft
        }
    }
    return if (filled == draft) draft else filled.copy(reviewed = false)
}

/** Applying a photo suggestion is explicit and only fills blanks. User data is never overwritten. */
fun fillEmptyOcrFields(draft: MedicineDraft, includeLeaflet: Boolean = false): MedicineDraft {
    val candidates = draft.candidates
    val info = MedicineInformationField.entries.fold(draft.information) { current, field ->
        val allowed = field != MedicineInformationField.STORAGE_LOCATION &&
            (field.group == MedicineInformationGroup.IDENTITY || includeLeaflet)
        if (allowed && current[field].isBlank() && candidates.information[field].isNotBlank())
            current.withValue(field, candidates.information[field]) else current
    }
    val filled = draft.copy(
        ocrSelectedName = if (draft.name.isBlank() && candidates.name.isNotBlank()) candidates.name else draft.ocrSelectedName,
        name = draft.name.ifBlank { candidates.name }, expiry = draft.expiry.ifBlank { candidates.expiry },
        efficacy = if (includeLeaflet) draft.efficacy.ifBlank { candidates.efficacy } else draft.efficacy,
        information = info
    )
    return if (filled == draft) draft else filled.copy(reviewed = false)
}
