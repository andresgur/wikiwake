package com.example.wikiwake

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.random.Random

data class WikipediaArticle(val title: String, val extract: String, val url: String, val imageUrl: String? = null)

class WikipediaClient {
    fun fetch(
        languageOverride: String,
        topics: List<String>,
        previousTitles: Set<String>,
        randomness: Int = 0
    ): WikipediaArticle {
        val language = languageOverride.trim().ifEmpty { Locale.getDefault().language }
            .lowercase(Locale.ROOT)
        if (!language.matches(Regex("[a-z][a-z0-9-]{1,14}"))) {
            throw IOException("Enter a valid Wikipedia language code, such as en or es.")
        }

        if (topics.isEmpty() || Random.nextInt(100) < randomness) {
            return fetchRandom(language, previousTitles)
        }
        return try {
            fetchTopic(language, topics, previousTitles)
        } catch (error: IOException) {
            // Topic search came up empty or failed: a random article beats silence.
            fetchRandom(language, previousTitles)
        }
    }

    private fun fetchRandom(language: String, previousTitles: Set<String>): WikipediaArticle {
        for (attempt in 1..30) {
            val uri = Uri.Builder()
                .scheme("https")
                .authority("$language.wikipedia.org")
                .appendPath("w")
                .appendPath("api.php")
                .appendQueryParameter("action", "query")
                .appendQueryParameter("generator", "random")
                .appendQueryParameter("grnnamespace", "0")
                .appendQueryParameter("grnlimit", "1")
                .appendQueryParameter("prop", "extracts|pageprops|pageimages")
                .appendQueryParameter("piprop", "thumbnail")
                .appendQueryParameter("pilicense", "any")
                .appendQueryParameter("pithumbsize", "640")
                .appendQueryParameter("ppprop", "disambiguation")
                .appendQueryParameter("explaintext", "1")
                .appendQueryParameter("format", "json")
                .appendQueryParameter("formatversion", "2")
                .build()
            val pages = getJson(uri.toString())
                .optJSONObject("query")
                ?.optJSONArray("pages")
                ?: throw IOException("Wikipedia returned no random article.")
            val page = pages.optJSONObject(0) ?: continue
            if (isDisambiguation(page)) continue
            val title = page.optString("title")
            val extract = page.optString("extract")
            if (extract.isNotBlank() && title !in previousTitles) {
                return WikipediaArticle(
                    title = title,
                    extract = extract,
                    url = "https://$language.wikipedia.org/wiki/${Uri.encode(title.replace(' ', '_'))}",
                    imageUrl = thumbnailOf(page)
                )
            }
        }
        throw IOException("Could not find an unread random article. Try again later.")
    }

    private fun fetchTopic(
        language: String,
        topics: List<String>,
        previousTitles: Set<String>
    ): WikipediaArticle {
        val query = topics.joinToString(" OR ") { it.trim() }
        for (offset in 0..450 step 50) {
            val uri = Uri.Builder()
                .scheme("https")
                .authority("$language.wikipedia.org")
                .appendPath("w")
                .appendPath("api.php")
                .appendQueryParameter("action", "query")
                .appendQueryParameter("generator", "search")
                .appendQueryParameter("gsrsearch", query)
                .appendQueryParameter("gsrnamespace", "0")
                .appendQueryParameter("gsrlimit", "50")
                .appendQueryParameter("gsroffset", offset.toString())
                .appendQueryParameter("prop", "info|pageprops")
                .appendQueryParameter("ppprop", "disambiguation")
                .appendQueryParameter("redirects", "1")
                .appendQueryParameter("format", "json")
                .appendQueryParameter("formatversion", "2")
                .build()
            val json = getJson(uri.toString())
            val pages = json.optJSONObject("query")?.optJSONArray("pages")
            if (pages == null) {
                if (offset == 0) throw IOException("Wikipedia found no articles for those topics.")
                break
            }
            val candidates = buildList<Pair<String, String>> {
                for (index in 0 until pages.length()) {
                    val page = pages.optJSONObject(index) ?: continue
                    val title = page.optString("title")
                    val pageId = page.optString("pageid")
                    if (title.isNotBlank() && pageId.isNotBlank() && title !in previousTitles && !isDisambiguation(page)) {
                        add(title to pageId)
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                return fetchArticle(language, candidates.random().second)
            }
            if (pages.length() == 0) break
        }
        throw IOException("No unread articles found for those topics. Try different topics.")
    }

    private fun fetchArticle(language: String, pageId: String): WikipediaArticle {
        val uri = Uri.Builder()
            .scheme("https")
            .authority("$language.wikipedia.org")
            .appendPath("w")
            .appendPath("api.php")
            .appendQueryParameter("action", "query")
            .appendQueryParameter("pageids", pageId)
            .appendQueryParameter("prop", "extracts|pageprops|pageimages")
            .appendQueryParameter("piprop", "thumbnail")
                .appendQueryParameter("pilicense", "any")
            .appendQueryParameter("pithumbsize", "640")
            .appendQueryParameter("ppprop", "disambiguation")
            .appendQueryParameter("explaintext", "1")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2")
            .build()
        val page = getJson(uri.toString())
            .optJSONObject("query")
            ?.optJSONArray("pages")
            ?.optJSONObject(0)
            ?: throw IOException("Wikipedia returned no article text.")
        if (isDisambiguation(page)) throw IOException("Picked a disambiguation page.")
        val title = page.optString("title")
        val extract = page.optString("extract")
        if (title.isBlank() || extract.isBlank()) throw IOException("Wikipedia article has no readable text.")
        return WikipediaArticle(
            title = title,
            extract = extract,
            url = "https://$language.wikipedia.org/wiki/${Uri.encode(title.replace(' ', '_'))}",
            imageUrl = thumbnailOf(page)
        )
    }

    private fun thumbnailOf(page: JSONObject): String? =
        page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("https://") }

    fun fetchBitmap(address: String): Bitmap? {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "WikiWake/1.0 (Android)")
        return try {
            if (connection.responseCode in 200..299) BitmapFactory.decodeStream(connection.inputStream) else null
        } catch (error: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isDisambiguation(page: JSONObject): Boolean =
        page.optJSONObject("pageprops")?.has("disambiguation") == true

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "WikiWake/1.0 (Android)")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Wikipedia returned HTTP $status.")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (error: JSONException) {
            throw IOException("Wikipedia returned an unreadable article.", error)
        } finally {
            connection.disconnect()
        }
    }
}
