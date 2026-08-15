package pharmacy

import pharmacy.Prescription
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PrescriptionTest {
    
    @Test
    fun `empty ites are rejected`(){
        assertFailsWith<IllegalArgumentException> {
            Prescription(id="123", patientId="456", items = emptyList(), submittedAt = Instant.now())
        }
    }
}