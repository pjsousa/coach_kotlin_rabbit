package pharmacy

import java.time.Instant


data class Prescription(
    val id: String,
    val patientId: String,
    val items: List<PrescriptionItem>,
    val submittedAt: Instant
){
    init {
        require(id.isNotBlank()) { "id cannot be blank"  }
        require(items.isNotEmpty()) { "a prescription need at least one item"}
    }
}