package pharmacy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PrescriptionItemTest {

    @Test
    fun `zero quantity is rejected`() {
        assertFailsWith<IllegalArgumentException> { PrescriptionItem("amoxicillin", 0) }
    }
}
