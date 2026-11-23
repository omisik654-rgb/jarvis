package com.jarvis.assistant

object JarvisState {
    @Volatile var speaking = false
    @Volatile var listening = false
    @Volatile var thinking = false
    @Volatile var running = false
    @Volatile var line = ""
}
