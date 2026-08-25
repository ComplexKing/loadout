package dev.loadout.core.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The accounts that have signed in on this machine.
 *
 * <p>This is what makes offline play legitimate rather than a way around owning the game.
 * A launcher that will start Minecraft for any name typed at it is a licence bypass; one
 * that plays offline using an account which has previously authenticated with Microsoft
 * is doing what offline mode is actually for — playing without a connection. Loadout
 * requires the latter.
 */
public final class AccountStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;

	public AccountStore(Path loadoutRoot) {
		this.file = loadoutRoot.resolve("accounts.json");
	}

	public List<StoredAccount> all() throws IOException {
		if (!Files.isRegularFile(this.file)) {
			return List.of();
		}

		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			List<StoredAccount> accounts = GSON.fromJson(reader,
					TypeToken.getParameterized(List.class, StoredAccount.class).getType());
			return accounts == null ? List.of() : accounts;
		}
	}

	/** Whether any account has ever completed a Microsoft sign-in here. */
	public boolean hasVerifiedAccount() throws IOException {
		return all().stream().anyMatch(StoredAccount::isVerified);
	}

	public Optional<StoredAccount> byUsername(String username) throws IOException {
		return all().stream()
				.filter(account -> account.username().equalsIgnoreCase(username))
				.findFirst();
	}

	/** The account to use when none was named. */
	public Optional<StoredAccount> primary() throws IOException {
		return all().stream().filter(StoredAccount::isVerified).findFirst();
	}

	/** Adds or replaces an account, matching on username. */
	public void save(StoredAccount account) throws IOException {
		List<StoredAccount> accounts = new ArrayList<>(all());
		accounts.removeIf(existing -> existing.username().equalsIgnoreCase(account.username()));
		accounts.add(account);
		write(accounts);
	}

	public void remove(String username) throws IOException {
		List<StoredAccount> accounts = new ArrayList<>(all());
		accounts.removeIf(existing -> existing.username().equalsIgnoreCase(username));
		write(accounts);
	}

	private void write(List<StoredAccount> accounts) throws IOException {
		Files.createDirectories(this.file.getParent());

		try (Writer writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
			GSON.toJson(accounts, writer);
		}

		restrictPermissions();
	}

	/**
	 * Narrows the file to the owner alone.
	 *
	 * <p>This holds refresh tokens, which are long lived and enough to obtain a session.
	 * A platform keyring would be better and is worth doing; owner-only permissions are
	 * the floor, not the finish line.
	 */
	private void restrictPermissions() {
		try {
			Set<PosixFilePermission> ownerOnly = Set.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(this.file, ownerOnly);
		} catch (IOException | UnsupportedOperationException e) {
			// Windows has no POSIX permissions. Files there inherit the user profile's
			// ACL, which is already owner-scoped for anything under the home directory.
		}
	}
}
