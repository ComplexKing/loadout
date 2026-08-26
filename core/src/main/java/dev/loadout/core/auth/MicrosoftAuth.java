package dev.loadout.core.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Signing in to a Microsoft account and turning that into a Minecraft session.
 *
 * <p>Four services in a chain, each of which only accepts a token minted by the one
 * before: Microsoft issues an OAuth token, Xbox Live exchanges it for its own, XSTS
 * authorises that for Minecraft specifically, and Minecraft services finally issue the
 * session the game runs with. Every step has its own failure worth naming, which is why
 * they are not collapsed into one method here.
 *
 * <h2>Why the device code flow</h2>
 *
 * <p>The alternative is an authorisation code flow, which needs either a loopback
 * listener or an embedded browser. A loopback redirect means opening a port and handling
 * a callback; an embedded browser means this application renders Microsoft's password
 * form, which is exactly the thing people should be taught not to type into. The device
 * flow asks the real browser to do it: Loadout shows a short code, the sign-in happens
 * somewhere Loadout cannot see, and no password ever passes through this process.
 */
public final class MicrosoftAuth {
	/** The consumers tenant: Minecraft accounts are personal, not organisational. */
	private static final String DEVICE_CODE_URL =
			"https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
	private static final String TOKEN_URL =
			"https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

	private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MINECRAFT_LOGIN_URL =
			"https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String PROFILE_URL =
			"https://api.minecraftservices.com/minecraft/profile";

	/**
	 * The only scopes needed.
	 *
	 * <p>XboxLive.signin is what the chain below requires; offline_access is what makes a
	 * refresh token available, so signing in is a thing done once rather than every launch.
	 * Nothing else is asked for -- a launcher has no business holding permission to read
	 * anyone's mail.
	 */
	private static final String SCOPE = "XboxLive.signin offline_access";

	private final String clientId;
	private final HttpClient http;

