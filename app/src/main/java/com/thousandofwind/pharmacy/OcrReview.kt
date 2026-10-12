package com.thousandofwind.pharmacy

/** Only undo a name actually adopted through this candidate control, never a matching manual name. */
fun toggleOcrName(draft: MedicineDraft, name: String): MedicineDraft = when {
    name !in draft.candidates.nameOptions -> draft
    draft.name == name && draft.ocrSelectedName == name -> draft.copy(name = "", ocrSelectedName = "", reviewed = false)
    draft.name.isBlank() -> draft.copy(name = name, ocrSelectedName = name, reviewed = false)
    else -> draft
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
        name = draft.name.ifBlank { candidates.name }, expiry = draft.expiry.ifBlank { candidates.expiry },
        efficacy = if (includeLeaflet) draft.efficacy.ifBlank { candidates.efficacy } else draft.efficacy,
        information = info
    )
    return if (filled == draft) draft else filled.copy(reviewed = false)
}
