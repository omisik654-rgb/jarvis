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

    fun ask(
        key: String,
        text: String,
        system: String,
        search: Boolean,
        maxTokens: Int,
        tools: JSONArray?,
        exec: ((String, JSONObject) -> String)?
    ): String {
        history.add(Pair("user", text))
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        try {
            val msgs = JSONArray()
            for (h in history) {
                msgs.put(JSONObject().put("role", h.first).put("content", h.second))
            }
            var useSearch = search
            var rounds = 0
            var answer: String
            while (true) {
                val resp = try {
                    post(key, system, msgs, tools, useSearch, maxTokens)
                } catch (e: ApiError) {
                    if (useSearch && e.code == 400) {
                        useSearch = false
                        post(key, system, msgs, tools, false, maxTokens)
                    } else {
                        throw e
                    }
                }
                val content = resp.getJSONArray("content")
                msgs.put(JSONObject().put("role", "assistant").put("content", content))
                val sb = StringBuilder()
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    val type = b.optString("type")
                    if (type == "text") {
                        sb.append(b.optString("text"))
                    } else if (type == "tool_use") {
                        val out = try {
                            exec?.invoke(b.getString("name"), b.optJSONObject("input") ?: JSONObject()) ?: "Araç yok"
                        } catch (e: Throwable) {
                            "Hata: " + e.message
                        }
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", out)
                        )
                    }
                }
                rounds++
                if (results.length() > 0 && rounds < 7) {
                    msgs.put(JSONObject().put("role", "user").put("content", results))
                    continue
                }
                if (resp.optString("stop_reason") == "pause_turn" && rounds < 7) continue
                answer = sb.toString().trim()
                break
            }
            if (answer.isBlank()) answer = "Tamamdır efendim"
            history.add(Pair("assistant", answer))
            return answer
        } catch (e: Throwable) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            throw e
        }
    }

    private fun post(
        key: String,
        system: String,
        msgs: JSONArray,
        tools: JSONArray?,
        search: Boolean,
        maxTokens: Int
    ): JSONObject {
        val body = JSONObject()
        body.put("model", MODEL)
        body.put("max_tokens", maxTokens)
        body.put("system", system)
        body.put("messages", msgs)
        val all = JSONArray()
        if (tools != null) {
            for (i in 0 until tools.length()) all.put(tools.getJSONObject(i))
        }
        if (search) {
            val s = JSONObject()
            s.put("type", "web_search_20250305")
            s.put("name", "web_search")
            s.put("max_uses", 3)
            all.put(s)
        }
        if (all.length() > 0) body.put("tools", all)
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
        return JSONObject(text)
    }
}
