package com.reconai.app

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The same tools as the Termux version, rewritten without Python. */
object Tools {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0 Mobile Safari/537.36 ReconAI-Android"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun get(url: String, accept: String? = null): okhttp3.Response {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (accept != null) b.header("Accept", accept)
        return http.newCall(b.build()).execute()
    }

    private fun withScheme(u: String) = if (u.startsWith("http")) u else "https://$u"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun webSearch(query: String, n: Int = 8): Any {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/")
            .header("User-Agent", UA).post(FormBody.Builder().add("q", query).build()).build()
        val html = http.newCall(req).execute().use { it.body?.string() ?: "" }
        val doc = Jsoup.parse(html)
        val out = JSONArray()
        for (d in doc.select(".result")) {
            if (out.length() >= n) break
            val a = d.selectFirst("a.result__a") ?: continue
            var href = a.attr("href")
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            out.put(JSONObject().put("title", a.text()).put("url", href)
                .put("snippet", d.selectFirst(".result__snippet")?.text() ?: ""))
        }
        return if (out.length() > 0) out
        else JSONObject().put("error", "Поиск ничего не вернул (возможно, DuckDuckGo временно ограничил запросы)")
    }

    fun fetchUrl(url: String): Any {
        get(withScheme(url)).use { r ->
            val doc = Jsoup.parse(r.body?.string() ?: "", r.request.url.toString())
            doc.select("script,style,noscript").remove()
            val links = JSONArray()
            for (a in doc.select("a[href]")) {
                val h = a.absUrl("href")
                if (h.startsWith("http")) links.put(JSONObject().put("t", a.text().take(60)).put("href", h))
                if (links.length() >= 30) break
            }
            return JSONObject().put("status", r.code).put("url", r.request.url.toString())
                .put("title", doc.title()).put("text", doc.body()?.text()?.take(3500) ?: "").put("links", links)
        }
    }

    /** DNS over HTTPS (Cloudflare): works on any network, no system resolver needed. */
    fun dnsLookup(domain: String): Any {
        val out = JSONObject()
        for (t in listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA")) {
            try {
                val j = get("https://cloudflare-dns.com/dns-query?name=${enc(domain)}&type=$t", "application/dns-json")
                    .use { JSONObject(it.body?.string() ?: "{}") }
                val ans = j.optJSONArray("Answer") ?: continue
                val vals = JSONArray()
                for (i in 0 until ans.length()) {
                    val a = ans.getJSONObject(i)
                    if (a.optString("name").trimEnd('.').equals(domain.trimEnd('.'), true) || t == "CNAME")
                        vals.put(a.optString("data"))
                }
                if (vals.length() > 0) out.put(t, vals)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun whoisRaw(server: String, query: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 43), 15000)
            s.soTimeout = 20000
            s.getOutputStream().write("$query\r\n".toByteArray())
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Classic WHOIS on port 43: IANA tells which registry serves the zone (works for .ru too). */
    fun whoisLookup(domain: String): Any {
        val d = domain.lowercase().removePrefix("www.")
        val tld = d.substringAfterLast('.')
        val iana = whoisRaw("whois.iana.org", tld)
        val server = Regex("(?im)^whois:\\s*(\\S+)").find(iana)?.groupValues?.get(1)
            ?: return JSONObject().put("error", "для зоны .$tld нет WHOIS-сервера")
        var text = whoisRaw(server, d)
        Regex("(?im)^\\s*Registrar WHOIS Server:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { reg ->
            if (reg.isNotBlank() && !reg.equals(server, true)) {
                try { text = whoisRaw(reg.removePrefix("http://").removePrefix("https://").trimEnd('/'), d) + "\n" + text } catch (_: Exception) {}
            }
        }
        fun first(vararg keys: String): String? {
            for (k in keys) Regex("(?im)^\\s*$k:\\s*(.+)$").find(text)?.let { return it.groupValues[1].trim() }
            return null
        }
        val ns = Regex("(?im)^\\s*(?:Name Server|nserver):\\s*(\\S+)").findAll(text)
            .map { it.groupValues[1].lowercase().trimEnd('.') }.distinct().toList()
        return JSONObject()
            .put("whois_server", server)
            .put("registrar", first("Registrar", "registrar"))
            .put("creation_date", first("Creation Date", "created", "Registered on"))
            .put("expiration_date", first("Registry Expiry Date", "Registrar Registration Expiration Date", "Expiration Date", "paid-till", "Expiry date"))
            .put("name_servers", JSONArray(ns))
            .put("org", first("Registrant Organization", "org"))
            .put("country", first("Registrant Country", "country"))
            .put("raw", text.take(1200))
    }

    fun subdomains(domain: String): Any {
        val body = get("https://crt.sh/?q=%25.${enc(domain)}&output=json").use { it.body?.string() ?: "[]" }
        val arr = JSONArray(body)
        val names = sortedSetOf<String>()
        for (i in 0 until arr.length()) {
            arr.getJSONObject(i).optString("name_value").split("\n").forEach {
                val n = it.trim().lowercase()
                if (n.isNotEmpty() && '*' !in n) names.add(n)
            }
        }
        return JSONArray(names.take(150))
    }

    fun ipInfo(ipOrHost: String): Any {
        val ip = try { InetAddress.getByName(ipOrHost).hostAddress ?: ipOrHost } catch (_: Exception) { ipOrHost }
        val j = get("https://ipwho.is/$ip").use { JSONObject(it.body?.string() ?: "{}") }
        val out = JSONObject()
        for (k in listOf("ip", "success", "type", "country", "region", "city", "latitude", "longitude", "connection"))
            if (j.has(k)) out.put(k, j.get(k))
        return out
    }

    fun httpHeaders(url: String): Any {
        get(withScheme(url)).use { r ->
            val redirects = JSONArray()
            var p = r.priorResponse
            val chain = mutableListOf<String>()
            while (p != null) { chain.add(0, p.request.url.toString()); p = p.priorResponse }
            chain.forEach { redirects.put(it) }
            val h = JSONObject()
            for (name in r.headers.names()) h.put(name, r.headers(name).joinToString(", "))
            return JSONObject().put("final_url", r.request.url.toString()).put("status", r.code)
                .put("redirects", redirects).put("headers", h)
        }
    }

    fun wayback(url: String, limit: Int = 15): Any {
        val body = get("https://web.archive.org/cdx/search/cdx?url=${enc(url)}&output=json&limit=$limit&collapse=timestamp:6")
            .use { it.body?.string() ?: "[]" }
        val rows = if (body.isBlank()) JSONArray() else JSONArray(body)
        val out = JSONArray()
        for (i in 1 until rows.length()) {
            val x = rows.getJSONArray(i)
            out.put(JSONObject().put("time", x.getString(1))
                .put("url", "https://web.archive.org/web/${x.getString(1)}/${x.getString(2)}").put("status", x.getString(4)))
        }
        return out
    }

    fun imageExif(path: String): Any {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val ex = ExifInterface(path)
        val data = JSONObject()
        val tags = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_DATESTAMP
        )
        for (t in tags) ex.getAttribute(t)?.let { data.put(t, it.take(150)) }
        ex.latLong?.let { (lat, lon) ->
            data.put("GPS", JSONObject().put("lat", "%.6f".format(java.util.Locale.US, lat).toDouble())
                .put("lon", "%.6f".format(java.util.Locale.US, lon).toDouble())
                .put("map", "https://www.google.com/maps/search/?api=1&query=$lat,$lon"))
        }
        return JSONObject().put("size", JSONArray(listOf(opts.outWidth, opts.outHeight)))
            .put("format", opts.outMimeType ?: "").put("exif", data)
    }

    data class Spec(val name: String, val desc: String, val param: String, val paramDesc: String)

    val SPECS = listOf(
        Spec("web_search", "Поиск в интернете", "query", "запрос"),
        Spec("fetch_url", "Открыть страницу и получить её текст и ссылки", "url", "URL"),
        Spec("dns_lookup", "DNS-записи домена", "domain", "домен"),
        Spec("whois_lookup", "WHOIS домена", "domain", "домен"),
        Spec("subdomains", "Поддомены из Certificate Transparency", "domain", "домен"),
        Spec("ip_info", "Провайдер, ASN и гео для IP или домена", "ip", "IP или домен"),
        Spec("http_headers", "HTTP-заголовки и цепочка редиректов", "url", "URL или домен"),
        Spec("wayback", "Снимки сайта в Wayback Machine", "url", "URL или домен"),
        Spec("image_exif", "EXIF и GPS из загруженного фото", "path", "путь к файлу"),
    )

    fun specJson(s: Spec): JSONObject = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", s.name).put("description", s.desc).put("parameters",
            JSONObject().put("type", "object").put("required", JSONArray(listOf(s.param)))
                .put("properties", JSONObject().put(s.param, JSONObject().put("type", "string").put("description", s.paramDesc)))))

    fun paramOf(name: String): String? = SPECS.firstOrNull { it.name == name }?.param

    fun run(name: String, args: JSONObject): Any = try {
        val p = paramOf(name) ?: return JSONObject().put("error", "нет такого инструмента: $name")
        val v = args.optString(p, "").ifBlank { args.keys().asSequence().map { args.optString(it) }.firstOrNull() ?: "" }.trim()
        if (v.isEmpty()) JSONObject().put("error", "не указан параметр $p")
        else when (name) {
            "web_search" -> webSearch(v)
            "fetch_url" -> fetchUrl(v)
            "dns_lookup" -> dnsLookup(v)
            "whois_lookup" -> whoisLookup(v)
            "subdomains" -> subdomains(v)
            "ip_info" -> ipInfo(v)
            "http_headers" -> httpHeaders(v)
            "wayback" -> wayback(v)
            "image_exif" -> imageExif(v)
            else -> JSONObject().put("error", "нет такого инструмента: $name")
        }
    } catch (e: Exception) {
        JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}")
    }

    fun toText(res: Any, limit: Int): String = res.toString().take(limit)
}
