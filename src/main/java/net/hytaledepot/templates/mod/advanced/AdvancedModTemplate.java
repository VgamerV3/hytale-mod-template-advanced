package net.hytaledepot.templates.mod.advanced;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedModTemplate {
  // Runtime queue limits and abuse-control thresholds.
  private static final int MAX_EVENT_QUEUE_SIZE = 256;
  private static final int MAX_FAILURES_BEFORE_LOCK = 5;
  private static final long BASE_LOCK_SECONDS = 60;
  private static final long NONCE_WINDOW_SECONDS = 90;
  private static final String ENVELOPE_SECRET = "hd-mod-advanced-secret";

  // Player-scoped state.
  private final Map<String, PlayerProfile> profiles = new ConcurrentHashMap<>();
  // Source-scoped security windows.
  private final Map<String, SecurityWindow> securityBySource = new ConcurrentHashMap<>();
  // Feature flags toggled at runtime.
  private final Map<String, String> featureFlags = new ConcurrentHashMap<>();
  // Replay guard for signed network envelopes.
  private final Map<String, Long> recentNonceExpiryByNonce = new ConcurrentHashMap<>();

  private final Deque<String> eventQueue = new ArrayDeque<>();
  private final List<String> auditTrail = new ArrayList<>();

  private final AtomicLong totalEnqueuedEvents = new AtomicLong();
  private final AtomicLong totalDroppedEvents = new AtomicLong();
  private final AtomicLong totalTransfers = new AtomicLong();

  public void onInitialize() {
    // Keep defaults explicit so deployment behavior is predictable.
    featureFlags.put("economy.enabled", "true");
    featureFlags.put("network.strict_signatures", "true");
    featureFlags.put("events.keep_audit", "true");

    appendAudit("mod.initialize", "initialized_at=" + Instant.now().getEpochSecond());
  }

  public void onShutdown() {
    appendAudit("mod.shutdown", "profiles=" + profiles.size() + ", queued_events=" + eventQueue.size());

    synchronized (eventQueue) {
      eventQueue.clear();
    }
    profiles.clear();
    securityBySource.clear();
    recentNonceExpiryByNonce.clear();
  }

  public void setFeatureFlag(String key, String value) {
    String safeKey = String.valueOf(key).trim().toLowerCase();
    String safeValue = String.valueOf(value).trim().toLowerCase();
    if (safeKey.isEmpty()) {
      return;
    }

    featureFlags.put(safeKey, safeValue);
    appendAudit("feature.set", safeKey + "=" + safeValue);
  }

  public boolean isFeatureEnabled(String key, boolean fallback) {
    String safeKey = String.valueOf(key).trim().toLowerCase();
    String configured = featureFlags.get(safeKey);
    if (configured == null) {
      return fallback;
    }
    return "true".equals(configured) || "1".equals(configured) || "yes".equals(configured) || "on".equals(configured);
  }

  public void ensureProfile(String playerId) {
    String id = normalizeId(playerId);
    profiles.computeIfAbsent(id, ignored -> new PlayerProfile());
  }

  public long getCoins(String playerId) {
    String id = normalizeId(playerId);
    return profiles.computeIfAbsent(id, ignored -> new PlayerProfile()).coins.get();
  }

  public boolean transferCoins(String fromPlayer, String toPlayer, long amount, String reason) {
    String from = normalizeId(fromPlayer);
    String to = normalizeId(toPlayer);

    if (from.equals(to) || amount <= 0) {
      return false;
    }

    PlayerProfile fromProfile = profiles.computeIfAbsent(from, ignored -> new PlayerProfile());
    PlayerProfile toProfile = profiles.computeIfAbsent(to, ignored -> new PlayerProfile());

    synchronized (fromProfile) {
      long fromBalance = fromProfile.coins.get();
      if (fromBalance < amount) {
        appendAudit("economy.transfer_denied", from + "->" + to + " amount=" + amount + " reason=insufficient");
        return false;
      }
      fromProfile.coins.addAndGet(-amount);
    }

    toProfile.coins.addAndGet(amount);
    totalTransfers.incrementAndGet();

    appendAudit(
        "economy.transfer",
        from
            + "->"
            + to
            + " amount="
            + amount
            + " reason="
            + String.valueOf(reason));
    return true;
  }

  public void grantPermission(String playerId, String permission) {
    String id = normalizeId(playerId);
    String perm = normalizePermission(permission);

    PlayerProfile profile = profiles.computeIfAbsent(id, ignored -> new PlayerProfile());
    synchronized (profile.permissions) {
      profile.permissions.add(perm);
    }

    appendAudit("permissions.grant", id + " " + perm);
  }

  public void revokePermission(String playerId, String permission) {
    String id = normalizeId(playerId);
    String perm = normalizePermission(permission);

    PlayerProfile profile = profiles.computeIfAbsent(id, ignored -> new PlayerProfile());
    synchronized (profile.permissions) {
      profile.permissions.remove(perm);
    }

    appendAudit("permissions.revoke", id + " " + perm);
  }

  public boolean hasPermission(String playerId, String permission) {
    String id = normalizeId(playerId);
    String perm = normalizePermission(permission);

    PlayerProfile profile = profiles.computeIfAbsent(id, ignored -> new PlayerProfile());
    synchronized (profile.permissions) {
      return profile.permissions.contains(perm);
    }
  }

  public void recordFailedSecurityAttempt(String source, long nowEpochSeconds) {
    String key = normalizeId(source);
    SecurityWindow window = securityBySource.computeIfAbsent(key, ignored -> new SecurityWindow());

    long failures = window.failures.incrementAndGet();
    if (failures >= MAX_FAILURES_BEFORE_LOCK) {
      long lockSeconds = BASE_LOCK_SECONDS * Math.max(1, failures - MAX_FAILURES_BEFORE_LOCK + 1);
      window.lockedUntilEpochSeconds.set(nowEpochSeconds + lockSeconds);
      appendAudit("security.lock", key + " failures=" + failures + " lock_seconds=" + lockSeconds);
    }
  }

  public void recordSuccessfulSecurityAttempt(String source) {
    String key = normalizeId(source);
    SecurityWindow removed = securityBySource.remove(key);
    if (removed != null) {
      appendAudit("security.reset", key);
    }
  }

  public boolean canSourceProceed(String source, long nowEpochSeconds) {
    String key = normalizeId(source);
    SecurityWindow window = securityBySource.get(key);
    if (window == null) {
      return true;
    }

    long lockedUntil = window.lockedUntilEpochSeconds.get();
    if (lockedUntil <= nowEpochSeconds) {
      if (lockedUntil > 0) {
        securityBySource.remove(key);
      }
      return true;
    }
    return false;
  }

  public void enqueueEvent(String topic, String payload, String source, long nowEpochSeconds) {
    String line =
        String.valueOf(nowEpochSeconds)
            + "|"
            + normalizeTopic(topic)
            + "|"
            + normalizeId(source)
            + "|"
            + normalizePayload(payload);

    synchronized (eventQueue) {
      if (eventQueue.size() >= MAX_EVENT_QUEUE_SIZE) {
        eventQueue.pollFirst();
        totalDroppedEvents.incrementAndGet();
      }
      eventQueue.addLast(line);
      totalEnqueuedEvents.incrementAndGet();
    }

    appendAudit("events.enqueue", "topic=" + normalizeTopic(topic));
  }

  public List<String> drainEvents(int maxItems) {
    int limit = Math.max(1, maxItems);
    List<String> drained = new ArrayList<>(limit);

    synchronized (eventQueue) {
      while (drained.size() < limit && !eventQueue.isEmpty()) {
        drained.add(eventQueue.pollFirst());
      }
    }

    return drained;
  }

  public String buildNetworkEnvelope(String topic, String payload, String nonce, long nowEpochSeconds) {
    String safeTopic = normalizeTopic(topic);
    String safePayload = normalizePayload(payload);
    String safeNonce = String.valueOf(nonce).trim();
    String timestamp = String.valueOf(nowEpochSeconds);

    String signature = signEnvelope(safeTopic, safePayload, safeNonce, timestamp);
    return "topic="
        + safeTopic
        + ";payload="
        + safePayload
        + ";nonce="
        + safeNonce
        + ";ts="
        + timestamp
        + ";sig="
        + signature;
  }

  public boolean validateNetworkEnvelope(String envelope, long nowEpochSeconds, long maxClockSkewSeconds) {
    // Envelope fields are parsed from the compact key=value format.
    Map<String, String> fields = parseEnvelope(envelope);

    String topic = fields.getOrDefault("topic", "");
    String payload = fields.getOrDefault("payload", "");
    String nonce = fields.getOrDefault("nonce", "");
    String tsValue = fields.getOrDefault("ts", "0");
    String sig = fields.getOrDefault("sig", "");

    long ts = parseLong(tsValue, 0L);
    if (ts <= 0) {
      return false;
    }

    if (Math.abs(nowEpochSeconds - ts) > Math.max(1, maxClockSkewSeconds)) {
      return false;
    }

    // Reject envelope replay inside the nonce window.
    sweepExpiredNonces(nowEpochSeconds);
    if (nonce.isEmpty()) {
      return false;
    }
    Long existingExpiry = recentNonceExpiryByNonce.get(nonce);
    if (existingExpiry != null && existingExpiry >= nowEpochSeconds) {
      appendAudit("network.replay_block", "nonce=" + nonce + " topic=" + topic);
      return false;
    }

    String expected = signEnvelope(topic, payload, nonce, String.valueOf(ts));
    boolean valid = expected.equals(sig);
    if (valid) {
      recentNonceExpiryByNonce.put(nonce, nowEpochSeconds + NONCE_WINDOW_SECONDS);
      appendAudit("network.accept", "nonce=" + nonce + " topic=" + topic);
    } else {
      appendAudit("network.signature_mismatch", "nonce=" + nonce + " topic=" + topic);
    }
    return valid;
  }

  public String describeStatus() {
    int queued;
    synchronized (eventQueue) {
      queued = eventQueue.size();
    }

    return "AdvancedMod[profiles="
        + profiles.size()
        + ", queue="
        + queued
        + ", enqueued="
        + totalEnqueuedEvents.get()
        + ", dropped="
        + totalDroppedEvents.get()
        + ", transfers="
        + totalTransfers.get()
        + ", security_sources="
        + securityBySource.size()
        + "]";
  }

  public List<String> getRecentAuditEntries(int limit) {
    int max = Math.max(1, limit);
    synchronized (auditTrail) {
      int start = Math.max(0, auditTrail.size() - max);
      return new ArrayList<>(auditTrail.subList(start, auditTrail.size()));
    }
  }

  private void appendAudit(String action, String details) {
    String line = Instant.now().getEpochSecond() + " " + action + " " + String.valueOf(details);
    synchronized (auditTrail) {
      auditTrail.add(line);
      if (auditTrail.size() > 500) {
        auditTrail.remove(0);
      }
    }
  }

  private static String signEnvelope(String topic, String payload, String nonce, String timestamp) {
    String base = topic + "|" + payload + "|" + nonce + "|" + timestamp + "|" + ENVELOPE_SECRET;
    return sha256(base).substring(0, 24);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(out.length * 2);
      for (byte b : out) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      return Integer.toHexString(String.valueOf(value).hashCode());
    }
  }

  private static Map<String, String> parseEnvelope(String envelope) {
    Map<String, String> out = new HashMap<>();
    String raw = String.valueOf(envelope);
    String[] parts = raw.split(";");
    for (String part : parts) {
      int index = part.indexOf('=');
      if (index <= 0) {
        continue;
      }
      String key = part.substring(0, index).trim();
      String value = part.substring(index + 1).trim();
      out.put(key, value);
    }
    return out;
  }

  private void sweepExpiredNonces(long nowEpochSeconds) {
    recentNonceExpiryByNonce.entrySet().removeIf(entry -> entry.getValue() < nowEpochSeconds);
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String normalizeId(String value) {
    String id = String.valueOf(value).trim();
    if (id.isEmpty()) {
      return "unknown";
    }
    return id.toLowerCase();
  }

  private static String normalizePermission(String value) {
    return String.valueOf(value).trim().toLowerCase().replace(' ', '.');
  }

  private static String normalizeTopic(String value) {
    String topic = String.valueOf(value).trim().toLowerCase();
    if (topic.isEmpty()) {
      return "generic";
    }
    return topic;
  }

  private static String normalizePayload(String value) {
    String payload = String.valueOf(value);
    if (payload.length() > 4096) {
      return payload.substring(0, 4096);
    }
    return payload;
  }

  private static final class PlayerProfile {
    private final AtomicLong coins = new AtomicLong();
    private final Set<String> permissions = new HashSet<>();
  }

  private static final class SecurityWindow {
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lockedUntilEpochSeconds = new AtomicLong(0);
  }
}
