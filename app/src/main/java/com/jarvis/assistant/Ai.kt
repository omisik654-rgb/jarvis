package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiError(val code: Int, msg: String) : Exception(msg)

object Ai {
    private const val MODEL = "claude-haiku-5-5"
    private val history = mutableListOf<Pair<String, String>>()

    fun clear() {
        history.clear()
    }

    fun ask(key: String, text: String, system: String, search: Boolean, maxTokens: Int): String {
        history.add(Pair("user", text))
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        try {
            val answer = try {
                call(key, system, search, maxTokens)
            } catch (e: ApiError) {
                if (search && e.code == 400) call(key, system, false, maxTokens) else throw e
            }
            history.add(Pair("assistant", answer))
            return answer
        } catch (e: Throwable) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            throw e
        }
    }

    private fun call(key: String, system: String, search: Boolean, maxTokens: Int): String {
        val msgs = JSONArray()
        for (h in history) {
            msgs.put(JSONObject().put("role", h.first).put("content", h.second))
        }
        val body = JSONObject()
        body.put("model", MODEL)
        body.put("max_tokens", maxTokens)
        body.put("system", system)
        body.put("messages", msgs)
        if (search) {
            val tool = JSONObject()
            tool.put("type", "web_search_20250305")
            tool.put("name", "web_search")
            tool.put("max_uses", 3)
            body.put("tools", JSONArray().put(tool))
        }
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").getString("message")
            } catch (e: Throwable) {
                "HTTP $code"
            }
            throw ApiError(code, msg)
        }
        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }
}
