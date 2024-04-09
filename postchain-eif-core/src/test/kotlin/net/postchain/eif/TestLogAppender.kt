package net.postchain.eif

import assertk.fail
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.ErrorHandler
import org.apache.logging.log4j.core.Layout
import org.apache.logging.log4j.core.LifeCycle
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import java.io.Serializable

// Log appender used to capture and assert log entries
class TestLogAppender(private val levels: List<Level>) : Appender {

    private val events = mutableListOf<LogEvent>()

    companion object {

        private var SINGLETON: TestLogAppender? = null

        // Use singleton for all tests since appenders in log4j are static
        fun addAppender(levels: List<Level>): TestLogAppender {
            if (SINGLETON == null) {
                SINGLETON = TestLogAppender(levels)
                val context = LoggerContext.getContext(false)
                context.rootLogger.addAppender(SINGLETON)
            }
            return SINGLETON!!
        }
    }

    override fun append(event: LogEvent?) {
        if (event != null && levels.contains(event.level)) {
            events.add(event.toImmutable())
        }
    }

    fun printAll() {
        events.forEach {
            println(it.level.name() + ": " + it.message)
        }
    }

    fun assertError(message: String) {
        assertEvent(Level.ERROR, message)
    }

    fun assertWarn(message: String) {
        assertEvent(Level.WARN, message)
    }

    fun assertEvent(
            level: Level,
            message: String,
            exceptionMatch: (Throwable?) -> Boolean = { true }
    ) {

        val eventsOnLevel = events.filter { it.level == level }
        val matches = eventsOnLevel
                .filter { it.message.formattedMessage.contains(message) && exceptionMatch(it.thrown) }

        if (matches.isEmpty()) {
            fail("Expected log ${level.name()} message: $message - but only found on same level: ${eventsOnLevel.joinToString(", ") { it.message.formattedMessage }}")
        }
    }

    fun assertEventMatches(
            level: Level,
            messageReg: String,
            exceptionMatch: (Throwable?) -> Boolean = { true }
    ) {

        val regex = messageReg.toRegex()
        val eventsOnLevel = events.filter { it.level == level }
        val matches = eventsOnLevel
                .filter { it.message.formattedMessage.matches(regex) && exceptionMatch(it.thrown) }

        if (matches.isEmpty()) {
            fail("Expected log ${level.name()} message matching regex: $messageReg - but only found on same level: ${eventsOnLevel.joinToString(", ") { it.message.formattedMessage }}")
        }
    }

    fun clear() {
        events.clear()
    }

    override fun getState(): LifeCycle.State {
        throw NotImplementedError("Not supported")
    }

    override fun initialize() {
    }

    override fun start() {
    }

    override fun stop() {
    }

    override fun isStarted(): Boolean {
        return true
    }

    override fun isStopped(): Boolean {
        throw NotImplementedError("Not supported")
    }

    override fun getName(): String {
        return "TestLogAppender"
    }

    override fun getLayout(): Layout<out Serializable> {
        throw NotImplementedError("Not supported")
    }

    override fun ignoreExceptions(): Boolean {
        throw NotImplementedError("Not supported")
    }

    override fun getHandler(): ErrorHandler {
        throw NotImplementedError("Not supported")
    }

    override fun setHandler(handler: ErrorHandler?) {
        throw NotImplementedError("Not supported")
    }
}
