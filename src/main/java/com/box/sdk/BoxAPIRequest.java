package com.box.sdk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Used to make HTTP requests to the Box API.
 *
 * <p>All requests to the REST API are sent using this class or one of its subclasses. This class wraps {@link
 * HttpURLConnection} in order to provide a simpler interface that can automatically handle various conditions specific
 * to Box's API. Requests will be authenticated using a {@link BoxAPIConnection} (if one is provided), so it isn't
 * necessary to add authorization headers. Requests can also be sent more than once, unlike with HttpURLConnection. If
 * an error occurs while sending a request, it will be automatically retried (with a back off delay) up to the maximum
 * number of times set in the BoxAPIConnection.</p>
 *
 * <p>Specifying a body for a BoxAPIRequest is done differently than it is with HttpURLConnection. Instead of writing to
 * an OutputStream, the request is provided an {@link InputStream} which will be read when the {@link #send} method is
 * called. This makes it easy to retry requests since the stream can automatically reset and reread with each attempt.
 * If the stream cannot be reset, then a new stream will need to be provided before each call to send. There is also a
 * convenience method for specifying the body as a String, which simply wraps the String with an InputStream.</p>
 */
public class BoxAPIRequest {
    private static final Logger LOGGER = Logger.getLogger(BoxAPIRequest.class.getName());

    private final BoxAPIConnection api;
    private final URL url;
    private final List<RequestHeader> headers;
    private final String method;

    private BackoffCounter backoffCounter;
    private int timeout;
    private InputStream body;
    private long bodyLength;
    private Map<String, List<String>> requestProperties;

    /**
     * Constructs an unauthenticated BoxAPIRequest.
     * @param  url    the URL of the request.
     * @param  method the HTTP method of the request.
     */
    public BoxAPIRequest(URL url, String method) {
        this(null, url, method);
    }

    /**
     * Constructs an authenticated BoxAPIRequest using a provided BoxAPIConnection.
     * @param  api    an API connection for authenticating the request.
     * @param  url    the URL of the request.
     * @param  method the HTTP method of the request.
     */
    public BoxAPIRequest(BoxAPIConnection api, URL url, String method) {
        this.api = api;
        this.url = url;
        this.method = method;
        this.headers = new ArrayList<RequestHeader>();
        this.backoffCounter = new BackoffCounter(new Time());

        this.addHeader("Accept-Encoding", "gzip");
        this.addHeader("Accept-Charset", "utf-8");
    }

    /**
     * Adds an HTTP header to this request.
     * @param key   the header key.
     * @param value the header value.
     */
    public void addHeader(String key, String value) {
        this.headers.add(new RequestHeader(key, value));
    }

    /**
     * Sets a timeout for this request in milliseconds.
     * @param timeout the timeout in milliseconds.
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Sets the request body to the contents of an InputStream.
     *
     * <p>The stream must support the {@link InputStream#reset} method if auto-retry is used or if the request needs to
     * be resent. Otherwise, the body must be manually set before each call to {@link #send}.</p>
     *
     * @param stream an InputStream containing the contents of the body.
     */
    public void setBody(InputStream stream) {
        this.body = stream;
    }

    /**
     * Sets the request body to the contents of an InputStream.
     *
     * <p>Providing the length of the InputStream allows for the progress of the request to be monitored when calling
     * {@link #send(ProgressListener)}.</p>
     *
     * <p> See {@link #setBody(InputStream)} for more information on setting the body of the request.</p>
     *
     * @param stream an InputStream containing the contents of the body.
     * @param length the expected length of the stream.
     */
    public void setBody(InputStream stream, long length) {
        this.bodyLength = length;
        this.body = stream;
    }

    /**
     * Sets the request body to the contents of a String.
     *
     * <p>If the contents of the body are large, then it may be more efficient to use an {@link InputStream} instead of
     * a String. Using a String requires that the entire body be in memory before sending the request.</p>
     *
     * @param body a String containing the contents of the body.
     */
    public void setBody(String body) {
        this.bodyLength = body.length();
        this.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends this request and returns a BoxAPIResponse containing the server's response.
     *
     * <p>The type of the returned BoxAPIResponse will be based on the content type returned by the server, allowing it
     * to be cast to a more specific type. For example, if it's known that the API call will return a JSON response,
     * then it can be cast to a {@link BoxJSONResponse} like so:</p>
     *
     * <pre>BoxJSONResponse response = (BoxJSONResponse) request.send();</pre>
     *
     * <p>If the server returns an error code or if a network error occurs, then the request will be automatically
     * retried. If the maximum number of retries is reached and an error still occurs, then a {@link BoxAPIException}
     * will be thrown.</p>
     *
     * @throws BoxAPIException if the server returns an error code or if a network error occurs.
     * @return a {@link BoxAPIResponse} containing the server's response.
     */
    public BoxAPIResponse send() {
        return this.send(null);
    }

    /**
     * Sends this request while monitoring its progress and returns a BoxAPIResponse containing the server's response.
     *
     * <p>A ProgressListener is generally only useful when the size of the request is known beforehand. If the size is
     * unknown, then the ProgressListener will be updated for each byte sent, but the total number of bytes will be
     * reported as 0.<p>
     *
     * <p> See {@link #send} for more information on sending requests.</p>
     *
     * @param  listener a listener for monitoring the progress of the request.
     * @throws BoxAPIException if the server returns an error code or if a network error occurs.
     * @return a {@link BoxAPIResponse} containing the server's response.
     */
    public BoxAPIResponse send(ProgressListener listener) {
        if (this.api == null) {
            this.backoffCounter.reset(BoxAPIConnection.DEFAULT_MAX_ATTEMPTS);
        } else {
            this.backoffCounter.reset(this.api.getMaxRequestAttempts());
        }

        while (this.backoffCounter.getAttemptsRemaining() > 0) {
            try {
                return this.trySend(listener);
            } catch (BoxAPIException apiException) {
                if (!this.backoffCounter.decrement() || !isResponseRetryable(apiException.getResponseCode())) {
                    throw apiException;
                }

                try {
                    this.resetBody();
                } catch (IOException ioException) {
                    throw apiException;
                }

                try {
                    this.backoffCounter.waitBackoff();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw apiException;
                }
            }
        }

        throw new RuntimeException();
    }

    /**
     * Returns a String containing the URL, HTTP method, headers and body of this request.
     * @return a String containing information about this request.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Request");
        builder.append(System.lineSeparator());
        builder.append(this.method);
        builder.append(' ');
        builder.append(this.url.toString());
        builder.append(System.lineSeparator());

        for (Map.Entry<String, List<String>> entry : this.requestProperties.entrySet()) {
            List<String> nonEmptyValues = new ArrayList<String>();
            for (String value : entry.getValue()) {
                if (value != null && value.trim().length() != 0) {
                    nonEmptyValues.add(value);
                }
            }

            if (nonEmptyValues.size() == 0) {
                continue;
            }

            builder.append(entry.getKey());
            builder.append(": ");
            for (String value : nonEmptyValues) {
                builder.append(value);
                builder.append(", ");
            }

            builder.delete(builder.length() - 2, builder.length());
            builder.append(System.lineSeparator());
        }

        String bodyString = this.bodyToString();
        if (bodyString != null) {
            builder.append(System.lineSeparator());
            builder.append(bodyString);
        }

        return builder.toString().trim();
    }

    /**
     * Returns a String representation of this request's body used in {@link #toString}. This method returns
     * null by default.
     *
     * <p>A subclass may want override this method if the body can be converted to a String for logging or debugging
     * purposes.</p>
     *
     * @return a String representation of this request's body.
     */
    protected String bodyToString() {
        return null;
    }

    /**
     * Writes the body of this request to an HttpURLConnection.
     *
     * <p>Subclasses overriding this method must remember to close the connection's OutputStream after writing.</p>
     *
     * @param connection the connection to which the body should be written.
     * @param listener   an optional listener for monitoring the write progress.
     * @throws BoxAPIException if an error occurs while writing to the connection.
     */
    protected void writeBody(HttpURLConnection connection, ProgressListener listener) {
        if (this.body == null) {
            return;
        }

        connection.setDoOutput(true);
        try {
            OutputStream output = connection.getOutputStream();
            if (listener != null) {
                output = new ProgressOutputStream(output, listener, this.bodyLength);
            }
            int b = this.body.read();
            while (b != -1) {
                output.write(b);
                b = this.body.read();
            }
            output.close();
        } catch (IOException e) {
            throw new BoxAPIException("Couldn't connect to the Box API due to a network error.", e);
        }
    }

    /**
     * Resets the InputStream containing this request's body.
     *
     * <p>This method will be called before each attempt to resend the request, giving subclasses an opportunity to
     * reset any streams that need to be read when sending the body.</p>
     *
     * @throws IOException if the stream cannot be reset.
     */
    protected void resetBody() throws IOException {
        if (this.body != null) {
            this.body.reset();
        }
    }

    void setBackoffCounter(BackoffCounter counter) {
        this.backoffCounter = counter;
    }

    private BoxAPIResponse trySend(ProgressListener listener) {
        HttpURLConnection connection = this.createConnection();
        connection.setRequestProperty("User-Agent", "Box Java SDK v0.4");

        if (this.bodyLength > 0) {
            connection.setFixedLengthStreamingMode(this.bodyLength);
            connection.setDoOutput(true);
        }

        if (this.api != null) {
            connection.addRequestProperty("Authorization", "Bearer " + this.api.getAccessToken());
        }

        this.requestProperties = connection.getRequestProperties();
        this.writeBody(connection, listener);

        // Ensure that we're connected in case writeBody() didn't write anything.
        try {
            connection.connect();
        } catch (IOException e) {
            throw new BoxAPIException("Couldn't connect to the Box API due to a network error.", e);
        }

        this.logRequest(connection);

        String contentType = connection.getContentType();
        BoxAPIResponse response;
        if (contentType == null) {
            response = new BoxAPIResponse(connection);
        } else if (contentType.contains("application/json")) {
            response = new BoxJSONResponse(connection);
        } else {
            response = new BoxAPIResponse(connection);
        }

        return response;
    }

    private void logRequest(HttpURLConnection connection) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, this.toString());
        }
    }

    private HttpURLConnection createConnection() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) this.url.openConnection();
        } catch (IOException e) {
            throw new BoxAPIException("Couldn't connect to the Box API due to a network error.", e);
        }

        try {
            connection.setRequestMethod(this.method);
        } catch (ProtocolException e) {
            throw new BoxAPIException("Couldn't connect to the Box API because the request's method was invalid.", e);
        }

        connection.setConnectTimeout(this.timeout);
        connection.setReadTimeout(this.timeout);

        for (RequestHeader header : this.headers) {
            connection.addRequestProperty(header.getKey(), header.getValue());
        }

        return connection;
    }

    private static boolean isResponseRetryable(int responseCode) {
        return (responseCode >= 500 || responseCode == 429);
    }

    private final class RequestHeader {
        private final String key;
        private final String value;

        public RequestHeader(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return this.key;
        }

        public String getValue() {
            return this.value;
        }
    }
}
