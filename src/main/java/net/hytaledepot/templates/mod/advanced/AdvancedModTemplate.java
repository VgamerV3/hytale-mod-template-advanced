package net.hytaledepot.templates.mod.advanced;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AdvancedModTemplate {
  private static final int ACTION_WINDOW_MAX = 10;
  private static final long ACTION_WINDOW_SECONDS = 6;
  private static final int EVENT_QUEUE_MAX = 320;
  private static final long NONCE_WINDOW_SECONDS = 120;
  private static final long BASE_LOCK_SECONDS = 45;
  private static final int AUDIT_LIMIT = 260;
  private static final String ENVELOPE_SECRET = "hd-mod-advanced-secret";

  private final Map<String, PlayerProfile> profiles = new ConcurrentHashMap<>();
  private final Map<String, Deque<Long>> actionWindows = new ConcurrentHashMap<>();
  private final Deque<EventPacket> eventQueue = new ArrayDeque<>();
  private final Map<String, Long> usedNonceExpiresAt = new ConcurrentHashMap<>();
  private final Map<String, Long> sourceLocks = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> securityFailuresBySource = new ConcurrentHashMap<>();
  private final Map<String, String> lastEnvelopeBySender = new ConcurrentHashMap<>();
  private final Map<String, String> lastActionBySender = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private final Deque<String> auditTrail = new ArrayDeque<>();

  private volatile Path snapshotPath;

  public void onInitialize(Path dataDirectory) {
    snapshotPath = dataDirectory.resolve("advanced-mod-state.properties");

    ensureCounter("heartbeat");
    ensureCounter("actionsBlocked");
    ensureCounter("eventsQueued");
    ensureCounter("eventsProcessed");
    ensureCounter("eventsDropped");
    ensureCounter("transfers");
    ensureCounter("envelopesSigned");
    ensureCounter("envelopesValidated");
    ensureCounter("envelopesRejected");
    ensureCounter("locksApplied");
    ensureCounter("xpAwards");
    ensureCounter("levelUps");
    ensureCounter("snapshotWrites");

    restoreSnapshot();
    appendAudit("mod.initialize", "snapshotPath=" + snapshotPath);
  }

  public void onHeartbeat(long tick) {
    incrementCounter("heartbeat");
    if (tick % 120 == 0) {
      grantPassiveIncome();
    }
  }

  public void maintenanceSweep() {
    long now = Instant.now().getEpochSecond();

    actionWindows.entrySet().removeIf(entry -> {
      Deque<Long> window = entry.getValue();
      synchronized (window) {
        while (!window.isEmpty() && now - window.peekFirst() > ACTION_WINDOW_SECONDS) {
          window.pollFirst();
        }
        return window.isEmpty();
      }
    });

    usedNonceExpiresAt.entrySet().removeIf(entry -> entry.getValue() < now);
    sourceLocks.entrySet().removeIf(entry -> entry.getValue() < now);
    securityFailuresBySource.entrySet().removeIf(entry -> entry.getValue().get() <= 0);
  }

  public String applyAction(String sender, String action, String[] args, long heartbeatTicks) {
    long now = Instant.now().getEpochSecond();
    String normalizedSender = normalizeId(sender);
    String normalizedAction = normalizeAction(action);

    if (!canSourceProceed(normalizedSender, now) && !isLockControlAction(normalizedAction)) {
      return "[AdvancedMod] sender locked until " + sourceLocks.get(normalizedSender);
    }
    if (!isReadOnlyAction(normalizedAction) && !consumeActionQuota(normalizedSender, now)) {
      incrementCounter("actionsBlocked");
      return "[AdvancedMod] action quota exceeded for sender=" + normalizedSender;
    }

    lastActionBySender.put(normalizedSender, normalizedAction);
    ensureProfile(normalizedSender);

    switch (normalizedAction) {
      case "info":
      case "status":
        return "[AdvancedMod] " + diagnostics(normalizedSender, heartbeatTicks);
      case "grant-starter":
        return "[AdvancedMod] " + grantStarterPack(normalizedSender);
      case "add-xp":
        long xp = parseLong(args.length >= 1 ? args[0] : "75", 75L);
        return "[AdvancedMod] " + awardExperience(normalizedSender, Math.max(1L, xp));
      case "transfer":
        String target = args.length >= 1 ? normalizeId(args[0]) : "market";
        long amount = parseLong(args.length >= 2 ? args[1] : "25", 25L);
        return "[AdvancedMod] " + transferCoins(normalizedSender, target, Math.max(1L, amount));
      case "queue-event":
        return "[AdvancedMod] " + queueEvent(normalizedSender, args, now);
      case "process-event":
        return "[AdvancedMod] " + processSingleEvent(now);
      case "sign-envelope":
        return "[AdvancedMod] " + signEnvelopeForSender(normalizedSender, args, now);
      case "verify-envelope":
        return "[AdvancedMod] " + verifyLastEnvelope(normalizedSender, now, 90);
      case "lock-source":
        String sourceToLock = args.length >= 1 ? normalizeId(args[0]) : normalizedSender;
        return "[AdvancedMod] " + applyLock(sourceToLock, now, "manual");
      case "unlock-source":
        String sourceToUnlock = args.length >= 1 ? normalizeId(args[0]) : normalizedSender;
        return "[AdvancedMod] " + clearLock(sourceToUnlock);
      case "flush":
      case "flush-snapshot":
        flushSnapshot();
        return "[AdvancedMod] snapshot flushed.";
      case "audit":
        return "[AdvancedMod] latestAudit=" + latestAuditEntry();
      default:
        return "[AdvancedMod] unknown action='"
            + normalizedAction
            + "' (try: info, grant-starter, add-xp, transfer, queue-event, process-event, "
            + "sign-envelope, verify-envelope, lock-source, unlock-source, flush, audit)";
    }
  }

  public String diagnostics(String sender, long heartbeatTicks) {
    PlayerProfile profile = profiles.computeIfAbsent(normalizeId(sender), ignored -> new PlayerProfile());
    int queueSize;
    synchronized (eventQueue) {
      queueSize = eventQueue.size();
    }
    return "sender="
        + normalizeId(sender)
        + ", heartbeatTicks="
        + heartbeatTicks
        + ", coins="
        + profile.coins.get()
        + ", level="
        + profile.level.get()
        + ", xp="
        + profile.xp.get()
        + ", queue="
        + queueSize
        + ", profiles="
        + profiles.size()
        + ", activeLocks="
        + sourceLocks.size()
        + ", envelopesSigned="
        + counterValue("envelopesSigned")
        + ", envelopesValidated="
        + counterValue("envelopesValidated")
        + ", envelopesRejected="
        + counterValue("envelopesRejected")
        + ", actionsBlocked="
        + counterValue("actionsBlocked");
  }

  public String describeLastAction(String sender) {
    return lastActionBySender.getOrDefault(normalizeId(sender), "none");
  }

  public String latestAuditEntry() {
    synchronized (auditTrail) {
      return auditTrail.peekLast() == null ? "none" : auditTrail.peekLast();
    }
  }

  public void flushSnapshot() {
    if (snapshotPath == null) {
      return;
    }

    try {
      Files.createDirectories(snapshotPath.getParent());

      Properties props = new Properties();
      for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
        props.setProperty("counter." + entry.getKey(), String.valueOf(entry.getValue().get()));
      }
      props.setProperty("runtime.profiles", String.valueOf(profiles.size()));
      props.setProperty("runtime.locks", String.valueOf(sourceLocks.size()));
      props.setProperty("runtime.nonces", String.valueOf(usedNonceExpiresAt.size()));
      props.setProperty("runtime.updatedAt", String.valueOf(Instant.now().getEpochSecond()));

      try (OutputStream out =
          Files.newOutputStream(
              snapshotPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        props.store(out, "Advanced mod snapshot");
      }
      incrementCounter("snapshotWrites");
      appendAudit("snapshot.write", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.write", "ok=false reason=" + exception.getMessage());
    }
  }

  public void onShutdown() {
    flushSnapshot();
    profiles.clear();
    actionWindows.clear();
    synchronized (eventQueue) {
      eventQueue.clear();
    }
    usedNonceExpiresAt.clear();
    sourceLocks.clear();
    securityFailuresBySource.clear();
    lastEnvelopeBySender.clear();
    lastActionBySender.clear();
  }

  private void restoreSnapshot() {
    if (snapshotPath == null || !Files.exists(snapshotPath)) {
      return;
    }

    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(snapshotPath)) {
      props.load(in);
      for (String key : props.stringPropertyNames()) {
        if (!key.startsWith("counter.")) {
          continue;
        }
        String counterKey = key.substring("counter.".length());
        ensureCounter(counterKey).set(parseLong(props.getProperty(key), 0L));
      }
      appendAudit("snapshot.restore", "ok=true");
    } catch (IOException exception) {
      appendAudit("snapshot.restore", "ok=false reason=" + exception.getMessage());
    }
  }

  private void ensureProfile(String sender) {
    profiles.computeIfAbsent(normalizeId(sender), ignored -> new PlayerProfile());
  }

  private String grantStarterPack(String sender) {
    PlayerProfile profile = profiles.computeIfAbsent(sender, ignored -> new PlayerProfile());
    profile.coins.addAndGet(500);
    profile.permissions.add("kit.starter");
    String xpSummary = awardExperience(sender, 120);
    appendAudit("profile.starter_pack", "sender=" + sender);
    return "starter pack granted; " + xpSummary + ", coins=" + profile.coins.get();
  }

  private String awardExperience(String sender, long amount) {
    PlayerProfile profile = profiles.computeIfAbsent(sender, ignored -> new PlayerProfile());
    profile.xp.addAndGet(amount);
    incrementCounter("xpAwards");

    long levelUps = 0;
    while (profile.xp.get() >= requiredXp(profile.level.get())) {
      long required = requiredXp(profile.level.get());
      profile.xp.addAndGet(-required);
      profile.level.incrementAndGet();
      levelUps++;
    }
    if (levelUps > 0) {
      ensureCounter("levelUps").addAndGet(levelUps);
    }

    appendAudit("profile.xp", "sender=" + sender + " amount=" + amount + " levelUps=" + levelUps);
    return "xp added="
        + amount
        + ", level="
        + profile.level.get()
        + ", xpBuffer="
        + profile.xp.get();
  }

  private String transferCoins(String from, String to, long amount) {
    if (from.equals(to)) {
      return "transfer blocked: source and target must differ";
    }

    PlayerProfile fromProfile = profiles.computeIfAbsent(from, ignored -> new PlayerProfile());
    PlayerProfile toProfile = profiles.computeIfAbsent(to, ignored -> new PlayerProfile());

    synchronized (fromProfile) {
      if (fromProfile.coins.get() < amount) {
        appendAudit("economy.transfer_denied", from + "->" + to + " amount=" + amount);
        return "transfer denied: insufficient funds";
      }
      fromProfile.coins.addAndGet(-amount);
    }
    toProfile.coins.addAndGet(amount);
    incrementCounter("transfers");
    appendAudit("economy.transfer", from + "->" + to + " amount=" + amount);
    return "transfer ok: " + from + "->" + to + " amount=" + amount;
  }

  private String queueEvent(String sender, String[] args, long nowEpochSeconds) {
    String topic = args.length >= 1 ? normalizeAction(args[0]) : "economy.credit";
    String payload = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : sender + ":15";

    String id = "evt-" + UUID.randomUUID().toString().substring(0, 8);
    EventPacket packet = new EventPacket(id, topic, payload, sender, nowEpochSeconds);

    synchronized (eventQueue) {
      if (eventQueue.size() >= EVENT_QUEUE_MAX) {
        eventQueue.pollFirst();
        incrementCounter("eventsDropped");
      }
      eventQueue.addLast(packet);
    }
    incrementCounter("eventsQueued");
    appendAudit("events.queue", id + " topic=" + topic + " sender=" + sender);
    return "queued " + id + " topic=" + topic;
  }

  private String processSingleEvent(long nowEpochSeconds) {
    EventPacket packet;
    synchronized (eventQueue) {
      packet = eventQueue.pollFirst();
    }
    if (packet == null) {
      return "no queued events";
    }

    packet.attempts++;
    String result;
    switch (packet.topic) {
      case "economy.credit":
        result = handleEconomyCredit(packet.payload);
        break;
      case "permission.grant":
        result = handlePermissionGrant(packet.payload);
        break;
      case "security.lock":
        result = applyLock(normalizeId(packet.payload), nowEpochSeconds, "event");
        break;
      default:
        result = "event " + packet.id + " processed (topic=" + packet.topic + ")";
        break;
    }

    if (result.startsWith("failed") && packet.attempts < 2) {
      synchronized (eventQueue) {
        eventQueue.addLast(packet);
      }
      appendAudit("events.retry", packet.id + " topic=" + packet.topic + " attempt=" + packet.attempts);
      return result + " -> requeued";
    }

    if (result.startsWith("failed")) {
      incrementCounter("eventsDropped");
      appendAudit("events.drop", packet.id + " topic=" + packet.topic);
      return result + " -> dropped";
    }

    incrementCounter("eventsProcessed");
    appendAudit("events.process", packet.id + " topic=" + packet.topic);
    return result;
  }

  private String handleEconomyCredit(String payload) {
    String[] parts = String.valueOf(payload).split(":");
    if (parts.length < 2) {
      return "failed economy.credit: expected target:amount payload";
    }
    String target = normalizeId(parts[0]);
    long amount = parseLong(parts[1], -1L);
    if (amount <= 0) {
      return "failed economy.credit: amount must be positive";
    }
    PlayerProfile profile = profiles.computeIfAbsent(target, ignored -> new PlayerProfile());
    profile.coins.addAndGet(amount);
    return "economy.credit -> " + target + " +" + amount;
  }

  private String handlePermissionGrant(String payload) {
    String[] parts = String.valueOf(payload).split(":");
    if (parts.length < 2) {
      return "failed permission.grant: expected target:permission payload";
    }
    String target = normalizeId(parts[0]);
    String permission = normalizePermission(parts[1]);
    profiles.computeIfAbsent(target, ignored -> new PlayerProfile()).permissions.add(permission);
    return "permission.grant -> " + target + " +" + permission;
  }

  private String signEnvelopeForSender(String sender, String[] args, long nowEpochSeconds) {
    String topic = args.length >= 1 ? normalizeAction(args[0]) : "sync.delta";
    String payload =
        args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "coins=" + profiles.get(sender).coins.get();
    String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String envelope = buildNetworkEnvelope(topic, payload, nonce, nowEpochSeconds);

    lastEnvelopeBySender.put(sender, envelope);
    incrementCounter("envelopesSigned");
    appendAudit("network.sign", "sender=" + sender + " topic=" + topic + " nonce=" + nonce);
    return "envelope signed for topic=" + topic;
  }

  private String verifyLastEnvelope(String sender, long nowEpochSeconds, long maxClockSkewSeconds) {
    String envelope = lastEnvelopeBySender.get(sender);
    if (envelope == null || envelope.isBlank()) {
      return "no envelope found for sender=" + sender;
    }

    boolean valid = validateNetworkEnvelope(envelope, nowEpochSeconds, maxClockSkewSeconds);
    if (valid) {
      incrementCounter("envelopesValidated");
      return "envelope validated";
    }
    incrementCounter("envelopesRejected");
    registerSecurityFailure(sender, nowEpochSeconds, "invalid-envelope");
    return "envelope rejected";
  }

  private void registerSecurityFailure(String source, long nowEpochSeconds, String reason) {
    AtomicLong counter = securityFailuresBySource.computeIfAbsent(source, ignored -> new AtomicLong());
    long failures = counter.incrementAndGet();
    long lockSeconds = BASE_LOCK_SECONDS * Math.max(1L, failures);
    sourceLocks.put(source, nowEpochSeconds + lockSeconds);
    incrementCounter("locksApplied");
    appendAudit(
        "security.lock",
        "source=" + source + " failures=" + failures + " lockSeconds=" + lockSeconds + " reason=" + reason);
  }

  private String applyLock(String source, long nowEpochSeconds, String reason) {
    registerSecurityFailure(source, nowEpochSeconds, reason);
    return "source locked: " + source + " until " + sourceLocks.get(source);
  }

  private String clearLock(String source) {
    sourceLocks.remove(source);
    securityFailuresBySource.remove(source);
    appendAudit("security.unlock", "source=" + source);
    return "source unlocked: " + source;
  }

  private void grantPassiveIncome() {
    for (Map.Entry<String, PlayerProfile> entry : profiles.entrySet()) {
      PlayerProfile profile = entry.getValue();
      profile.coins.incrementAndGet();
      profile.lastSeenEpochSeconds.set(Instant.now().getEpochSecond());
    }
  }

  private boolean consumeActionQuota(String sender, long nowEpochSeconds) {
    Deque<Long> window = actionWindows.computeIfAbsent(sender, ignored -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && nowEpochSeconds - window.peekFirst() > ACTION_WINDOW_SECONDS) {
        window.pollFirst();
      }
      if (window.size() >= ACTION_WINDOW_MAX) {
        return false;
      }
      window.addLast(nowEpochSeconds);
      return true;
    }
  }

  private boolean canSourceProceed(String source, long nowEpochSeconds) {
    Long lockedUntil = sourceLocks.get(source);
    if (lockedUntil == null) {
      return true;
    }
    return lockedUntil <= nowEpochSeconds;
  }

  private String buildNetworkEnvelope(String topic, String payload, String nonce, long nowEpochSeconds) {
    String safeTopic = normalizeAction(topic);
    String safePayload = sanitizePayload(payload);
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

  private boolean validateNetworkEnvelope(String envelope, long nowEpochSeconds, long maxClockSkewSeconds) {
    Map<String, String> fields = parseEnvelope(envelope);
    String topic = fields.getOrDefault("topic", "");
    String payload = fields.getOrDefault("payload", "");
    String nonce = fields.getOrDefault("nonce", "");
    long ts = parseLong(fields.getOrDefault("ts", "0"), 0L);
    String signature = fields.getOrDefault("sig", "");

    if (nonce.isBlank() || ts <= 0) {
      return false;
    }
    if (Math.abs(nowEpochSeconds - ts) > Math.max(1L, maxClockSkewSeconds)) {
      return false;
    }

    Long nonceExpiry = usedNonceExpiresAt.get(nonce);
    if (nonceExpiry != null && nonceExpiry >= nowEpochSeconds) {
      appendAudit("network.replay_blocked", "nonce=" + nonce);
      return false;
    }

    String expectedSignature = signEnvelope(topic, payload, nonce, String.valueOf(ts));
    if (!expectedSignature.equals(signature)) {
      appendAudit("network.signature_mismatch", "nonce=" + nonce + " topic=" + topic);
      return false;
    }

    usedNonceExpiresAt.put(nonce, nowEpochSeconds + NONCE_WINDOW_SECONDS);
    appendAudit("network.accept", "nonce=" + nonce + " topic=" + topic);
    return true;
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
    for (String part : String.valueOf(envelope).split(";")) {
      int split = part.indexOf('=');
      if (split <= 0) {
        continue;
      }
      String key = part.substring(0, split).trim();
      String value = part.substring(split + 1).trim();
      out.put(key, value);
    }
    return out;
  }

  private void appendAudit(String action, String details) {
    String line = Instant.now().getEpochSecond() + " " + action + " " + String.valueOf(details);
    synchronized (auditTrail) {
      auditTrail.addLast(line);
      while (auditTrail.size() > AUDIT_LIMIT) {
        auditTrail.pollFirst();
      }
    }
  }

  private static String normalizeId(String value) {
    String normalized = String.valueOf(value == null ? "" : value).trim().toLowerCase();
    return normalized.isEmpty() ? "unknown" : normalized;
  }

  private static String normalizeAction(String value) {
    String normalized = String.valueOf(value == null ? "" : value).trim().toLowerCase();
    return normalized.isEmpty() ? "info" : normalized;
  }

  private static String normalizePermission(String value) {
    return String.valueOf(value == null ? "" : value).trim().toLowerCase().replace(' ', '.');
  }

  private static String sanitizePayload(String payload) {
    String normalized = String.valueOf(payload == null ? "" : payload).trim();
    if (normalized.length() > 512) {
      normalized = normalized.substring(0, 512);
    }
    return normalized.replace(';', ',');
  }

  private static boolean isReadOnlyAction(String action) {
    return Arrays.asList("info", "status", "audit").contains(normalizeAction(action));
  }

  private static boolean isLockControlAction(String action) {
    String normalized = normalizeAction(action);
    return normalized.equals("unlock-source") || normalized.equals("lock-source") || normalized.equals("info") || normalized.equals("status");
  }

  private AtomicLong ensureCounter(String key) {
    return counters.computeIfAbsent(key, ignored -> new AtomicLong());
  }

  private long incrementCounter(String key) {
    return ensureCounter(key).incrementAndGet();
  }

  private long counterValue(String key) {
    return ensureCounter(key).get();
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long requiredXp(long currentLevel) {
    return 100L * Math.max(1L, currentLevel);
  }

  private static final class EventPacket {
    private final String id;
    private final String topic;
    private final String payload;
    private final String sender;
    private final long createdAtEpochSeconds;
    private int attempts;

    private EventPacket(
        String id,
        String topic,
        String payload,
        String sender,
        long createdAtEpochSeconds) {
      this.id = id;
      this.topic = topic;
      this.payload = payload;
      this.sender = sender;
      this.createdAtEpochSeconds = createdAtEpochSeconds;
      this.attempts = 0;
    }
  }

  private static final class PlayerProfile {
    private final AtomicLong coins = new AtomicLong(0);
    private final AtomicLong xp = new AtomicLong(0);
    private final AtomicLong level = new AtomicLong(1);
    private final AtomicLong lastSeenEpochSeconds = new AtomicLong(Instant.now().getEpochSecond());
    private final Set<String> permissions = new HashSet<>();
  }
}
