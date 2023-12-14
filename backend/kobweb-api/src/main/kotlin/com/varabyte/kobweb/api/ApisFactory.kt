package com.varabyte.kobweb.api

import com.varabyte.kobweb.api.env.Environment
import com.varabyte.kobweb.api.event.Events
import com.varabyte.kobweb.api.log.Logger

/**
 * An interface for creating an [Apis] instance.
 *
 * This class is provided to help with reflective access by a Kobweb server. Users should never have to interact with it
 * directly. It is expected that a Kobweb project will generate an implementation for this at compile-time.
 */
interface ApisFactory {
    fun create(env: Environment, events: Events, logger: Logger): Apis
}
