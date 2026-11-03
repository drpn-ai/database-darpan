package darpan.database

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-191. IBM's Db2 driver (com.ibm.db2:jcc) bundles its own, older, unrelocated copy of lz4-java
 * (net.jpountz.*). Moqui flattens every component's lib/ into one WEB-INF/lib, and jcc-*.jar sorts before
 * lz4-java-*.jar, so the driver's copy won: Spark 4.2.1 then failed every shuffle with
 * NoSuchMethodError LZ4BlockInputStream.newBuilder(). The build strips net/jpountz/** from the driver, so
 * exactly one jar on the classpath may carry these classes: lz4-java itself.
 */
class Db2DriverLz4ShadowTests {

    private static final String LZ4_CLASS = 'net/jpountz/lz4/LZ4BlockInputStream.class'

    @Test void onlyLz4JavaCarriesTheLz4Classes() {
        List<String> carriers = Collections.list(getClass().classLoader.getResources(LZ4_CLASS))*.toString()
        assertEquals(1, carriers.size(), "expected one jar carrying ${LZ4_CLASS}, found: ${carriers}")
        assertTrue(carriers[0].contains('lz4-java'), "the lz4 classes must come from lz4-java, not ${carriers[0]}")
    }

    @Test void theDb2DriverStillLoads() {
        // Stripping lz4 must not cost the driver itself: it still resolves for Db2 sources.
        assertNotNull(Class.forName('com.ibm.db2.jcc.DB2Driver', false, getClass().classLoader))
    }
}
