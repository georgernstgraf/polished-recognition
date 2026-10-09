package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM tests for the #117 shadow-comparison endpoint gate. */
class SttEndpointPolicyTest {

    @Test
    fun `LAN IPv4 endpoints are local`() {
        assertThat(SttEndpointPolicy.isLocal("http://10.8.0.16:11437/v1/")).isTrue()
        assertThat(SttEndpointPolicy.isLocal("http://192.168.1.50:8000/v1/")).isTrue()
        assertThat(SttEndpointPolicy.isLocal("http://172.16.0.5/v1/")).isTrue()
        assertThat(SttEndpointPolicy.isLocal("http://127.0.0.1:9000/v1/")).isTrue()
    }

    @Test
    fun `localhost and mDNS names are local`() {
        assertThat(SttEndpointPolicy.isLocal("http://localhost:11437/v1/")).isTrue()
        assertThat(SttEndpointPolicy.isLocal("http://gregor.local:11437/v1/")).isTrue()
    }

    @Test
    fun `cloud endpoints are not local`() {
        assertThat(SttEndpointPolicy.isLocal("https://api.groq.com/openai/v1/")).isFalse()
        assertThat(SttEndpointPolicy.isLocal("https://api.openai.com/v1/")).isFalse()
        // public IP ranges must never match the private regexes
        assertThat(SttEndpointPolicy.isLocal("http://100.64.0.1/v1/")).isFalse()
        assertThat(SttEndpointPolicy.isLocal("http://11.0.0.1/v1/")).isFalse()
        assertThat(SttEndpointPolicy.isLocal("http://192.169.0.1/v1/")).isFalse()
        assertThat(SttEndpointPolicy.isLocal("http://172.32.0.1/v1/")).isFalse()
    }

    @Test
    fun `unparsable URLs are not local`() {
        assertThat(SttEndpointPolicy.isLocal("not a url")).isFalse()
        assertThat(SttEndpointPolicy.isLocal("")).isFalse()
    }
}
