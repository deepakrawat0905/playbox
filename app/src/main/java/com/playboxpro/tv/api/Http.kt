package com.playboxpro.tv.api

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

object FallbackDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    // Pre-seed known Anycast CDN IPs for instantaneous 0ms resolution
    init {
        try {
            val githubPagesIps = listOf(
                InetAddress.getByAddress("iptv-org.github.io", byteArrayOf(185.toByte(), 199.toByte(), 108.toByte(), 153.toByte())),
                InetAddress.getByAddress("iptv-org.github.io", byteArrayOf(185.toByte(), 199.toByte(), 109.toByte(), 153.toByte())),
                InetAddress.getByAddress("iptv-org.github.io", byteArrayOf(185.toByte(), 199.toByte(), 110.toByte(), 153.toByte())),
                InetAddress.getByAddress("iptv-org.github.io", byteArrayOf(185.toByte(), 199.toByte(), 111.toByte(), 153.toByte()))
            )
            cache["iptv-org.github.io"] = githubPagesIps

            val rawGithubIps = listOf(
                InetAddress.getByAddress("raw.githubusercontent.com", byteArrayOf(185.toByte(), 199.toByte(), 108.toByte(), 133.toByte())),
                InetAddress.getByAddress("raw.githubusercontent.com", byteArrayOf(185.toByte(), 199.toByte(), 109.toByte(), 133.toByte())),
                InetAddress.getByAddress("raw.githubusercontent.com", byteArrayOf(185.toByte(), 199.toByte(), 110.toByte(), 133.toByte())),
                InetAddress.getByAddress("raw.githubusercontent.com", byteArrayOf(185.toByte(), 199.toByte(), 111.toByte(), 133.toByte()))
            )
            cache["raw.githubusercontent.com"] = rawGithubIps
        } catch (_: Throwable) {}
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("Empty hostname")

        // 1. Check in-memory Cache (Instant 0ms)
        cache[hostname]?.let { return it }

        // 2. Direct IP check
        if (hostname.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            val addr = listOf(InetAddress.getByName(hostname))
            cache[hostname] = addr
            return addr
        }

        // 3. Fast match for any GitHub CDN subdomains
        if (hostname.contains("githubusercontent.com")) {
            val rawGithubIps = listOf(
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 108.toByte(), 133.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 109.toByte(), 133.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 110.toByte(), 133.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 111.toByte(), 133.toByte()))
            )
            cache[hostname] = rawGithubIps
            return rawGithubIps
        }

        if (hostname.contains("github.io")) {
            val githubIps = listOf(
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 108.toByte(), 153.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 109.toByte(), 153.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 110.toByte(), 153.toByte())),
                InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 199.toByte(), 111.toByte(), 153.toByte()))
            )
            cache[hostname] = githubIps
            return githubIps
        }

        // 4. Ultra-Fast UDP DNS Query to 8.8.8.8 / 1.1.1.1 port 53 (Sub-5ms, no SSL/Android timeout)
        try {
            val udpResolved = resolveViaUdpDns(hostname, "8.8.8.8")
            if (udpResolved.isNotEmpty()) {
                cache[hostname] = udpResolved
                return udpResolved
            }
        } catch (_: Throwable) {}

        try {
            val udpResolved = resolveViaUdpDns(hostname, "1.1.1.1")
            if (udpResolved.isNotEmpty()) {
                cache[hostname] = udpResolved
                return udpResolved
            }
        } catch (_: Throwable) {}

        // 5. System DNS (Only as last fallback)
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                cache[hostname] = addresses
                return addresses
            }
        } catch (_: Throwable) {}

        throw UnknownHostException("Unable to resolve host \"$hostname\"")
    }

    private fun resolveViaUdpDns(hostname: String, dnsServerIp: String): List<InetAddress> {
        val socket = DatagramSocket()
        socket.soTimeout = 1200
        try {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)

            dos.writeShort(0x1234)
            dos.writeShort(0x0100)
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)

            val parts = hostname.split('.')
            for (p in parts) {
                val bytes = p.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)

            dos.writeShort(1) // Type A
            dos.writeShort(1) // Class IN

            val queryBytes = baos.toByteArray()
            val serverAddr = InetAddress.getByName(dnsServerIp)
            val packet = DatagramPacket(queryBytes, queryBytes.size, serverAddr, 53)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)

            val dis = DataInputStream(buffer.inputStream())
            val id = dis.readUnsignedShort()
            val flags = dis.readUnsignedShort()
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()

            if (anCount == 0) return emptyList()

            dis.skipBytes(queryBytes.size - 12)

            val results = mutableListOf<InetAddress>()
            for (i in 0 until anCount) {
                val nameType = dis.readUnsignedShort()
                val type = dis.readUnsignedShort()
                val clazz = dis.readUnsignedShort()
                val ttl = dis.readInt()
                val dataLen = dis.readUnsignedShort()

                if (type == 1 && dataLen == 4) {
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    results.add(InetAddress.getByAddress(hostname, ipBytes))
                } else {
                    dis.skipBytes(dataLen)
                }
            }
            return results
        } finally {
            socket.close()
        }
    }
}

