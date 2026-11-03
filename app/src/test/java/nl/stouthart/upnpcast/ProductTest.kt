package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** A room's icon by its main player's model name, as the speakers' descriptions give it. */
class ProductTest {
    @Test
    fun families() {
        assertEquals(Product.SOUNDBAR, product("Sonos Beam Ultra"))
        assertEquals(Product.SOUNDBAR, product("Sonos Arc"))
        assertEquals(Product.SOUNDBAR, product("Sonos Ray"))
        assertEquals(Product.SUB, product("Sonos Sub Mini"))
        assertEquals(Product.SPEAKER, product("Sonos Era 300"))
        assertEquals(Product.SPEAKER, product("Sonos Five"))
        assertEquals(Product.PORTABLE, product("Sonos Move 2"))
        assertEquals(Product.PORTABLE, product("Sonos Roam SL"))
        assertEquals(Product.AMP, product("Sonos Amp"))
        assertEquals(Product.AMP, product("Sonos Port"))
        assertEquals(Product.SPEAKER, product("")) // Not read yet.
        assertEquals(Product.SPEAKER, product("Something new"))
    }
}
