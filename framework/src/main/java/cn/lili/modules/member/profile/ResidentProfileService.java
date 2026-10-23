package cn.lili.modules.member.profile;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.security.session.*;
import cn.lili.modules.connect.wechat.WechatIdentityService;
import cn.lili.modules.member.mapper.ResidentProfileMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@Service
public class ResidentProfileService {
    private final ResidentProfileMapper members;
    private final WechatIdentityService identities;
    private final ResidentProfileProperties properties;
    private final ResidentTransactions transactions;
    private final ResidentAvatarService avatars;
    private final WechatPhoneGateway phones;
    private final Cache<List<String>> cache;
    public ResidentProfileService(ResidentProfileMapper members, WechatIdentityService identities,
            ResidentProfileProperties properties, ResidentTransactions transactions,
            ResidentAvatarService avatars, WechatPhoneGateway phones, Cache<List<String>> cache) {
        this.members=members; this.identities=identities; this.properties=properties; this.transactions=transactions;
        this.avatars=avatars; this.phones=phones; this.cache=cache;
    }
    public Profile read(SessionIdentity identity) { return safe(() -> { check(identity); return view(valid(members.read(identity.subject()))); }); }
    public Profile edit(SessionIdentity identity, long version, String nickname, String avatarFileId) {
        if (version < 0 || (nickname == null && avatarFileId == null)) throw new ResidentProfileException(INVALID_REQUEST);
        String name = nickname == null ? null : nickname(nickname);
        return transactions.run(() -> {
            check(identity); var current = valid(members.lock(identity.subject())); version(current, version);
            String avatar = avatarFileId == null ? current.getAvatar() : avatars.ownedUrl(identity.subject(), avatarFileId);
            if (members.edit(identity.subject(), version, name == null ? current.getNickname() : name, avatar) != 1) {
                throw new ResidentProfileException(PROFILE_CONFLICT);
            }
            return view(valid(members.read(identity.subject())));
        });
    }
    public Profile phone(SessionIdentity identity, long version, String requestId, String phoneCode) {
        requestId(requestId); code(phoneCode); if(version < 0) throw new ResidentProfileException(INVALID_REQUEST);
        String hash = hash(identity.application()+"|"+identity.bindingId()+"|"+version+"|"+phoneCode);
        return safe(() -> {
            check(identity); var before = valid(members.read(identity.subject()));
            if (replay(before, requestId, hash)) return view(before);
            version(before, version);
            String phone = phones.verify(phoneCode); // No network call inside a database transaction.
            if (phone == null || !phone.matches("(?:1[0-9]{10}|\\+[1-9][0-9]{5,14})")) throw new ResidentProfileException(PHONE_REJECTED);
            return transactions.run(() -> {
                check(identity); var current = valid(members.lock(identity.subject()));
                if (replay(current, requestId, hash)) return view(current);
                version(current, version);
                var owners = members.phoneOwners(phone);
                if (owners == null) throw new ResidentProfileException(OPERATION_UNAVAILABLE);
                if (owners.size() > 1 || owners.stream().anyMatch(id -> !identity.subject().equals(id))) {
                    throw new ResidentProfileException(LINK_REQUIRED); // Never return B's identifier/details.
                }
                if (members.phone(identity.subject(), version, phone, requestId, hash) != 1) throw new ResidentProfileException(PROFILE_CONFLICT);
                return view(valid(members.read(identity.subject())));
            });
        });
    }
    public void check(SessionIdentity identity) {
        properties.requireSchema();
        if (identity == null || identity.domain()!=SessionDomain.RESIDENT) throw new ResidentProfileException(SESSION_REQUIRED);
        try { identities.verify(identity); } catch(RuntimeException e) { throw new ResidentProfileException(IDENTITY_UNAVAILABLE); }
    }
    private String nickname(String input) {
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
        if (value.codePointCount(0,value.length()) < 1 || value.codePointCount(0,value.length()) > 32
                || !value.matches("[\\p{L}\\p{N} _·.\\-]+")) throw new ResidentProfileException(NICKNAME_REJECTED);
        List<String> blocked;
        try { blocked=cache.get(CachePrefix.SENSITIVE.getPrefix()); }
        catch(RuntimeException e) { throw new ResidentProfileException(CONTENT_POLICY_PENDING); }
        if(blocked==null || blocked.stream().noneMatch(w -> w!=null && !w.isBlank())) throw new ResidentProfileException(CONTENT_POLICY_PENDING);
        String lower=value.toLowerCase(Locale.ROOT);
        if(blocked.stream().filter(Objects::nonNull).filter(w -> !w.isBlank())
                .anyMatch(w -> lower.contains(Normalizer.normalize(w,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)))) {
            throw new ResidentProfileException(NICKNAME_REJECTED);
        }
        return value;
    }
    public static ResidentProfileRow valid(ResidentProfileRow row) {
        if(row==null || row.getId()==null || !Boolean.TRUE.equals(row.getDisabled()) || !Boolean.FALSE.equals(row.getDeleteFlag())
                || row.getVersion()==null || row.getVersion()<0) throw new ResidentProfileException(IDENTITY_UNAVAILABLE);
        return row;
    }
    private static void version(ResidentProfileRow row,long expected) {
        if(row.getVersion()!=expected) throw new ResidentProfileException(PROFILE_CONFLICT);
    }
    private static boolean replay(ResidentProfileRow row,String id,String hash) {
        if(!id.equals(row.getPhoneRequestId())) return false;
        if(!hash.equals(row.getPhoneRequestHash())) throw new ResidentProfileException(REQUEST_CONFLICT);
        return true;
    }
    public static void requestId(String value) { if(value==null || !value.matches("[a-zA-Z0-9_-]{16,64}")) throw new ResidentProfileException(INVALID_REQUEST); }
    public static void code(String value) { if(value==null || !value.matches("[a-zA-Z0-9_-]{1,512}")) throw new ResidentProfileException(PHONE_REJECTED); }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new ResidentProfileException(OPERATION_UNAVAILABLE); }
    }
    private static Profile view(ResidentProfileRow row) {
        String mobile=row.getMobile(); boolean bound=mobile!=null && !mobile.isBlank();
        String masked=bound ? (mobile.length()>=7 ? mobile.substring(0,3)+"****"+mobile.substring(mobile.length()-4) : "****") : null;
        return new Profile(row.getId(),row.getNickname(),row.getAvatar(),masked,bound,row.getVersion(),
                row.getUpdateTime()==null?null:row.getUpdateTime().getTime());
    }
    private static <T> T safe(java.util.function.Supplier<T> work) {
        try { return work.get(); } catch(ResidentProfileException e) { throw e; }
        catch(RuntimeException e) { throw new ResidentProfileException(OPERATION_UNAVAILABLE); }
    }
    public record Profile(String memberId,String nickname,String avatar,String maskedMobile,boolean phoneBound,long version,Long updatedAt) {
        @Override public String toString() { return "ResidentProfile[REDACTED]"; }
    }
}
