package cn.lili.common.security.session;

/** Stored only as JSON with credential digests, never the credential itself. */
public record SessionRecord(int protocolVersion, String sid, SessionIdentity identity, Status status,
                            long generation, String accessDigest, String refreshDigest,
                            long createdAt, long accessExpiresAt, long absoluteExpiresAt, long issuedAt) {
    public enum Status { ACTIVE, REVOKED }

    public SessionRecord {
        // Older stored records have no issuedAt. Keep their original lifetime and fail-closed cutoff semantics.
        if (issuedAt == 0) issuedAt = createdAt;
        if (protocolVersion != 1 || sid == null || !sid.matches("[a-f0-9]{32}") || identity == null
                || status == null || generation < 0 || generation > 1_000_000_000L
                || !digest(accessDigest) || !digest(refreshDigest) || accessDigest.equals(refreshDigest)
                || createdAt < 0 || accessExpiresAt <= createdAt || absoluteExpiresAt < accessExpiresAt
                || issuedAt < createdAt || issuedAt >= accessExpiresAt) {
            throw new IllegalArgumentException("SESSION_RECORD_INVALID");
        }
    }

    public SessionRecord(int protocolVersion, String sid, SessionIdentity identity, Status status,
                         long generation, String accessDigest, String refreshDigest,
                         long createdAt, long accessExpiresAt, long absoluteExpiresAt) {
        this(protocolVersion,sid,identity,status,generation,accessDigest,refreshDigest,createdAt,accessExpiresAt,absoluteExpiresAt,createdAt);
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    SessionRecord rotated(String access, String refresh, long expiresAt) {
        return new SessionRecord(protocolVersion, sid, identity, status, generation + 1,
                access, refresh, createdAt, expiresAt, absoluteExpiresAt, issuedAt);
    }

    SessionRecord revoked() {
        return new SessionRecord(protocolVersion, sid, identity, Status.REVOKED, generation,
                accessDigest, refreshDigest, createdAt, accessExpiresAt, absoluteExpiresAt, issuedAt);
    }

    @Override
    public String toString() {
        return "SessionRecord[status=" + status + ", generation=" + generation + "]";
    }
}
