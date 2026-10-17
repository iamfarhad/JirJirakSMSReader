package ir.jirjirak.sms

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A bot on 127.0.0.1 inside the test process — no real service, no real money. Every request is
 * recorded; [handler] answers it, or returns null to drop the connection without a reply (what a
 * timeout looks like to the app).
 */
class FakeBot(var handler: (method: String, path: String, body: String) -> Pair<Int, String>?) {

    data class Request(val method: String, val path: String, val body: String, val contentType: String = "")

    val requests = CopyOnWriteArrayList<Request>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    val endpoint get() = "http://127.0.0.1:$port/api/bank-sms/$SECRET"

    init {
        thread(isDaemon = true, name = "fake-bot") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val first = input.readLine() ?: return
        val (method, rawPath) = first.split(" ").let { it[0] to it[1] }
        var length = 0
        var type = ""
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
            if (line.lowercase().startsWith("content-type:")) type = line.substringAfter(':').trim()
        }
        // Content-Length counts bytes; the reader counts chars — read until the bytes are covered.
        val sb = StringBuilder()
        var got = 0
        while (got < length) {
            val n = input.read()
            if (n < 0) break
            sb.append(n.toChar())
            got += n.toChar().toString().toByteArray(Charsets.UTF_8).size
        }
        val body = sb.toString()
        val path = rawPath.removePrefix("/api/bank-sms/$SECRET")
        requests += Request(method, path, body, type)
        val reply = handler(method, path, body) ?: return
        val bytes = reply.second.toByteArray(Charsets.UTF_8)
        val out = s.getOutputStream()
        out.write(("HTTP/1.1 ${reply.first} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    fun close() = runCatching { server.close() }

    companion object {
        const val SECRET = "TestSecretOnlyForUiTests123"

        fun ping(features: List<String> = listOf("dry_run", "assign", "formats", "insights", "devices")) =
            200 to JSONObject().put("shop", "فروشگاه آزمون").put("features", JSONArray(features)).toString()

        fun summary(needsReview: Int = 1, autoOn: Boolean = true) = 200 to JSONObject()
            .put("auto_on", autoOn)
            .put("metrics", JSONObject().put("auto_approved", 4).put("manual_approved", 1).put("needs_review", needsReview).put("deposits_total", 1_250_000).put("deposits_count", 5))
            .put("previous", JSONObject())
            .put("actions", JSONArray().put(JSONObject().put("kind", "review").put("count", needsReview).put("title", "$needsReview رسید منتظر تصمیم").put("hint", "بررسی مبلغ و نشانه‌ها")))
            .put("open", JSONObject().put("mismatch", 0))
            .toString()

        fun receipt(id: Long, state: String, amount: Long = 320_000) = JSONObject()
            .put("id", id).put("kind", "payment").put("state", state).put("amount", amount)
            .put("customer", "مشتری آزمون").put("tracking", "T-$id").put("rail", "کارت‌به‌کارت")
            .put("created_at", System.currentTimeMillis() / 1000).put("has_image", false)
            .put("auto", JSONObject().put("score", 50).put("threshold", 70))
            .apply { if (state == "rejected") put("reason", "تراکنش در حساب مقصد یافت نشد") }

        fun history(vararg items: JSONObject) = 200 to JSONObject().put("items", JSONArray(items.toList())).put("has_more", false).toString()

        fun detail(id: Long, state: String) = 200 to JSONObject()
            .put("receipt", receipt(id, state))
            .put("analysis", JSONObject().put("score", 50).put("threshold", 70).put("enough", false)
                .put("signals", JSONArray().put(JSONObject().put("label", "مبلغ").put("ok", true).put("weight", 50).put("earned", 50))))
            .put("suggestion", JSONObject().put("tone", "warn").put("title", "بررسی واریز لازم است"))
            .put("timeline", JSONArray())
            .toString()

        fun reasons() = 200 to JSONObject().put("reasons", JSONArray()
            .put(JSONObject().put("code", "not_found").put("label", "تراکنش در حساب مقصد یافت نشد"))
            .put(JSONObject().put("code", "amount").put("label", "مبلغ واریز با سفارش مغایرت دارد"))).toString()
    }
}
