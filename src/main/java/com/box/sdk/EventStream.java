package com.box.sdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * Receives real-time events from the API and forwards them to {@link EventListener EventListeners}.
 *
 * <p>This class handles long polling the Box events endpoint in order to receive real-time user or enterprise events.
 * When an EventStream is started, it begins long polling on a separate thread until the {@link #stop} method is called.
 * Since the API may return duplicate events, EventStream also maintains a small cache of the most recently received
 * event IDs in order to automatically deduplicate events.</p>
 *
 */
public class EventStream {
    private static final int LIMIT = 800;
    private static final int LRU_SIZE = 512;
    private static final URLTemplate EVENT_URL = new URLTemplate("events?limit=" + LIMIT + "&stream_position=%s");

    private final BoxAPIConnection api;
    private final Collection<EventListener> listeners;
    private final Object listenerLock;

    private LinkedHashSet<String> receivedEvents;
    private boolean started;
    private Poller poller;
    private Thread pollerThread;

    /**
     * Constructs an EventStream using an API connection.
     * @param  api the API connection to use.
     */
    public EventStream(BoxAPIConnection api) {
        this.api = api;
        this.listeners = new ArrayList<EventListener>();
        this.listenerLock = new Object();
    }

    /**
     * Adds a listener that will be notified when an event is received.
     * @param listener the listener to add.
     */
    public void addListener(EventListener listener) {
        synchronized (this.listenerLock) {
            this.listeners.add(listener);
        }
    }

    /**
     * Indicates whether or not this EventStream has been started.
     * @return true if this EventStream has been started; otherwise false.
     */
    public boolean isStarted() {
        return this.started;
    }

    /**
     * Stops this EventStream and disconnects from the API.
     * @throws IllegalStateException if the EventStream is already stopped.
     */
    public void stop() {
        if (!this.started) {
            throw new IllegalStateException("Cannot stop the EventStream because it isn't started.");
        }

        this.started = false;
        this.pollerThread.interrupt();
    }

    /**
     * Starts this EventStream and begins long polling the API.
     * @throws IllegalStateException if the EventStream is already started.
     */
    public void start() {
        if (this.started) {
            throw new IllegalStateException("Cannot start the EventStream because it isn't stopped.");
        }

        BoxAPIRequest request = new BoxAPIRequest(this.api, EVENT_URL.build(this.api.getBaseURL(), "now"), "GET");
        BoxJSONResponse response = (BoxJSONResponse) request.send();
        JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
        final long initialPosition = jsonObject.get("next_stream_position").asLong();
        this.poller = new Poller(initialPosition);

        this.pollerThread = new Thread(this.poller);
        this.pollerThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                EventStream.this.notifyException(e);
            }
        });
        this.pollerThread.start();

        this.started = true;
    }

    /**
     * Indicates whether or not an event ID is a duplicate.
     *
     * <p>This method can be overridden by a subclass in order to provide custom de-duping logic.</p>
     *
     * @param  eventID the event ID.
     * @return         true if the event is a duplicate; otherwise false.
     */
    protected boolean isDuplicate(String eventID) {
        if (this.receivedEvents == null) {
            this.receivedEvents = new LinkedHashSet<String>(LRU_SIZE);
        }

        boolean newEvent = this.receivedEvents.add(eventID);
        if (newEvent && this.receivedEvents.size() > LRU_SIZE) {
            this.receivedEvents.iterator().remove();
        }

        return newEvent;
    }

    private void notifyEvent(BoxEvent event) {
        synchronized (this.listenerLock) {
            boolean isDuplicate = this.isDuplicate(event.getID());
            if (!isDuplicate) {
                for (EventListener listener : this.listeners) {
                    listener.onEvent(event);
                }
            }
        }
    }

    private void notifyException(Throwable e) {
        if (e instanceof InterruptedException && !this.started) {
            return;
        }

        this.stop();
        synchronized (this.listenerLock) {
            for (EventListener listener : this.listeners) {
                if (listener.onException(e)) {
                    return;
                }
            }
        }
    }

    private class Poller implements Runnable {
        private final long initialPosition;

        private RealtimeServerConnection server;

        public Poller(long initialPosition) {
            this.initialPosition = initialPosition;
            this.server = new RealtimeServerConnection(EventStream.this.api);
        }

        @Override
        public void run() {
            long position = this.initialPosition;
            while (!Thread.interrupted()) {
                if (this.server.getRemainingRetries() == 0) {
                    this.server = new RealtimeServerConnection(EventStream.this.api);
                }

                if (this.server.waitForChange(position)) {
                    if (Thread.interrupted()) {
                        return;
                    }

                    BoxAPIRequest request = new BoxAPIRequest(EventStream.this.api,
                        EVENT_URL.build(EventStream.this.api.getBaseURL(), position), "GET");
                    BoxJSONResponse response = (BoxJSONResponse) request.send();
                    JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
                    position = jsonObject.get("next_stream_position").asLong();
                    JsonArray entriesArray = jsonObject.get("entries").asArray();
                    for (JsonValue entry : entriesArray) {
                        BoxEvent event = new BoxEvent(EventStream.this.api, entry.asObject());
                        EventStream.this.notifyEvent(event);
                    }
                }
            }
        }
    }
}