object Http {
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext: SSLContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .addInterceptor { chain ->
            val original = chain.request()
            val req = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; PlayBoxPro TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()
            chain.proceed(req)
        }
        .build()

    fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        return response.body?.string() ?: ""
    }

    fun postJson(url: String, json: String): String {
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 404) {
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        return response.body?.string() ?: ""
    }

    fun parseObject(text: String): JSONObject {
        val t = text.trim()
        if (t.startsWith("<")) throw IOException("Server returned HTML instead of JSON data")
        return JSONObject(t)
    }

    fun parseArray(text: String): JSONArray {
        val t = text.trim()
        if (t.startsWith("<")) throw IOException("Server returned HTML instead of JSON data")
        return JSONArray(t)
    }

    /**
     * Streams a JSON array straight off the socket, handing each element to
     * [map] and keeping only what comes back.
     *
     * An Xtream VOD listing runs to tens of megabytes. Reading that into a
     * String and building a whole org.json tree from it costs far more time
     * and memory on a TV box than the download itself - and the tree is
     * thrown away immediately afterwards anyway. This keeps one row in
     * memory at a time.
     */
    fun <T : Any> streamArray(url: String, map: (JSONObject, Int) -> T?): List<T> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: return emptyList()
            val out = ArrayList<T>()

            JsonReader(body.charStream().buffered(BUFFER_CHARS)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    // An error page or an object where a list was expected.
                    if (reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NULL) {
                        return emptyList()
                    }
                    throw IOException("Server returned unexpected data instead of a list")
                }

                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    map(readObject(reader), index++)?.let { out.add(it) }
                }
                reader.endArray()
            }
            return out
        }
    }

    private const val BUFFER_CHARS = 1 shl 16

    private fun readObject(reader: JsonReader): JSONObject {
        val o = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING -> o.put(name, reader.nextString())
                JsonToken.BOOLEAN -> o.put(name, reader.nextBoolean())
                JsonToken.NULL -> reader.nextNull()
                JsonToken.NUMBER -> {
                    // Keep the numeric type so optInt / optDouble behave as
                    // they did when org.json parsed the whole document.
                    val raw = reader.nextString()
                    val value: Any = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
                    runCatching { o.put(name, value) }.onFailure { o.put(name, raw) }
                }
                JsonToken.BEGIN_OBJECT -> o.put(name, readObject(reader))
                JsonToken.BEGIN_ARRAY -> o.put(name, readArray(reader))
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return o
    }

    private fun readArray(reader: JsonReader): JSONArray {
        val a = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.STRING -> a.put(reader.nextString())
                JsonToken.BOOLEAN -> a.put(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); a.put(JSONObject.NULL) }
                JsonToken.NUMBER -> {
                    val raw = reader.nextString()
                    val value: Any = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
                    runCatching { a.put(value) }.onFailure { a.put(raw) }
                }
                JsonToken.BEGIN_OBJECT -> a.put(readObject(reader))
                JsonToken.BEGIN_ARRAY -> a.put(readArray(reader))
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return a
    }
}
