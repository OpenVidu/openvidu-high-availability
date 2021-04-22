package io.openvidu.multi.master.sample.app;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import io.openvidu.java.client.OpenVidu;
import io.openvidu.java.client.OpenViduHttpException;
import io.openvidu.java.client.OpenViduJavaClientException;
import io.openvidu.java.client.Session;
import io.openvidu.java.client.SessionProperties;

@RestController
@RequestMapping("/api")
public class MyRestController {

	private static final Logger log = LoggerFactory.getLogger(MyRestController.class);

	// URL where our OpenVidu server is listening
	private String OPENVIDU_URL;
	// Secret shared with our OpenVidu server
	private String SECRET;
	// Custom http client to consume REST API not supported by openvidu-java-client
	private HttpClient HTTP_CLIENT;

	// OpenVidu object as entrypoint of the SDK
	private OpenVidu openVidu;

	// Collection of Sessions
	private Map<String, Session> sessions = new ConcurrentHashMap<>();
	private Set<String> crashedSessions = new HashSet<>();

	public MyRestController(@Value("${openvidu.secret}") String secret, @Value("${openvidu.url}") String openviduUrl) {
		this.SECRET = secret;
		this.OPENVIDU_URL = openviduUrl;
		this.openVidu = new OpenVidu(OPENVIDU_URL, SECRET);
		this.initInsecureHttpClient();
		log.info("Connecting to OpenVidu Pro Multi Master cluster at {}", OPENVIDU_URL);
	}

	/**
	 * This method creates a Connection for an existing or new Session, and returns
	 * the Connection's token to the client side
	 */
	@RequestMapping(value = "/get-token", method = RequestMethod.POST)
	public ResponseEntity<?> getToken(@RequestBody Map<String, Object> params) {

		log.info("Getting sessionId and token | {sessionId}={}", params);

		// The Session to connect
		String sessionId = (String) params.get("sessionId");

		if (this.crashedSessions.contains(sessionId)) {

			// Session is flagged as crashed and is being rebuilt
			log.warn("A user asked for a token, but Session {} has crashed and is being rebuilt", sessionId);
			return new ResponseEntity<>(HttpStatus.CONFLICT);

		} else if (this.sessions.containsKey(sessionId)) {

			// Session already exists
			log.info("Existing session {}", sessionId);
			return returnToken(this.sessions.get(sessionId));

		} else {

			// Session does not exist yet
			log.info("New session {}", sessionId);
			return createSessionAndReturnToken(sessionId);

		}
	}

	/**
	 * This method allows users to reconnect to a Session that was closed after a
	 * node crashed
	 */
	@RequestMapping(value = "/reconnect", method = RequestMethod.POST)
	public ResponseEntity<?> reconnectAfterCrash(@RequestBody Map<String, Object> params) {

		String sessionId = (String) params.get("sessionId");

		if (this.crashedSessions.contains(sessionId)) {
			log.warn("A user wants to reconnect to a crashed Session, but Session {} is still being rebuilt",
					sessionId);
			return new ResponseEntity<>(HttpStatus.CONFLICT);
		}

		if (this.sessions.containsKey(sessionId)) {
			log.info("A user is reconnecting to Session {}", sessionId);
			return returnToken(this.sessions.get(sessionId));
		}

		log.error("A user is asking to reconnect to Sesion {} but it does not exist", sessionId);
		return new ResponseEntity<>(HttpStatus.NOT_FOUND);
	}

	/**
	 * This method returns the Media Nodes information of the cluster
	 */
	@RequestMapping(value = "/cluster-status", method = RequestMethod.GET)
	public ResponseEntity<?> getClusterStatus() throws Exception {

		log.info("Getting cluster status");

		HttpGet request = new HttpGet(this.OPENVIDU_URL + "openvidu/api/media-nodes");
		HttpResponse response;
		try {
			response = HTTP_CLIENT.execute(request);
		} catch (IOException e) {
			return getErrorResponse(e);
		}

		return new ResponseEntity<>(httpResponseToJson(response), HttpStatus.OK);
	}

