package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply counts as done only when it is the action's own answer, one whole SOAP document, not just any HTTP 200. */
class SoapTest {
    private val soap = "http://schemas.xmlsoap.org/soap/envelope/"
    private val avt = "xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\""

    private fun envelope(body: String, before: String = "") = ("<?xml version=\"1.0\"?>$before<s:Envelope xmlns:s=\"$soap\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$body</s:Body></s:Envelope>").toByteArray()

    private fun ok(reply: ByteArray, action: String, service: String? = "AVTransport") =
        assertTrue(String(reply), Http.acknowledges(reply, action, service))

    private fun no(reply: ByteArray, action: String, service: String? = "AVTransport") =
        assertFalse(String(reply), Http.acknowledges(reply, action, service))

    @Test
    fun ownAnswer() {
        ok(envelope("<u:SetAVTransportURIResponse $avt></u:SetAVTransportURIResponse>"), "SetAVTransportURI")
        ok(envelope("<u:PlayResponse $avt/>"), "Play")
        // No prefix, a default namespace, as some servers answer; another version of the service; no namespace at all.
        ok("<Envelope xmlns=\"$soap\"><Body><BrowseResponse xmlns=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><Result>&lt;DIDL-Lite/&gt;</Result></BrowseResponse></Body></Envelope>".toByteArray(), "Browse", "ContentDirectory")
        ok(envelope("<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:2\"/>"), "Browse", "ContentDirectory")
        ok(envelope("<PlayResponse/>"), "Play")
        // Far into the reply: no cut-off.
        ok(envelope("<u:PlayResponse $avt/>", " ".repeat(8192) + "<!-- ${"x".repeat(8192)} -->"), "Play")
    }

    @Test
    fun notAnAnswer() {
        no(envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode></UPnPError></detail></s:Fault>"), "SetAVTransportURI")
        no("<html><body>OK</body></html>".toByteArray(), "Play")
        no(ByteArray(0), "Play")
        no(envelope("<u:PlayResponse $avt></u:PlayResponse>"), "SetAVTransportURI") // Another action's.
        no(envelope("<u:DisplayResponse $avt></u:DisplayResponse>"), "Play") // Only ends the same.
        no(envelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"/>"), "Play") // Another service's.
        // The service's name with no version, or one that isn't a number.
        no(envelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:\"/>"), "Play")
        no(envelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:nonsense\"/>"), "Play")
        no(envelope("<u:PlayResponse xmlns:u=\"urn:example:AVTransport:1\"/>"), "Play") // Not a UPnP service's.
        // The Body in another namespace; a second Body, holding a fault.
        no("<s:Envelope xmlns:s=\"$soap\"><b:Body xmlns:b=\"urn:other\"><u:PlayResponse $avt/></b:Body></s:Envelope>".toByteArray(), "Play")
        no(("<s:Envelope xmlns:s=\"$soap\"><s:Body><u:PlayResponse $avt/></s:Body>" +
            "<s:Body><s:Fault><faultcode>s:Client</faultcode></s:Fault></s:Body></s:Envelope>").toByteArray(), "Play")
    }

    @Test
    fun errorCodes() {
        fun fault(code: String) = envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>$code</detail></s:Fault>")
        // Plain, or with a prefix as some devices send it: the special cases (1028 queue changed, 803 already a favourite) still count.
        assertEquals(714, Http.upnpError(fault("<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode></UPnPError>")))
        assertEquals(1028, Http.upnpError(fault("<u:UPnPError xmlns:u=\"urn:schemas-upnp-org:control-1-0\"><u:errorCode>1028</u:errorCode></u:UPnPError>")))
        assertEquals(0, Http.upnpError(fault("<UPnPError/>")))
        assertEquals(0, Http.upnpError("not xml".toByteArray()))
    }

    @Test
    fun tagsThatDontCount() {
        no(envelope("<!-- <u:PlayResponse $avt/> -->"), "Play")
        no(envelope("<s:Fault><faultstring><![CDATA[<u:PlayResponse $avt/>]]></faultstring></s:Fault>"), "Play")
        no(envelope("<s:Fault/><u:PlayResponse $avt/>"), "Play") // Not alone in the Body.
        no(envelope("<u:PlayResponse $avt/>").copyOf(150), "Play") // Unfinished.
        no(("<!-- <s:Envelope xmlns:s=\"$soap\"><s:Body><u:PlayResponse $avt/></s:Body></s:Envelope> -->").toByteArray(), "Play")
        no(envelope("<u:PlayResponse $avt/>") + envelope("<u:PlayResponse $avt/>"), "Play") // Two documents.
        no(envelope("<u:PlayResponse $avt/>", "<!DOCTYPE s:Envelope [<!ENTITY x \"y\">]>"), "Play") // A DTD.
        no("text<s:Envelope xmlns:s=\"$soap\"><s:Body><u:PlayResponse $avt/></s:Body></s:Envelope>".toByteArray(), "Play")
        // Not UTF-8, as UPnP's XML must be: in UTF-16 or UTF-32, with a DTD or without.
        val play = String(envelope("<u:PlayResponse $avt/>"))
        val dtd = String(envelope("<u:PlayResponse $avt/>", "<!DOCTYPE s:Envelope [<!ENTITY x \"y\">]>"))
        no("\uFEFF$play".toByteArray(Charsets.UTF_16LE), "Play")
        no("\uFEFF$dtd".toByteArray(Charsets.UTF_16LE), "Play")
        no(dtd.toByteArray(Charsets.UTF_16BE), "Play")
        no(dtd.toByteArray(charset("UTF-32BE")), "Play")
    }

    @Test
    fun argumentsInTheSamePass() {
        val reply = envelope("<u:GetPositionInfoResponse $avt><Track>3</Track><TrackMetaData>&lt;DIDL-Lite/&gt;</TrackMetaData>" +
            "<RelTime><![CDATA[0:01:02]]></RelTime></u:GetPositionInfoResponse>")
        val out = arrayOfNulls<String>(4)
        assertTrue(Http.check(reply, "GetPositionInfo", "AVTransport", arrayOf("Track", "TrackMetaData", "RelTime", "TrackURI"), out))
        assertEquals("3", out[0])
        assertEquals("<DIDL-Lite/>", out[1]) // Unescaped once, as nextText() does.
        assertEquals("0:01:02", out[2])
        assertEquals(null, out[3]) // Not there.
        // Not the answer: nothing read counts.
        assertFalse(Http.check(envelope("<u:PlayResponse $avt><Track>3</Track></u:PlayResponse>"), "GetPositionInfo", "AVTransport", arrayOf("Track"), arrayOfNulls(1)))
    }
}
