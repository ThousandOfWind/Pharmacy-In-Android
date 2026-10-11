package com.thousandofwind.pharmacy

/** Applying a photo suggestion is explicit and only fills blanks. User data is never overwritten. */
fun fillEmptyOcrFields(draft: MedicineDraft, includeLeaflet: Boolean = false): MedicineDraft {
    val candidates = draft.candidates
    val info = MedicineInformationField.entries.fold(draft.information) { current, field ->
        val allowed = field != MedicineInformationField.STORAGE_LOCATION &&
            (field.group == MedicineInformationGroup.IDENTITY || includeLeaflet)
        if (allowed && current[field].isBlank() && candidates.information[field].isNotBlank())
            current.withValue(field, candidates.information[field]) else current
    }
    return draft.copy(
        name = draft.name.ifBlank { candidates.name }, expiry = draft.expiry.ifBlank { candidates.expiry },
        efficacy = if (includeLeaflet) draft.efficacy.ifBlank { candidates.efficacy } else draft.efficacy,
        information = info, reviewed = false
    )
}