	/**
	 * This is the webhook handler. OpenVidu webhook events are received in this
	 * method
	 */
	@RequestMapping(value = "/webhook", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<JsonObject> webhook(@RequestBody Map<String, Object> event) throws Exception {

		log.info("Webhook event received: {}", event.get("event"));

		String eventName = (String) event.get("event");

		// Handle the necessary webhook events. Do not block the return of 200 OK to
		// OpenVidu Server
		switch (eventName) {
		case "nodeCrashed":
			new Thread(() -> {
				handleNodeCrashedEvent(event);
			}).start();
			break;
		case "sessionDestroyed":
			new Thread(() -> {
				handleSessionDestroyedEvent(event);
			}).start();
			break;
		}

		return new ResponseEntity<>(HttpStatus.OK);
	}

	private void handleNodeCrashedEvent(Map<String, Object> event) {

		log.info("Media Node {} has crashed", event.get("id"));
		log.info("Affected users will ask to rejoin a new Session");

		List<String> affectedSessions = (List<String>) event.get("sessionIds");
		affectedSessions.forEach(sessionId -> {
			// Remove the Session from our collection of active Sessions
			sessions.remove(sessionId);
			// Store the Session as crashed and waiting to be rebuilt
			crashedSessions.add(sessionId);
		});
	}

	private void handleSessionDestroyedEvent(Map<String, Object> event) {

		if ("nodeCrashed".equals(event.get("reason"))) {

			String sessionId = (String) event.get("sessionId");

			// The new Session has the same customSessionId as the previous one
			SessionProperties props = new SessionProperties.Builder().customSessionId(sessionId).build();

			// Create and store the new Session
			try {
				Session newSession = openVidu.createSession(props);
				sessions.put(sessionId, newSession);
			} catch (OpenViduJavaClientException | OpenViduHttpException e) {
				log.error("Error creating new Session after nodeCrashed: {}", e.getMessage());
				return;
			}

			// Remove the Session from the collection of crashed Sessions
			this.crashedSessions.remove(sessionId);
		}
	}

	private ResponseEntity<?> createSessionAndReturnToken(String sessionId) {
		try {

			// Create a new OpenVidu Session
			SessionProperties props = new SessionProperties.Builder().customSessionId(sessionId).build();
			Session session = this.openVidu.createSession(props);

			// Store the Session in our collection
			this.sessions.put(sessionId, session);

			// Return a token
			return this.returnToken(session);

		} catch (Exception e) {
			// If error generate an error message and return it to the client
			return getErrorResponse(e);
		}
	}

	private ResponseEntity<?> returnToken(Session session) {
		try {

			String token = session.createConnection().getToken();

			// Send the response with the token
			JsonObject responseJson = new JsonObject();
			responseJson.addProperty("token", token);
			return new ResponseEntity<>(responseJson, HttpStatus.OK);

		} catch (OpenViduJavaClientException e1) {
			// If internal error generate an error message and return it to client
			return getErrorResponse(e1);
		} catch (OpenViduHttpException e2) {
			if (404 == e2.getStatus()) {
				// Invalid sessionId (user left unexpectedly). Session object is not valid
				// anymore. Clean collection and return error. A new Session should be created
				this.sessions.remove(session.getSessionId());
			}
			return getErrorResponse(e2);
		}
	}

	private ResponseEntity<JsonObject> getErrorResponse(Exception e) {
		JsonObject json = new JsonObject();
		if (e.getCause() != null) {
			json.addProperty("cause", e.getCause().toString());
		}
		if (e.getStackTrace() != null) {
			json.addProperty("stacktrace", e.getStackTrace().toString());
		}
		json.addProperty("error", e.getMessage());
		json.addProperty("exception", e.getClass().getCanonicalName());
		return new ResponseEntity<>(json, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	private JsonObject httpResponseToJson(HttpResponse response) throws Exception {
		try {
			JsonObject json = new Gson().fromJson(EntityUtils.toString(response.getEntity(), "UTF-8"),
					JsonObject.class);
			return json;
		} catch (JsonSyntaxException | ParseException | IOException e) {
			throw new Exception(e.getMessage(), e.getCause());
		}
	}

	/**
	 * This Http client is initialized to accept insecure SSL certificates. It is
	 * used only in method {@link MyRestController#getClusterStatus()}
	 */
	private void initInsecureHttpClient() {
		TrustStrategy trustStrategy = new TrustStrategy() {
			@Override
			public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				return true;
			}
		};

		CredentialsProvider provider = new BasicCredentialsProvider();
		UsernamePasswordCredentials credentials = new UsernamePasswordCredentials("OPENVIDUAPP", this.SECRET);
		provider.setCredentials(AuthScope.ANY, credentials);

		SSLContext sslContext;

		try {
			sslContext = new SSLContextBuilder().loadTrustMaterial(null, trustStrategy).build();
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
			throw new RuntimeException(e);
		}

		RequestConfig.Builder requestBuilder = RequestConfig.custom();
		requestBuilder = requestBuilder.setConnectTimeout(30000);
		requestBuilder = requestBuilder.setConnectionRequestTimeout(30000);

		HTTP_CLIENT = HttpClientBuilder.create().setDefaultRequestConfig(requestBuilder.build())
				.setConnectionTimeToLive(30, TimeUnit.SECONDS).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
				.setSSLContext(sslContext).setDefaultCredentialsProvider(provider).build();
	}

}