	public MicrosoftAuth(String clientId) {
		this.clientId = clientId;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * What the person needs to do, and what to poll with.
	 *
	 * @param userCode the short code they type
	 * @param verificationUri where they type it
	 * @param deviceCode the secret half, used to poll. Not shown to anyone.
	 * @param intervalSeconds how often Microsoft permits polling
	 * @param expiresAt when the code stops working
	 */
	public record DeviceCode(
			String userCode,
			String verificationUri,
			String deviceCode,
			int intervalSeconds,
			Instant expiresAt
	) {
	}

	/**
	 * A completed sign-in.
	 *
	 * @param accessToken the Minecraft session token, short lived and never stored
	 * @param refreshToken the long-lived secret, which is what gets kept
	 */
	public record Session(
			String username,
			String uuid,
			String accessToken,
			String refreshToken
	) {
		public StoredAccount toStored() {
			return new StoredAccount(this.username, this.uuid, this.refreshToken,
					Instant.now().toString());
		}
	}

	/** Raised when a step fails in a way worth telling the user about. */
	public static final class AuthException extends IOException {
		public AuthException(String message) {
			super(message);
		}
	}

	// -- step 1: ask Microsoft for a code ---------------------------------------------

	public DeviceCode begin() throws IOException, InterruptedException {
		JsonObject response = form(DEVICE_CODE_URL, Map.of(
				"client_id", this.clientId,
				"scope", SCOPE));

		if (response.has("error")) {
			throw new AuthException(describe(response));
		}

		return new DeviceCode(
				string(response, "user_code"),
				string(response, "verification_uri"),
				string(response, "device_code"),
				Math.max(response.has("interval") ? response.get("interval").getAsInt() : 5, 1),
				Instant.now().plusSeconds(
						response.has("expires_in") ? response.get("expires_in").getAsInt() : 900));
	}

	// -- step 2: wait for them to finish ----------------------------------------------

	/**
	 * Checks whether the sign-in has completed.
	 *
	 * <p>Empty means "not yet", which is the normal answer for as long as someone is still
	 * typing. Only a genuine refusal or expiry raises.
	 */
	public Optional<Session> poll(String deviceCode) throws IOException, InterruptedException {
		JsonObject response = form(TOKEN_URL, Map.of(
				"client_id", this.clientId,
				"grant_type", "urn:ietf:params:oauth:grant-type:device_code",
				"device_code", deviceCode));

		if (response.has("error")) {
			String error = string(response, "error");
			switch (error) {
				case "authorization_pending":
					return Optional.empty();
				case "slow_down":
					// Microsoft asking for a longer gap. Waiting here is simpler than
					// threading a new interval back to the caller for one extra second.
					Thread.sleep(2000);
					return Optional.empty();
				case "expired_token":
					throw new AuthException("The sign-in code expired. Start again.");
				case "authorization_declined":
					throw new AuthException("Sign-in was declined.");
				default:
					throw new AuthException(describe(response));
			}
		}

		return Optional.of(completeWithMicrosoftToken(
				string(response, "access_token"), string(response, "refresh_token")));
	}

	// -- refreshing -------------------------------------------------------------------

	/**
	 * Turns a stored refresh token into a fresh session.
	 *
	 * <p>Run before every launch. The Minecraft access token lasts around a day, so a
	 * launcher that stored one would be handing the game an expired credential most of the
	 * time; the refresh token is what actually persists.
	 */
	public Session refresh(String refreshToken) throws IOException, InterruptedException {
		JsonObject response = form(TOKEN_URL, Map.of(
				"client_id", this.clientId,
				"grant_type", "refresh_token",
				"refresh_token", refreshToken,
				"scope", SCOPE));

		if (response.has("error")) {
			throw new AuthException("This account needs signing in again: " + describe(response));
		}

		// Microsoft may hand back a new refresh token, and the old one stops working when
		// it does. Falling back to the previous one only when none was returned is what
		// keeps a rotated token from being lost.
		String rotated = string(response, "refresh_token");
		return completeWithMicrosoftToken(string(response, "access_token"),
				rotated == null ? refreshToken : rotated);
	}

	// -- steps 3 to 6: Xbox, XSTS, Minecraft, profile ---------------------------------

	private Session completeWithMicrosoftToken(String microsoftToken, String refreshToken)
			throws IOException, InterruptedException {

		JsonObject xbl = json(XBL_URL, """
				{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",\
				"RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
				.formatted(microsoftToken), null);

		String xblToken = string(xbl, "Token");
		String userHash = userHashOf(xbl);
		if (xblToken == null || userHash == null) {
			throw new AuthException("Xbox Live did not return a token for this account.");
		}

		JsonObject xsts = json(XSTS_URL, """
				{"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},\
				"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
				.formatted(xblToken), null);

		if (xsts.has("XErr")) {
			throw new AuthException(explainXsts(xsts.get("XErr").getAsLong()));
		}

		String xstsToken = string(xsts, "Token");
		if (xstsToken == null) {
			throw new AuthException("Xbox refused to authorise this account for Minecraft.");
		}

		JsonObject minecraft = json(MINECRAFT_LOGIN_URL,
				"{\"identityToken\":\"XBL3.0 x=%s;%s\"}".formatted(userHash, xstsToken), null);

		String accessToken = string(minecraft, "access_token");
		if (accessToken == null) {
			throw new AuthException("Minecraft services did not return a session.");
		}

		JsonObject profile = get(PROFILE_URL, accessToken);
		String uuid = string(profile, "id");
		String name = string(profile, "name");

		if (uuid == null || name == null) {
			// The chain succeeded but there is no profile, which means this Microsoft
			// account does not own the game. Worth saying plainly: it is the one failure
			// here that is about the account rather than about the sign-in.
			throw new AuthException(
					"That account signed in, but it does not own Minecraft: Java Edition.");
		}

		return new Session(name, dashed(uuid), accessToken, refreshToken);
	}

	/**
	 * XSTS refusals, in the terms a person can act on.
	 *
	 * <p>The raw code is a bare number in a JSON body, and every launcher that shows it
	 * unexplained generates the same round of confused searching.
	 */
	private static String explainXsts(long code) {
		// Chained rather than switched: these codes are above Integer.MAX_VALUE, and Java
		// cannot switch on a long. Narrowing them to int to fit a switch is exactly the
		// kind of quiet truncation that makes two different errors look like one.
		if (code == 2148916233L) {
			return "This Microsoft account has no Xbox profile. Sign in once at minecraft.net "
					+ "to create one, then try again.";
		}
		if (code == 2148916235L) {
			return "Xbox Live is not available in this account's country.";
		}
		if (code == 2148916236L || code == 2148916237L) {
			return "This account needs adult verification before it can sign in.";
		}
		if (code == 2148916238L) {
			return "This is a child account. An adult has to add it to a Microsoft family "
					+ "before it can sign in.";
		}
		return "Xbox refused to authorise this account (code " + code + ").";
	}

	/** Minecraft returns a UUID without dashes; the game and its APIs expect them. */
	private static String dashed(String raw) {
		if (raw == null || raw.length() != 32) {
			return raw;
		}
		return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
				+ "-" + raw.substring(16, 20) + "-" + raw.substring(20);
	}

	private static String userHashOf(JsonObject xbl) {
		JsonElement claims = xbl.get("DisplayClaims");
		if (claims == null || !claims.isJsonObject()) {
			return null;
		}
		JsonArray xui = claims.getAsJsonObject().getAsJsonArray("xui");
		return xui == null || xui.isEmpty() ? null : string(xui.get(0).getAsJsonObject(), "uhs");
	}

	// -- transport ---------------------------------------------------------------------

	private JsonObject form(String url, Map<String, String> fields)
			throws IOException, InterruptedException {
		StringBuilder body = new StringBuilder();
		Map<String, String> ordered = new LinkedHashMap<>(fields);

		for (Map.Entry<String, String> field : ordered.entrySet()) {
			if (!body.isEmpty()) {
				body.append('&');
			}
			body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
		}

		return send(HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
	}

	private JsonObject json(String url, String body, String bearer)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return send(request);
	}

	private JsonObject get(String url, String bearer) throws IOException, InterruptedException {
		return send(HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + bearer)
				.timeout(Duration.ofSeconds(30))
				.GET());
	}

	/**
	 * Sends a request and parses the body, whatever the status.
	 *
	 * <p>Every service in this chain reports its failures in the body rather than only in
	 * the status, and those bodies carry the part worth showing. Throwing on the status
	 * alone would discard the only useful half of the answer.
	 */
	private JsonObject send(HttpRequest.Builder request) throws IOException, InterruptedException {
		HttpResponse<String> response =
				this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());

		String body = response.body();
		if (body == null || body.isBlank()) {
			if (response.statusCode() / 100 != 2) {
				throw new AuthException("The sign-in service returned "
						+ response.statusCode() + " with no explanation.");
			}
			return new JsonObject();
		}

		try {
			JsonElement parsed = JsonParser.parseString(body);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			throw new AuthException("The sign-in service returned something unreadable.");
		}
	}

	private static String describe(JsonObject response) {
		String description = string(response, "error_description");

		// One error is worth translating, because it is a configuration mistake with an
		// exact fix and Microsoft's own wording sends people looking for the wrong thing:
		// the setting is not called "mobile" anywhere in the portal.
		if (description != null && description.contains("AADSTS70002")) {
			return "This Azure application is not set up for device sign-in. In the Azure "
					+ "portal, open the app registration, go to Authentication, and turn on "
					+ "\"Allow public client flows\".";
		}

		if (description != null && !description.isBlank()) {
			// Microsoft appends a correlation id and timestamp on its own lines, which are
			// for their support rather than for whoever is looking at this window.
			int newline = description.indexOf('\n');
			return newline > 0 ? description.substring(0, newline).trim() : description;
		}
		String error = string(response, "error");
		return error == null ? "Sign-in failed." : error;
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
