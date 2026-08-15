package pharmacy

data class PrescriptionItem(
    val medicationId: String,
    val quantity: Int
)
{
    init {
        require(medicationId.isNotBlank()){ "medicationId must not be blank"  }
        require(quantity > 0){ "quantity must be positive, was $quantity"  }
    }
}