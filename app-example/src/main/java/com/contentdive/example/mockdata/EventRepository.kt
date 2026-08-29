package com.contentdive.example.mockdata

/** The app's source of truth. Search results contain references, not copies of these records. */
internal interface EventRepository {
    fun all(): List<Event>

    fun event(id: String): Event?
}

internal class MockEventRepository(
    private val events: List<Event> = MockEvents.all,
) : EventRepository {
    override fun all(): List<Event> = events

    override fun event(id: String): Event? = events.firstOrNull { it.id == id }
}
